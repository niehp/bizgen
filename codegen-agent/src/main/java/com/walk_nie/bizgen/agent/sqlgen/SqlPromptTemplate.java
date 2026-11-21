package com.walk_nie.bizgen.agent.sqlgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 下書き生成の prompt テンプレート（md ファイルで管理する）。
 *
 * <p>プレースホルダは大文字のみ。Java 側が確定的に差し込む：
 * {@code {{DESCRIPTION}}} / {@code {{VARS}}} / {@code {{CONTEXTS}}} / {@code {{TABLES}}}。
 * 小文字の {@code {{key}}} は md 変数の説明文なので置換しない
 * （TemplateResolver は使わない——説明文中の変数まで置換してしまうため）。
 *
 * <p>ゲート：
 * <ul>
 *   <li>必須プレースホルダが 1 つでも欠けていたら拒否（黙って空欄になるのを防ぐ）</li>
 *   <li>未知の大文字プレースホルダ（タイポ等）は拒否して列挙</li>
 *   <li>HTML コメント（{@code <!-- ... -->}、複数行可）は人間向けメモなので送信前に除去</li>
 * </ul>
 */
public final class SqlPromptTemplate {

    public static final String DEFAULT_PATH = "specs/prompts/sql-draft.md";
    /** 種別別テンプレートを置くフォルダ（1 種別 1 ファイル）。 */
    public static final String KIND_PROMPT_DIR = "specs/prompts";

    /**
     * 種別に対応する既定テンプレートのパス（100% 確定的）。
     * SELECT → specs/prompts/sql-select.md（多表結合対応）、
     * UPDATE → sql-update.md、DELETE → sql-delete.md。
     */
    public static Path defaultKindPath(SqlKind kind) {
        if (kind == null) throw new IllegalArgumentException("SQL 種別が null（prompt を選べない）");
        return Path.of(KIND_PROMPT_DIR, "sql-" + kind.name().toLowerCase(Locale.ROOT) + ".md");
    }

    public static final String DESCRIPTION = "{{DESCRIPTION}}";
    public static final String VARS = "{{VARS}}";
    public static final String CONTEXTS = "{{CONTEXTS}}";
    public static final String TABLES = "{{TABLES}}";

    private static final List<String> REQUIRED =
            List.of(DESCRIPTION, VARS, CONTEXTS, TABLES);

    /** 大文字プレースホルダ（説明文中の小文字 {{key}} は対象外）。 */
    private static final Pattern UPPER = Pattern.compile("\\{\\{([A-Z][A-Z0-9_]*)}}");

    private SqlPromptTemplate() {}

    /**
     * md テンプレートを読み込む。コメント除去とプレースホルダ検証まで行う。
     * 明示指定したファイルが無い場合は例外（黙ってフォールバックしない）。
     */
    public static String load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("prompt テンプレートが無い: "
                    + (file == null ? "null" : file.toAbsolutePath()));
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        String body = stripComments(text);
        require(!body.isBlank(), "prompt テンプレートが空: " + file.toAbsolutePath());
        validate(body, file);
        return body;
    }

    /** テンプレート本文から HTML コメントを除去する（複数行・同一行内の両対応）。 */
    static String stripComments(String text) {
        String out = Pattern.compile("<!--[\\s\\S]*?-->").matcher(text).replaceAll("");
        int idx = out.indexOf("<!--"); // 閉じ忘れは以降をコメント扱い
        if (idx >= 0) out = out.substring(0, idx);
        return out;
    }

    /** 必須プレースホルダの存在と未知プレースホルダの有無を検証する。 */
    static void validate(String template, Path file) {
        List<String> missing = REQUIRED.stream().filter(p -> !template.contains(p)).toList();
        require(missing.isEmpty(), "prompt テンプレートに必須プレースホルダが無い: " + missing
                + " @" + (file == null ? DEFAULT_PATH : file.toAbsolutePath()));

        List<String> unknown = new ArrayList<>();
        Matcher m = UPPER.matcher(template);
        java.util.Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String name = "{{" + m.group(1) + "}}";
            if (!REQUIRED.contains(name) && seen.add(name)) unknown.add(name);
        }
        require(unknown.isEmpty(), "prompt テンプレートに未知のプレースホルダ: " + unknown
                + "（許可: " + REQUIRED + "）");
    }

    /** プレースホルダを確定的に差し込み、残骸がないか最終検証する。 */
    public static String fill(String template, String description, String varsText,
                              String contextsText, String tablesText) {
        validate(template, null);
        String out = template
                .replace(DESCRIPTION, nullToEmpty(description))
                .replace(VARS, nullToEmpty(varsText))
                .replace(CONTEXTS, nullToEmpty(contextsText))
                .replace(TABLES, nullToEmpty(tablesText));
        Matcher m = UPPER.matcher(out);
        List<String> leftover = new ArrayList<>();
        while (m.find()) leftover.add(m.group(0));
        require(leftover.isEmpty(), "prompt に未置換のプレースホルダが残った: " + leftover);
        return out.strip();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException("prompt テンプレート拒否: " + message);
    }
}
