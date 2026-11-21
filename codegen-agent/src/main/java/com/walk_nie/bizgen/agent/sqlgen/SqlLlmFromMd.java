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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 「SQL 雛形（種別別 prompt テンプレート）＋ md の表構造」を大模型へ渡し、
 * <b>SQL 文そのものを生成させてファイルへ保存する</b> CLI。
 *
 * <pre>
 * SqlLlmFromMd &lt;概要.md&gt; [--kind SELECT|UPDATE|DELETE] [--out &lt;dir&gt;]
 *              [--config &lt;file&gt;] [--prompt &lt;file&gt;] [--var KEY=VALUE ...]
 *              [--common-dir &lt;dir&gt;] [--tables-dir &lt;dir&gt;] [--no-common]
 * </pre>
 *
 * <p>処理順（ルーティングは全て Java が確定）：
 * <ol>
 *   <li>共通 md の変数定義を回収 → 変数解決</li>
 *   <li>表構造ライブラリ（1 表 1 md）→ {@link NameMapping} を構築</li>
 *   <li>概要 md・共通文脈の<b>日本語名を英語物理名へ変換</b>（モデルへ渡す前）</li>
 *   <li>種別から prompt テンプレート（SQL 雛形入り）を選択</li>
 *   <li>設定ファイルの {@code llm.mode} に従い mock / live で SQL を生成</li>
 *   <li>下書き {@code _draft.sql} を<b>人工レビュー用に必ず残す</b></li>
 *   <li>{@link SqlTextGate} の確定的ゲート → 通ったものだけ {@code .sql} へ落盤</li>
 * </ol>
 *
 * <p>SELECT テンプレートは多表結合（JOIN）に対応する。UPDATE / DELETE は
 * WHERE 必須など、種別ごとの安全性をテンプレートとゲートの両方で担保する。
 */
public final class SqlLlmFromMd {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        Path descriptionMd = Path.of(args[0]);
        require(Files.isRegularFile(descriptionMd), "概要 md が無い: " + descriptionMd.toAbsolutePath());

        AgentConfig config = AgentConfig.load(optionValue(args, "--config").map(Path::of).orElse(null));
        Path outDir = optionValue(args, "--out").map(Path::of).orElse(Path.of("generated/sql-llm"));
        boolean noCommon = contains(args, "--no-common");
        Path commonDir = optionValue(args, "--common-dir").map(Path::of).orElse(config.commonDir());
        Path tableDir = optionValue(args, "--tables-dir").map(Path::of).orElse(config.tableDir());

        // 1) 変数表と共通 md の文脈（翻訳前の生テキスト）
        Map<String, String> vars = collectVars(args);
        List<String> rawContexts = new ArrayList<>();
        if (!noCommon && Files.isDirectory(commonDir)) {
            Map<SpecLevel, List<Path>> auto = CommonSpecScanner.scan(commonDir);
            for (SpecLevel level : SpecLevel.values()) {
                for (Path p : auto.getOrDefault(level, List.of())) {
                    String raw = read(p);
                    for (Map.Entry<String, String> e : TemplateResolver.definitions(raw).entrySet()) {
                        vars.putIfAbsent(e.getKey(), e.getValue());
                    }
                    rawContexts.add("## 【" + level.japanese() + "】" + p + "\n" + raw);
                }
            }
        }

        // 2) 表構造ライブラリ → 日本語名→英語名の変換表
        TableSchemaLibrary library = TableSchemaLibrary.load(tableDir);
        NameMapping mapping = NameMapping.from(library);

        // 3) 種別は 100% 確定側が決める（--kind ＞ 仕様 md ＞ キーワード）
        String rawDescription = read(descriptionMd);
        SqlKind kind = resolveKind(args, rawDescription);

        // 4) 変数解決 → 日本語名を英語物理名へ変換（大模型へ渡す<b>前</b>に完了させる）
        TemplateResolver resolver = new TemplateResolver(vars);
        String description = mapping.translate(resolver.resolve(rawDescription));
        List<String> contexts = rawContexts.stream()
                .map(c -> mapping.translate(resolver.resolve(c)))
                .toList();

        // 4b) 今回の SQL で使う表だけをライブラリから選ぶ（LLM へ渡す表構造を制限）
        TableSelector.Selection selection = TableSelector.select(rawDescription, description,
                library, mapping);

        // 5) 種別別 prompt テンプレート（SQL 雛形入り）
        Path promptPath = optionValue(args, "--prompt").map(Path::of)
                .orElseGet(() -> SqlPromptTemplate.defaultKindPath(kind));
        String promptTemplate = SqlPromptTemplate.load(promptPath); // 無ければ例外

        // 6) 大模型で SQL 生成（mock / live は設定ファイル次第）
        VllmClient client = new VllmClient(config.baseUrl(), config.model());
        SqlTextDraft draft = new SqlLlmGenerator(client, config.live())
                .generate(kind, description, vars, contexts,
                        selection.scoped(), selection.scopedMapping(), promptTemplate);

        // 7) 下書きを人工レビュー用に必ず残す（ゲートで拒否されても残る）
        Files.createDirectories(outDir);
        String base = baseName(descriptionMd.getFileName().toString());
        Path draftOut = outDir.resolve(base + "_draft.sql");
        Path noteOut = outDir.resolve(base + "_note.md");
        Files.writeString(draftOut, draft.sql() + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(noteOut, draft.note() + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("=== SQL 生成（prompt 雛形 + 表構造 → 大模型 → 確定的ゲート）===");
        System.out.println("  設定ファイル    : " + config.source());
        System.out.println("  LLM モード      : " + config.mode() + "（設定ファイル）");
        System.out.println("  LLM モデル      : " + config.model());
        System.out.println("  種別            : " + kind + "（Java が確定）");
        System.out.println("  prompt          : " + promptPath.toAbsolutePath() + "（SQL 雛形入り md）");
        System.out.println("  概要 md         : " + descriptionMd.toAbsolutePath());
        System.out.println("  共通 md         : " + rawContexts.size() + " 件");
        System.out.println("  表構造ライブラリ: " + library.size() + " テーブル（" + tableDir.toAbsolutePath() + "）");
        System.out.println("  LLM へ渡す表構造: " + selection.scopedSize() + " / " + library.size()
                + " テーブル → " + selection.used()
                + (selection.fallbackAll() ? "（使用表を特定できず全表へフォールバック）" : "（使用表のみ）"));
        System.out.println("  名称対応        : " + selection.scopedMapping().size()
                + " 件（使用表のみ、LLM 前に変換済み／全表は " + mapping.size() + " 件）");
        System.out.println("  翻訳後概要      : " + preview(description));
        System.out.println("  変数            : " + vars);
        System.out.println("  下書き(レビュー): " + draftOut.toAbsolutePath());
        System.out.println("  判断メモ        : " + noteOut.toAbsolutePath());
        List<String> leftover = mapping.remainingJapanese(description);
        if (!leftover.isEmpty()) {
            System.out.println("  [注意] 翻訳後に日本語名が残存: " + leftover);
        }

        // 8) 確定的ゲート（表・列の存在、注入、WHERE、変数 3 形式）
        SqlTextGate.Result result = SqlTextGate.verify(draft.sql(), kind, library);

        // 9) ゲート通過分だけ正式な .sql へ保存
        String fileName = result.primaryTable() + "_" + kind.name().toLowerCase(Locale.ROOT) + ".sql";
        Path sqlOut = outDir.resolve(fileName);
        Files.writeString(sqlOut, draft.sql() + System.lineSeparator(), StandardCharsets.UTF_8);

        Map<String, SqlVariableType> binds = result.binds();
        System.out.println("\n=== ゲート通過 → SQL 落盤 ===");
        System.out.println("  対象テーブル    : " + result.tables());
        System.out.println("  バインド変数    : " + binds + "（文字/日付/数値の 3 形式）");
        System.out.println("  書き出し先      : " + sqlOut.toAbsolutePath());
        System.out.println("\n--- 生成 SQL（下書きは別途レビュー必須）---\n" + draft.sql());
    }

    /** 種別の決定は全て確定的。モデルの判断を挟まない。 */
    private static SqlKind resolveKind(String[] args, String rawDescription) {
        Optional<String> explicit = optionValue(args, "--kind");
        if (explicit.isPresent()) return SqlKind.from(explicit.get());

        Map<String, List<String>> sections = TableSchemaParser.splitSections(rawDescription);
        List<String> target = sections.get("対象");
        if (target != null) {
            String v = TableSchemaParser.keyValues(target).get("種別");
            if (v != null && !v.isBlank()) {
                try {
                    return SqlKind.from(v);
                } catch (IllegalArgumentException ignored) {
                    // 日本語表記などは下のキーワード推定へ
                }
            }
        }
        String s = rawDescription == null ? "" : rawDescription.toUpperCase(Locale.ROOT);
        if (s.contains("DELETE") || s.contains("削除")) return SqlKind.DELETE;
        if (s.contains("UPDATE") || s.contains("更新") || s.contains("変更")) return SqlKind.UPDATE;
        return SqlKind.SELECT;
    }

    /** ログ用：翻訳済み概要を 1 行・先頭 180 字に圧縮する。 */
    private static String preview(String text) {
        String one = (text == null ? "" : text).replaceAll("\\s+", " ").strip();
        return one.length() > 180 ? one.substring(0, 180) + "…" : one;
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
        System.err.println("使い方: SqlLlmFromMd <概要.md> [--kind SELECT|UPDATE|DELETE] [--out <dir>]");
        System.err.println("        [--config <file>] [--prompt <file>] [--var KEY=VALUE ...]");
        System.err.println("        [--common-dir <dir>] [--tables-dir <dir>] [--no-common]");
        System.err.println("  LLM 情報は設定ファイルから取得（既定 config/agent.properties: llm.mode=mock|live）");
        System.err.println("  --kind      省略時は概要 md の ## 対象 - 種別、次にキーワードで確定");
        System.err.println("  --prompt    種別既定は specs/prompts/sql-<select|update|delete>.md（SQL 雛形入り）");
        System.err.println("  --tables-dir 表構造ライブラリ（既定 specs/tables、1 ファイル 1 テーブル）");
        System.err.println("  日本語名は LLM へ渡す前に英語物理名へ変換。下書き _draft.sql は必ず残る。");
    }
}
