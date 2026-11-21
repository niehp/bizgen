package com.walk_nie.bizgen.agent.sqlgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表構造 md ライブラリ（1 ファイル 1 テーブル）。
 *
 * <p>既定ディレクトリは {@code specs/tables/}。ファイル名はテーブル名とし、
 * {@code _} 始まりのテンプレート／メモは読まない。読み込み順はパス順で安定。
 *
 * <p>ゲート：
 * <ul>
 *   <li>同一テーブル名の重複は拒否（どの定義が正しいか曖昧にしない）</li>
 *   <li>テンプレートのプレースホルダ &lt;table_name&gt; が残っていたら拒否</li>
 *   <li>テーブル名・カラム名は識別子のみ（SQL 断片の混入を防ぐ）</li>
 * </ul>
 *
 * <p>モデルに表構造を推させないため、SQL 下書き生成ではここから該当テーブルを
 * コンテキストとして渡す。
 */
public final class TableSchemaLibrary {

    public static final String DEFAULT_DIR = "specs/tables";

    /** 1 テーブル分の md。 */
    public record Entry(Path path, String raw, TableSchema schema) {
        public String tableName() { return schema.tableName(); }
    }

    private final Map<String, Entry> byName;

    private TableSchemaLibrary(Map<String, Entry> byName) {
        this.byName = byName;
    }

    /** 空ライブラリ（表構造が無い場合のフォールバック用）。 */
    public static TableSchemaLibrary empty() { return new TableSchemaLibrary(Map.of()); }

    /** ディレクトリが無ければ空ライブラリ（必須は呼び出し側のゲートで決める）。 */
    public static TableSchemaLibrary load(Path dir) throws IOException {
        Map<String, Entry> byName = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) return new TableSchemaLibrary(Map.of());

        List<Path> files;
        try (var stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("_"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        for (Path p : files) {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') raw = raw.substring(1);
            TableSchema schema = TableSchemaParser.parse(raw);
            requireIdentifier(schema.tableName(), "テーブル名", p);
            for (String col : schema.columnNames()) requireIdentifier(col, "カラム名", p);

            String key = schema.tableName().toLowerCase(Locale.ROOT);
            Entry prev = byName.putIfAbsent(key, new Entry(p, raw, schema));
            if (prev != null) {
                throw new IllegalArgumentException("表構造ライブラリ拒否: テーブル「"
                        + schema.tableName() + "」が重複: " + prev.path() + " と " + p);
            }
        }
        return new TableSchemaLibrary(Map.copyOf(byName));
    }

    public Entry find(String tableName) {
        if (tableName == null) return null;
        return byName.get(tableName.strip().toLowerCase(Locale.ROOT));
    }

    public List<Entry> all() {
        return byName.values().stream()
                .sorted(Comparator.comparing(e -> e.path().toString()))
                .toList();
    }

    public int size() { return byName.size(); }

    /**
     * 指定された表名だけを残した<b>部分ライブラリ</b>を作る（存在しない名前は無視）。
     * LLM へ渡す表構造を今回の SQL で使う表だけに絞るために使う。
     * ゲート側は元の全ライブラリを使ったままであること（モデルが増やした表も検査する）。
     */
    public TableSchemaLibrary subset(Collection<String> tableNames) {
        Map<String, Entry> picked = new LinkedHashMap<>();
        if (tableNames != null) {
            for (String name : tableNames) {
                Entry e = find(name);
                if (e != null) picked.put(name.strip().toLowerCase(Locale.ROOT), e);
            }
        }
        return new TableSchemaLibrary(Map.copyOf(picked));
    }

    /** LLM prompt 用：保持しているテーブルだけをパス順に並べたコンテキスト。 */
    public String promptContext() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : all()) {
            sb.append("## 【表構造: ").append(e.tableName()).append("】").append(e.path()).append('\n')
              .append(e.raw().strip()).append("\n\n");
        }
        return sb.toString();
    }

    private static void requireIdentifier(String value, String label, Path path) {
        boolean ok = value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
        require(ok, label + "「" + value + "」が不正（" + path + "）。"
                + "テンプレートの <table_name> を書き換えたか確認すること");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException("表構造ライブラリ拒否: " + message);
    }
}
