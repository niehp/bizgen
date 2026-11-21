package com.walk_nie.bizgen.agent.sqlgen;

import com.walk_nie.bizgen.agent.mdgen.AgentConfig;
import com.walk_nie.bizgen.agent.mdgen.CommonSpecScanner;
import com.walk_nie.bizgen.agent.mdgen.SpecLevel;
import com.walk_nie.bizgen.agent.mdgen.TemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「表構造 md + SQL 要求 md」から SQL を生成してファイルへ保存する CLI。
 *
 * 使い方：
 *   SqlgenFromMd &lt;request.md&gt; [--schema &lt;table.md&gt;] [--out &lt;dir&gt;]
 *                [--var KEY=VALUE ...] [--common-dir &lt;dir&gt;] [--no-common]
 *
 * <p>位置引数は SQL 要求 md（種別・テーブル・選択列／設定値・条件）。
 * 表構造 md は既定で共通フォルダ配下の *.md から自動検出し、
 * {"テーブル": ...} を持つものを採用する。--schema で明示指定もできる。
 *
 * <p>変数は共通 md の {{key=value}} 定義を集め、--var で上書きしてから
 * 両 md へ適用する（LLM へ渡す前に解決する既存原則と同じ）。
 * 条件／設定値の {{key}} は解決前に &#47;*key*&#47;'{{key}}' へ正規化され、
 * SQL では &#47;*key*&#47;'値' のバインド変数になる。
 * SQL そのものは LLM を使わず Java が確定的に組み立てる。
 */
public final class SqlgenFromMd {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        Path requestMd = Path.of(args[0]);
        boolean noCommon = contains(args, "--no-common");
        Path outDir = optionValue(args, "--out").map(Path::of).orElse(Path.of("generated/sql"));
        AgentConfig config = AgentConfig.load(optionValue(args, "--config").map(Path::of).orElse(null));
        Path commonDir = optionValue(args, "--common-dir").map(Path::of).orElse(config.commonDir());
        Path tableDir = optionValue(args, "--tables-dir").map(Path::of).orElse(config.tableDir());

        if (!Files.isRegularFile(requestMd)) {
            throw new IllegalArgumentException("SQL 要求 md が無い: " + requestMd.toAbsolutePath());
        }

        // 1) 変数表：共通 md の定義 → --var で上書き
        Map<String, String> vars = collectVars(args);
        if (!noCommon && Files.isDirectory(commonDir)) {
            Map<SpecLevel, List<Path>> auto = CommonSpecScanner.scan(commonDir);
            for (List<Path> paths : auto.values()) {
                for (Path p : paths) {
                    String raw = Files.readString(p, StandardCharsets.UTF_8);
                    for (Map.Entry<String, String> e : TemplateResolver.definitions(raw).entrySet()) {
                        vars.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
            }
        }
        TemplateResolver resolver = new TemplateResolver(vars);

        // 2) まず要求 md を素のまま解析し、対象テーブル・種別を確定させる
        //    （どの表構造を読むか＝ルーティングは 100% Java が決め、モデルは介在しない）
        String rawRequest = read(requestMd);
        SqlRequest probe = SqlRequestParser.parse(rawRequest);

        // 3) 表構造：--schema 明示 ＞ 表構造ライブラリ（表名で 1 ファイル選択）＞ 共通／要求 md
        Path schemaMd = optionValue(args, "--schema").map(Path::of).orElse(null);
        String schemaText;
        String schemaSource;
        if (schemaMd != null) {
            require(Files.isRegularFile(schemaMd), "表構造 md が無い: " + schemaMd.toAbsolutePath());
            schemaText = resolver.resolve(read(schemaMd));
            schemaSource = "--schema 明示";
        } else {
            TableSchemaLibrary lib = noCommon ? TableSchemaLibrary.load(null)
                    : TableSchemaLibrary.load(tableDir);
            TableSchemaLibrary.Entry entry = lib.find(probe.tableName());
            if (entry != null) {
                schemaMd = entry.path();
                schemaText = resolver.resolve(entry.raw());
                schemaSource = "表構造ライブラリ（1 ファイル 1 テーブル）";
            } else {
                schemaMd = findSchemaMd(commonDir, noCommon, requestMd);
                require(Files.isRegularFile(schemaMd), "表構造 md が無い: " + schemaMd.toAbsolutePath()
                        + "（--schema で指定するか specs/tables へ 1 テーブル 1 ファイルで置くこと）");
                schemaText = resolver.resolve(read(schemaMd));
                schemaSource = "共通／要求 md";
            }
        }
        TableSchema schema = TableSchemaParser.parse(schemaText);

        // 4) 列型が揃ったので {{key}} を「列型に応じた 3 形式」へ正規化 → 変数解決 → 本解析
        //    （解決後に正規化すると変数名が失われるため、必ずこの順）
        String requestText = resolver.resolve(SqlVariables.normalizeRequest(rawRequest, schema));
        SqlRequest req = SqlRequestParser.parse(requestText);
        require(req.tableName().equalsIgnoreCase(probe.tableName()),
                "正規化の前後でテーブル名が変化した（仕様 md を確認すること）");

        // 4) 確定的レンダリング（列名・表名・注入ゲート）
        String sql = new SqlRenderer().render(req, schema);

        // 5) 保存（--out が無い既定は generated/sql）
        Files.createDirectories(outDir);
        String fileName = req.tableName() + "_" + req.kind().name().toLowerCase() + ".sql";
        Path outFile = outDir.resolve(fileName);
        Files.writeString(outFile, sql + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("=== SQL 生成（確定的・LLM 不使用）===");
        System.out.println("  表構造 md   : " + schemaMd.toAbsolutePath() + "（" + schema.columns().size() + " 列 / " + schemaSource + "）");
        System.out.println("  SQL 要求 md : " + requestMd.toAbsolutePath());
        System.out.println("  変数        : " + vars);
        System.out.println("  種別/テーブル: " + req.kind() + " / " + req.tableName());
        System.out.println("  条件        : " + req.conditions());
        System.out.println("  バインド変数: " + SqlVariables.bindNames(req.conditions()));
        System.out.println("  書き出し先  : " + outFile.toAbsolutePath());
        System.out.println("\n--- 生成 SQL ---\n" + sql);
    }

    /** 共通フォルダから「テーブル名」定義を持つ md を 1 つ拾う（見つからなければ要求 md と同じファイル）。 */
    private static Path findSchemaMd(Path commonDir, boolean noCommon, Path requestMd) {
        if (!noCommon && Files.isDirectory(commonDir)) {
            try {
                Map<SpecLevel, List<Path>> auto = CommonSpecScanner.scan(commonDir);
                for (List<Path> paths : auto.values()) {
                    for (Path p : paths) {
                        String raw = read(p);
                        if (raw.contains("## テーブル") && raw.contains("テーブル名:")) return p;
                    }
                }
            } catch (IOException e) {
                // 走査失敗は要求 md フォールバック（後段の必須ゲートが既に止める）
            }
        }
        return requestMd; // 単一ファイルに表構造と要求を両方書いた場合
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
        System.err.println("使い方: SqlgenFromMd <request.md> [--schema <table.md>] [--tables-dir <dir>] [--out <dir>]");
        System.err.println("        [--var KEY=VALUE ...] [--config <file>] [--common-dir <dir>] [--no-common]");
        System.err.println("  --schema     表構造 md（省略時は表構造ライブラリから表名で選択）");
        System.err.println("  --tables-dir 表構造ライブラリ（既定 specs/tables、1 ファイル 1 テーブル）");
        System.err.println("  --out        保存先（既定 generated/sql）。ファイル名: <table>_<kind>.sql");
        System.err.println("  --var        {{key}} の上書き（共通 md の {{key=value}} 定義が既定値）");
    }
}
