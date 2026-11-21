package com.walk_nie.bizgen.agent.sqlgen;

import com.walk_nie.bizgen.agent.VllmClient;
import com.walk_nie.bizgen.agent.mdgen.AgentConfig;
import com.walk_nie.bizgen.agent.mdgen.CommonSpecScanner;
import com.walk_nie.bizgen.agent.mdgen.SpecLevel;
import com.walk_nie.bizgen.agent.mdgen.TemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自然言語の概要 md から LLM に表構造／SQL 要求の下書きを作らせ、
 * その下書きを確定側ゲートへ通して SQL を保存する CLI。
 *
 * 使い方：
 *   SqlDraftFromMd &lt;概要.md&gt; [--out &lt;dir&gt;] [--config &lt;file&gt;]
 *                  [--var KEY=VALUE ...] [--common-dir &lt;dir&gt;] [--no-common] [--no-render]
 *
 * <p>LLM 情報は設定ファイル（既定 config/agent.properties）から取得する。
 * モデルが作るのは「大概の定義」だけ。SQL 本体は Java が確定的に組み立て、
 * 下書き md もレビュー用に必ずファイルへ残す。
 */
public final class SqlDraftFromMd {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        Path descriptionMd = Path.of(args[0]);
        require(Files.isRegularFile(descriptionMd), "概要 md が無い: " + descriptionMd.toAbsolutePath());

        AgentConfig config = AgentConfig.load(optionValue(args, "--config").map(Path::of).orElse(null));
        Path outDir = optionValue(args, "--out").map(Path::of).orElse(Path.of("generated/sql-draft"));
        boolean noCommon = contains(args, "--no-common");
        boolean noRender = contains(args, "--no-render");
        Path commonDir = optionValue(args, "--common-dir").map(Path::of).orElse(config.commonDir());
        Path tableDir = optionValue(args, "--tables-dir").map(Path::of).orElse(config.tableDir());

        // 1) 変数表：共通 md の {{key=value}} → --var で上書き
        Map<String, String> vars = collectVars(args);
        List<String> contexts = new ArrayList<>();
        if (!noCommon && Files.isDirectory(commonDir)) {
            Map<SpecLevel, List<Path>> auto = CommonSpecScanner.scan(commonDir);
            for (SpecLevel level : SpecLevel.values()) {
                for (Path p : auto.getOrDefault(level, List.of())) {
                    String raw = read(p);
                    for (Map.Entry<String, String> e : TemplateResolver.definitions(raw).entrySet()) {
                        vars.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
            }
        }
        TemplateResolver resolver = new TemplateResolver(vars);
        if (!noCommon && Files.isDirectory(commonDir)) {
            Map<SpecLevel, List<Path>> auto = CommonSpecScanner.scan(commonDir);
            for (SpecLevel level : SpecLevel.values()) {
                for (Path p : auto.getOrDefault(level, List.of())) {
                    contexts.add("## 【" + level.japanese() + "】" + p + "\n" + resolver.resolve(read(p)));
                }
            }
        }

        // prompt テンプレート（md）：--prompt 明示 ＞ 既定ファイル ＞ 内蔵フォールバック
        Optional<Path> promptOpt = optionValue(args, "--prompt").map(Path::of);
        String promptTemplate = null;
        Path promptSource = null;
        if (promptOpt.isPresent()) {
            promptTemplate = SqlPromptTemplate.load(promptOpt.get()); // 無ければ例外
            promptSource = promptOpt.get();
        } else if (Files.isRegularFile(Path.of(SqlPromptTemplate.DEFAULT_PATH))) {
            promptSource = Path.of(SqlPromptTemplate.DEFAULT_PATH);
            promptTemplate = SqlPromptTemplate.load(promptSource);
        }

        String description = resolver.resolve(read(descriptionMd));

        // 表構造ライブラリ（1 ファイル 1 テーブル）を読み、LLM へ文脈として渡す
        TableSchemaLibrary library = noCommon ? TableSchemaLibrary.load(null)
                : TableSchemaLibrary.load(tableDir);
        String detectedTable = SqlDraftGenerator.detectTable(description);
        // LLM へは概要が指す表の構造だけを渡す（無関係な表・列を混ぜないため）
        TableSchemaLibrary promptLibrary = library.find(detectedTable) != null
                ? library.subset(List.of(detectedTable)) : library;

        // 2) LLM で「大概の定義」を作る（mock/live は設定ファイル次第）
        VllmClient client = new VllmClient(config.baseUrl(), config.model());
        SqlDraft draft = new SqlDraftGenerator(client, config.live())
                .generate(description, vars, contexts, promptLibrary, promptTemplate);

        // 3) 下書きをレビュー用に落とす（ゲートに通る前の生の状態も残す）
        Files.createDirectories(outDir);
        String base = baseName(descriptionMd.getFileName().toString());
        Path tableOut = outDir.resolve(base + "_table.md");
        Path requestOut = outDir.resolve(base + "_request.md");
        Path noteOut = outDir.resolve(base + "_note.md");
        Files.writeString(tableOut, draft.tableMd() + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(requestOut, draft.requestMd() + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(noteOut, draft.note() + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("=== SQL 下書き生成（LLM は意味変換のみ）===");
        System.out.println("  設定ファイル  : " + config.source());
        System.out.println("  LLM モード    : " + config.mode() + "（設定ファイル）");
        System.out.println("  LLM モデル    : " + config.model());
        System.out.println("  prompt        : " + (promptSource != null
                ? promptSource.toAbsolutePath() + "（md テンプレート）" : "内蔵フォールバック"));
        System.out.println("  概要 md       : " + descriptionMd.toAbsolutePath());
        System.out.println("  共通 md       : " + contexts.size() + " 件");
        System.out.println("  表構造ライブラリ: " + library.size() + " テーブル（" + tableDir.toAbsolutePath() + "）");
        System.out.println("  LLM へ渡す表構造: " + promptLibrary.size() + " / " + library.size()
                + " テーブル → " + promptLibrary.all().stream().map(TableSchemaLibrary.Entry::tableName).toList());
        System.out.println("  概要のテーブル: " + detectedTable
                + (library.find(detectedTable) != null ? " → ライブラリの定義を使用" : " → ライブラリに無きは推定"));
        System.out.println("  変数          : " + vars);
        System.out.println("  下書き        : " + tableOut.toAbsolutePath());
        System.out.println("                " + requestOut.toAbsolutePath());
        System.out.println("  判断メモ      : " + noteOut.toAbsolutePath());

        if (noRender) {
            System.out.println("\n--no-render 指定のため SQL 生成はスキップ。下書きをレビューしてください。");
            return;
        }

        // 4) 下書きを確定側パイプラインへ：正規化 → 変数解決 → 解析 → ゲート → 落盤
        String tableMd = resolver.resolve(draft.tableMd());
        String requestMd = resolver.resolve(SqlVariables.normalizeRequest(draft.requestMd()));
        TableSchema schema = TableSchemaParser.parse(tableMd);
        SqlRequest req = SqlRequestParser.parse(requestMd);
        String sql = new SqlRenderer().render(req, schema);

        Path sqlOut = outDir.resolve(req.tableName() + "_" + req.kind().name().toLowerCase() + ".sql");
        Files.writeString(sqlOut, sql + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("\n=== 確定側ゲート通過 → SQL 落盤 ===");
        System.out.println("  種別/テーブル : " + req.kind() + " / " + req.tableName());
        System.out.println("  バインド変数  : " + SqlVariables.bindNames(req.conditions()));
        System.out.println("  書き出し先    : " + sqlOut.toAbsolutePath());
        System.out.println("\n--- 生成 SQL（下書きのためレビュー必須）---\n" + sql);
    }

    private static String baseName(String fileName) {
        String s = fileName.replaceAll("\\.[^.]+$", "");
        s = s.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isBlank() ? "draft" : s;
    }

    private static String read(Path p) throws IOException {
        String text = Files.readString(p, StandardCharsets.UTF_8);
        return text.isEmpty() || text.charAt(0) != '\uFEFF' ? text : text.substring(1);
    }

    private static Map<String, String> collectVars(String[] args) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (!"--var".equals(args[i])) continue;
            String item = args[i + 1];
            int eq = item.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("--var は KEY=VALUE 形式: " + item);
            vars.put(item.substring(0, eq).strip(), item.substring(eq + 1).strip());
        }
        return vars;
    }

    private static Optional<String> optionValue(String[] args, String opt) {
        for (int i = 0; i < args.length - 1; i++) {
            if (opt.equals(args[i])) return Optional.of(args[i + 1]);
        }
        return Optional.empty();
    }

    private static boolean contains(String[] args, String flag) {
        for (String a : args) if (flag.equals(a)) return true;
        return false;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static void usage() {
        System.err.println("使い方: SqlDraftFromMd <概要.md> [--out <dir>] [--config <file>]");
        System.err.println("        [--var KEY=VALUE ...] [--tables-dir <dir>] [--common-dir <dir>] [--no-common] [--no-render]");
        System.err.println("  LLM 情報は設定ファイルから取得（既定 config/agent.properties: llm.mode=mock|live）");
        System.err.println("  --prompt     prompt テンプレート md（既定 specs/prompts/sql-draft.md、無ければ内蔵）");
        System.err.println("  --tables-dir 表構造ライブラリ（既定 specs/tables、1 ファイル 1 テーブル）");
        System.err.println("  --no-render 下書き md だけを残す（SQL 生成なし）");
    }
}
