package com.walk_nie.bizgen.agent.mdgen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * md→コード リンクの負向セルフチェック：確定的ゲートが本当に止めることを証明する
 * （常に true を返すだけの「飾りゲート」でないことを示すため）。
 *   1) 仕様書式エラー（必須セクション欠落）→ SpecParser が拒否
 *   2) 追加・修正なのに対象ファイルが無い → SpecParser が拒否
 *   3) 追加先に同名メソッドが有る → SourceAssembler が拒否
 *   4) 修正対象メソッドが無い → SourceAssembler が拒否
 *   5) ホワイトリスト外 API → SourceAssembler が拒否
 *   6) Java として不正な文 → SourceAssembler が拒否
 *   7) 引数コメント数が引数数と不一致 → JavadocBuilder が拒否
 * さらに正向として、LLM が @param タグを書いても無害化され、実引数名が使われることを確認する。
 */
public final class MdgenDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 負向セルフチェック ===");

        // 1) 必須セクション欠落
        expectReject("書式エラー", () -> SpecParser.parse("# 仕様\n## 処理概要\n何かする"));

        // 2) 追加モードなのに対象ファイル未指定
        expectReject("対象ファイル欠落", () -> SpecParser.parse("""
                # 仕様
                ## 対象
                - クラス: Foo
                - メソッド: bar
                - 戻り値: void
                - モード: ADD_METHOD
                ## 処理概要
                bar を追加する
                """));

        MethodSpec spec1 = new MethodSpec("com.example", "FooService", "doIt", "void",
                List.of(), "説明", List.of("java.util.List"), List.of(),
                Operation.NEW_FILE, null);

        // 5) ホワイトリスト外 API
        MethodBody badApi = body(List.of("DangerUtils.dropAllTables();"), List.of());
        expectReject("ホワイトリスト外 API", () -> new SourceAssembler().assemble(spec1, badApi));

        // 6) Java として不正
        MethodBody badJava = body(List.of("this is not java ;;;"), List.of());
        expectReject("不正な Java", () -> new SourceAssembler().assemble(spec1, badJava));

        // 7) 引数コメント数が引数数と不一致（1 引数なのにコメント 2 件）
        MethodSpec spec2 = new MethodSpec("com.example", "BarService", "run", "void",
                List.of("String key"), "説明", List.of(), List.of(), Operation.NEW_FILE, null);
        MethodBody badArity = body(List.of(), List.of("コメント1", "コメント2"));
        expectReject("引数コメント数不一致", () -> new SourceAssembler().assemble(spec2, badArity));

        // 3)(4) 既存ファイルを使った追加／修正の境界チェック
        Path existing = Path.of("specs/existing/ReceiptReconciliationService.java");
        if (Files.isRegularFile(existing)) {
            MethodSpec addDup = new MethodSpec("com.example.biz", "ReceiptReconciliationService",
                    "matchPayments", "void", List.of("List<String> payments", "List<String> receipts"),
                    "重名追加", List.of(), List.of(), Operation.ADD_METHOD, existing.toString());
            expectReject("同名メソッド追加", () -> new SourceAssembler().assemble(addDup, body(List.of(), List.of())));

            MethodSpec modMissing = new MethodSpec("com.example.biz", "ReceiptReconciliationService",
                    "noSuchMethod", "void", List.of(), "存在しない", List.of(), List.of(),
                    Operation.MODIFY_METHOD, existing.toString());
            expectReject("対象メソッド不在", () -> new SourceAssembler().assemble(modMissing, body(List.of(), List.of())));
        }

        // 正向：モデルが @param/@return タグを書いても、実引数名から生成し直される
        System.out.println("\n=== 正向セルフチェック（タグの確定的生成）===");
        MethodSpec spec3 = new MethodSpec("com.example", "ZService", "calc", "int",
                List.of("int realArg"), "説明", List.of(), List.of(), Operation.NEW_FILE, null);
        MethodBody injected = new MethodBody("理由", List.of(),
                "計算する。\n@param fakeArg 模型が勝手に書いたタグ", // わざと偽タグを混入
                List.of("本当の引数（実名から生成）"), "計算結果",
                "計算サービス（責務を集約）", "計算パッケージ（依存を保つ）",
                List.of("return realArg;"), List.of("値を返す（呼び出し側が使うため）"));
        String src = new SourceAssembler().assemble(spec3, injected).source();
        check(src.contains("@param realArg"), "@param が実引数名から生成された");
        check(!src.contains("fakeArg"), "モデルが書いた偽タグ名は採用されない");
        check(src.contains("@return"), "非 void なら @return が生成される");
        check(src.contains("/**") && src.contains("計算サービス"), "クラス Javadoc が生成された");
        check(injected.packageComment() != null, "パッケージ説明が ModelBody に保持された");

        // ---- 変数解決・階層ロードのセルフチェック ----
        System.out.println("\n=== 変数 / 階層セルフチェック ===");
        TemplateResolver tr = new TemplateResolver(Map.of("applicationId", "APP001"));
        check("ID=APP001 で処理".equals(tr.resolve("ID={{applicationId}} で処理")), "{{var}} が置換される");
        check("APP001".equals(new TemplateResolver(Map.of()).resolve("{{applicationId:APP001}}")),
                "{{name:既定値}} が既定値で置換される");
        check(List.of("applicationId", "userId").equals(
                        TemplateResolver.placeholders("{{applicationId}} と {{userId}} と {{applicationId}}")),
                "プレースホルダ名が重複なし・出現順に取れる");
        expectReject("未解決変数", () -> new TemplateResolver(Map.of()).resolve("ID={{applicationId}}"));

        // 6 種の命名規則（template-studio/shared/casevars.js と同じ結果）
        check("OrgTree".equals(CaseVars.apply("org_tree", CaseVars.Variant.PASCAL)), "大驼峰 PascalCase");
        check("orgTree".equals(CaseVars.apply("org_tree", CaseVars.Variant.CAMEL)), "小驼峰 camelCase");
        check("ORG_TREE".equals(CaseVars.apply("org_tree", CaseVars.Variant.UPPER_SNAKE)), "大蛇形 UPPER_SNAKE");
        check("org_tree".equals(CaseVars.apply("OrgTree", CaseVars.Variant.SNAKE)), "小蛇形 snake_case");
        check("OrgTree".equals(CaseVars.apply("orgTree", CaseVars.Variant.UPPER_FIRST)), "首字母大写（先頭のみ）");
        check("orgTree".equals(CaseVars.apply("OrgTree", CaseVars.Variant.LOWER_FIRST)), "首字母小写（先頭のみ）");
        check("orgTreeNode".equals(CaseVars.camel("org_tree_node")), "参照名の小驼峰正規化");
        CaseVars.Ref cvRef = CaseVars.resolve("applicationIdUpperSnake");
        check(cvRef != null && cvRef.base().equals("applicationId")
                        && cvRef.variant() == CaseVars.Variant.UPPER_SNAKE,
                "長い接尾辞 UpperSnake が Upper より優先して解決される");
        check(CaseVars.resolve("Snake") == null, "接尾辞だけの名前は派生とみなさない");
        check("App001".equals(new TemplateResolver(Map.of("applicationId", "APP001"))
                        .resolve("{{applicationIdPascal}}")),
                "{{keyPascal}} が定義値から確定的に派生する");
        check("APP001".equals(new TemplateResolver(Map.of("application_id", "APP001"))
                        .resolve("{{applicationId}}")),
                "application_id ⇔ applicationId が camel 正規化で自動マッチする");
        check("明示".equals(new TemplateResolver(Map.of("key", "base", "keyCamel", "明示"))
                        .resolve("{{keyCamel}}")), "明示定義は派生より優先される");
        expectReject("camel 正規化が曖昧な変数", () -> new TemplateResolver(
                Map.of("org_id", "A", "orgId", "B")).resolve("{{org-id}}"));

        // 共通 md での変数定義：定義→参照の順で解決し、--var が優先、定義記法は文脈に残らない
        check("APP001 / APP001".equals(new TemplateResolver(Map.of())
                        .resolve("{{applicationId=APP001}} / {{applicationId}}")),
                "共通mdの {{key=value}} 定義が値へ展開され、後続参照も解決される");
        check("APP002".equals(new TemplateResolver(Map.of("applicationId", "APP002"))
                        .resolve("{{applicationId=APP001}}")), "--var が共通定義より優先");
        check(!new TemplateResolver(Map.of()).resolve("{{applicationId=APP001}}").contains("{{"),
                "定義記法は LLM へ渡す文脈に残らない");
        check(TemplateResolver.placeholders("{{applicationId=APP001}} と {{userId}}")
                        .equals(List.of("userId")), "定義は未解決プレースホルダに数えない");

        // ローダ横断：共通 md で定義 → メソッド級 md で参照（--var 無しで通る）
        Path defTmp = Files.createTempDirectory("bizgen-def-");
        Path commonDef = defTmp.resolve("common-def.md");
        Path useMethod = defTmp.resolve("use-method.md");
        Files.writeString(commonDef, "# プロジェクト共通\nID={{newId=ID9}} を使う。\n");
        Files.writeString(useMethod, """
                # メソッド
                ## 対象
                - クラス: DefService
                - メソッド: readId
                - 戻り値: String
                ## 処理概要
                {{newId}} を返す。
                """);
        LayeredSpec defLayered = LayeredSpecLoader.load(useMethod,
                Map.of(SpecLevel.PROJECT_COMMON, List.of(commonDef)), Map.of());
        check(defLayered.method().summary().contains("ID9"), "共通md定義がメソッド級参照へ反映された");
        check("ID9".equals(defLayered.vars().get("newId")), "変数表に共通定義が採用された");
        check(!defLayered.contexts().get(0).content().contains("{{"), "文脈に未解決プレースホルダが残らない");

        Path badMethodDef = defTmp.resolve("bad-method-def.md");
        Files.writeString(badMethodDef, """
                # メソッド
                ## 対象
                - クラス: BadService
                - メソッド: bad
                - 戻り値: void
                ## 処理概要
                {{x=1}} を使う。
                """);
        expectReject("メソッド級での変数定義", () -> {
            try { LayeredSpecLoader.load(badMethodDef, Map.of(), Map.of()); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });

        // 階層ローダ：上位級の変数が下位級へ共有されること／文脈が粗い順に並ぶこと
        Path tmpDir = Files.createTempDirectory("bizgen-layer-");
        Path common = tmpDir.resolve("project.md");
        Path method = tmpDir.resolve("method.md");
        Files.writeString(common, "# プロジェクト共通\nID={{applicationId}} を使う。命名は camelCase。\n");
        Files.writeString(method, """
                # メソッド
                ## 対象
                - クラス: OrderService
                - メソッド: findByApp
                - 戻り値: String
                ## 処理概要
                {{applicationId}} に紐づく名称を返す。
                """);
        Map<SpecLevel, List<Path>> layers = Map.of(SpecLevel.PROJECT_COMMON, List.of(common));
        LayeredSpec layered = LayeredSpecLoader.load(method, layers, Map.of("applicationId", "APP001"));
        check(layered.method().summary().contains("APP001"), "メソッド級 md の変数も解決された");
        check(layered.contexts().size() == 1
                        && layered.contexts().get(0).content().contains("APP001"),
                "上位級 md の変数も解決され文脈に保持された");
        check(BodyGenerator.buildPrompt(layered).indexOf("プロジェクト共通")
                        < BodyGenerator.buildPrompt(layered).indexOf("今回の実装対象"),
                "prompt は共通制約→メソッド仕様の順");

        // 共通フォルダ自動走査：置くだけで読込まれ、レベル順→相対パス順で文脈に入ること
        Path commonRoot = Files.createTempDirectory("bizgen-common-");
        Files.createDirectories(commonRoot.resolve("java-common"));
        Files.createDirectories(commonRoot.resolve("project-common"));
        Files.createDirectories(commonRoot.resolve("feature"));
        Files.writeString(commonRoot.resolve("java-common").resolve("01-rules.md"), "# java共通\n例外は握り潰さない。\n");
        Files.writeString(commonRoot.resolve("java-common").resolve("02-style.md"), "# java共通\n命名は camelCase。\n");
        Files.writeString(commonRoot.resolve("project-common").resolve("app.md"),
                "# プロジェクト共通\nID={{applicationId}} を使う。\n");
        Files.writeString(commonRoot.resolve("feature").resolve("01-match.md"), "# 機能\n一致のみ返す。\n");
        Files.writeString(commonRoot.resolve("feature").resolve("02-order.md"), "# 機能\n順番を保つ。\n");
        Files.writeString(commonRoot.resolve("_memo.md"), "説明用なので読まない。\n");

        Map<SpecLevel, List<Path>> auto = CommonSpecScanner.scan(commonRoot);
        check(CommonSpecScanner.count(auto) == 5, "共通フォルダの md が自動検出された（_memo.md は除外）");
        check(auto.get(SpecLevel.JAVA_COMMON).size() == 2
                        && auto.get(SpecLevel.JAVA_COMMON).get(0).getFileName().toString().equals("01-rules.md"),
                "1レベル=1フォルダ、 folder 内複数 md は相対パス順");
        check(auto.get(SpecLevel.FEATURE).size() == 2, "機能フォルダの複数 md も検出された");
        check(auto.get(SpecLevel.PROJECT_COMMON).size() == 1, "直下の md はプロジェクト共通扱い");

        Map<SpecLevel, List<Path>> explicit = Map.of(SpecLevel.CLASS, List.of(common));
        LayeredSpec autoLayered = LayeredSpecLoader.load(method,
                CommonSpecScanner.merge(auto, explicit), Map.of("applicationId", "APP001"));
        check(autoLayered.contexts().size() == 6, "自動5件＋明示1件が文脈に入った");
        check(autoLayered.contexts().get(0).level() == SpecLevel.JAVA_COMMON
                        && autoLayered.contexts().get(4).level() == SpecLevel.FEATURE
                        && autoLayered.contexts().get(5).level() == SpecLevel.CLASS,
                "文脈は java共通→プロジェクト共通→機能→クラス の順");
        check(autoLayered.contexts().get(2).content().contains("APP001"), "自動読込分も変数解決された");
        check(CommonSpecScanner.count(CommonSpecScanner.merge(Map.of(), explicit)) == 1,
                "--no-common 相当では自動分が入らない");

        Path badRoot = Files.createTempDirectory("bizgen-common-bad-");
        Files.createDirectories(badRoot.resolve("typo"));
        Files.writeString(badRoot.resolve("typo").resolve("a.md"), "x");
        expectReject("未知の共通サブフォルダ", () -> {
            try { CommonSpecScanner.scan(badRoot); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });

        // 設定ファイル驱动的 LLM 情報（--live 不要）
        Path cfgDir = Files.createTempDirectory("bizgen-config-");
        Path cfg = cfgDir.resolve("agent.properties");
        Files.writeString(cfg, "llm.mode=live\nllm.base-url=http://127.0.0.1:9000\n"
                + "llm.model=Test/Model\ncommon.dir=specs/custom\n");
        AgentConfig loaded = AgentConfig.load(cfg);
        check(loaded.live() && "http://127.0.0.1:9000".equals(loaded.baseUrl())
                        && "Test/Model".equals(loaded.model()),
                "設定ファイルから LLM モード/URL/モデル名を取得");
        check(Path.of("specs/custom").equals(loaded.commonDir()), "設定ファイルから共通フォルダを取得");
        Path badCfg = cfgDir.resolve("bad.properties");
        Files.writeString(badCfg, "llm.modle=live\n");
        expectReject("不明な設定キー", () -> {
            try { AgentConfig.load(badCfg); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        Path badMode = cfgDir.resolve("bad-mode.properties");
        Files.writeString(badMode, "llm.mode=fast\n");
        expectReject("不正な llm.mode", () -> {
            try { AgentConfig.load(badMode); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        Path missingCfg = cfgDir.resolve("missing.properties");
        expectReject("明示設定ファイル不在", () -> {
            try { AgentConfig.load(missingCfg); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        check(!AgentConfig.load(null).live(), "既定設定ファイルの llm.mode=mock が効く");

        System.out.println("\n全てのゲートが有効。md→コード リンクのセルフチェック完了。");
    }

    /** 日本語コメント付きの最小 MethodBody を作る（検査対象は本体なのでコメントは固定）。 */
    private static MethodBody body(List<String> statements, List<String> paramComments) {
        return new MethodBody("検査用", List.of(), "テスト用メソッド（確認のため）",
                paramComments, "", "", "", statements, List.of());
    }

    private static void expectReject(String label, Runnable action) {
        try {
            action.run();
            throw new AssertionError("ゲート失效: " + label + " が受け入れられた");
        } catch (IllegalArgumentException | SecurityException expected) {
            System.out.println("  [拒否] " + label + " -> " + expected.getMessage().split("\n")[0]);
        }
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError("正向チェック失敗: " + label);
        System.out.println("  [OK] " + label);
    }
}
