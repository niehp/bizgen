package com.walk_nie.bizgen.agent;

import java.util.List;
import java.util.Map;

/**
 * 最小可运行骨架的入口。
 *
 * 用法：
 *   java com.walk_nie.bizgen.agent.Main            # 离线自检：生成 schema + 用 mock 输出验证回填
 *   java com.walk_nie.bizgen.agent.Main --live     # 连接本地 vLLM 实测（需先启动模型服务）
 *
 * 环境变量（--live 时）：
 *   VLLM_BASE_URL  默认 http://127.0.0.1:8000
 *   VLLM_MODEL     默认 Qwen/Qwen2.5-Coder-14B-Instruct
 */
public final class Main {

    private static final String SYSTEM = """
            あなたは日系SIのコード生成アシスタントです。
            仕様書を構造化する際、必ず指定されたJSONスキーマに従ってください。
            推論は reasoning フィールドに自由記述し、それ以外のフィールドはスキーマの制約に従うこと。
            許可されていないAPI、スキーマ外のフィールドは出力してはならない。
            """;

    public static void main(String[] args) throws Exception {
        boolean live = args.length > 0 && "--live".equals(args[0]);

        // ---- 单一真源：同一 record 同时产出 schema 与反序列化目标 ----
        String mappingSchema = RecordSchema.toJsonSchema(SemanticMapping.class);
        String draftSchema = RecordSchema.toJsonSchema(CodeDraft.class);

        System.out.println("=== 1) record 派生 JSON Schema: SemanticMapping ===");
        System.out.println(mappingSchema);
        System.out.println("\n=== 2) record 派生 JSON Schema: CodeDraft（嵌套 2 层）===");
        System.out.println(draftSchema);

        String mappingJson;
        if (live) {
            String base = env("VLLM_BASE_URL", "http://127.0.0.1:8000");
            String model = env("VLLM_MODEL", "Qwen/Qwen2.5-Coder-14B-Instruct");
            VllmClient client = new VllmClient(base, model);
            String spec = """
                    入金消込バッチの新規メソッドを実装する。
                    対象クラス: ReceiptReconciliationService
                    対象メソッド: matchPayments
                    使用してよいAPI: PaymentRepository.findByDate, ReceiptRepository.findOpen
                    受入基準: 消込結果が0件の場合は空リストを返すこと。
                    """;
            System.out.println("\n[live] 连接 " + base + " / 模型 " + model);
            mappingJson = client.guidedJson(SYSTEM, spec, mappingSchema);
        } else {
            // 离线 mock：模拟一段「结构合法」的模型回填
            mappingJson = """
                    {
                      "reasoning": "仕様書から新規メソッド追加の意図を抽出した。",
                      "intent": "NEW_METHOD",
                      "targetClass": "ReceiptReconciliationService",
                      "targetMethod": "matchPayments",
                      "apiCalls": ["PaymentRepository.findByDate", "ReceiptRepository.findOpen"],
                      "acceptanceChecks": ["消込結果が0件の場合は空リストを返すこと"]
                    }
                    """;
            System.out.println("\n[offline] 使用 mock 回填验证链路");
        }

        // ---- 3) 回填 → record（严格校验，字段缺失/枚举越界都会抛异常）----
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) Json.parse(mappingJson);
        SemanticMapping mapping = RecordSchema.fromMap(map, SemanticMapping.class);

        System.out.println("\n=== 3) 回填还原为强类型 record ===");
        System.out.println("  intent        = " + mapping.intent());
        System.out.println("  targetClass   = " + mapping.targetClass());
        System.out.println("  targetMethod  = " + mapping.targetMethod());
        System.out.println("  apiCalls      = " + mapping.apiCalls());
        System.out.println("  acceptance    = " + mapping.acceptanceChecks());

        // ---- 4) 确定性闸门示例：Java 侧校验白名单（模型无权越界）----
        List<String> whitelist = List.of(
                "PaymentRepository.findByDate",
                "ReceiptRepository.findOpen",
                "ReceiptRepository.markMatched");
        for (String api : mapping.apiCalls()) {
            if (!whitelist.contains(api)) {
                throw new SecurityException("模型输出命中白名单外 API，拒绝继续: " + api);
            }
        }
        System.out.println("\n=== 4) 确定性闸门：API 白名单校验通过 ===");

        // ---- 5) 嵌套 record 回填演示（CodeDraft 2 层）----
        String draftJson = """
                {
                  "reasoning": "既存メソッドの末尾に1行追加する。",
                  "imports": ["java.util.List"],
                  "patchHunks": [
                    {"targetMethod":"matchPayments","statement":"return result;","kind":"INSERT_AFTER"}
                  ]
                }
                """;
        @SuppressWarnings("unchecked")
        Map<String, Object> draftMap = (Map<String, Object>) Json.parse(draftJson);
        CodeDraft draft = RecordSchema.fromMap(draftMap, CodeDraft.class);
        System.out.println("\n=== 5) 嵌套 record 回填（CodeDraft）===");
        System.out.println("  " + draft);

        // ---- 6) 负向自检：证明闸门真的会拦人（而不是永远返回 true）----
        System.out.println("\n=== 6) 负向自检（确定性闸门必须拒绝）===");
        checkRejects("枚举越界", """
                {"reasoning":"x","intent":"DO_SOMETHING_ELSE","targetClass":"A","targetMethod":"b",
                 "apiCalls":[],"acceptanceChecks":[]}
                """);
        checkRejects("字段缺失", """
                {"reasoning":"x","intent":"NEW_METHOD","targetClass":"A"}
                """);
        System.out.println("  两项非法输入均被拒绝，闸门有效。");
        System.out.println("\n骨架自检完成。");
    }

    /** 断言给定 JSON 会在回填阶段被拒绝。 */
    private static void checkRejects(String label, String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) Json.parse(json);
            RecordSchema.fromMap(m, SemanticMapping.class);
            throw new AssertionError("闸门失效：非法输入被接受（" + label + "）");
        } catch (IllegalArgumentException expected) {
            System.out.println("  [拒绝] " + label + " -> " + expected.getMessage());
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
