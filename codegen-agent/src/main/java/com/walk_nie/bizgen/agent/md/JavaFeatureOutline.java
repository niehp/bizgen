package com.walk_nie.bizgen.agent.md;

import com.walk_nie.bizgen.agent.util.StrUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 機能 md に書かれた Java の契約（パッケージ／クラス／フィールド／メソッド／処理フロー）を
 * モデルに推測させず、決定論的にパースして保持する。
 * 変数は呼び出し側で解決済みの本文を渡すこと。
 */
public final class JavaFeatureOutline {

    private static final Pattern NUMBERED = Pattern.compile("^\\d+[.)]\\s+(.*)$");

    private String packageName;
    private String packageComment;
    private String className;
    private String classComment;
    private final List<FieldDef> fields = new ArrayList<FieldDef>();
    private final List<MethodDef> methods = new ArrayList<MethodDef>();
    private final List<String> flowSteps = new ArrayList<String>();

    /** フィールド定義。日本語コメントはそのまま Javadoc の根拠に使う。 */
    public static final class FieldDef {
        private final String name;
        private final String type;
        private final String comment;

        public FieldDef(String name, String type, String comment) {
            this.name = name;
            this.type = type;
            this.comment = comment;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public String comment() {
            return comment;
        }
    }

    /** メソッド定義。目的・処理内容・理由・引数・戻り値・例外を型として保持する。 */
    public static final class MethodDef {
        private final String name;
        private String purpose;
        private String behavior;
        private String reason;
        private String args;
        private String returnType;
        private String throwsClause;
        private final List<String> steps = new ArrayList<String>();

        public MethodDef(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public String purpose() {
            return purpose;
        }

        public String behavior() {
            return behavior;
        }

        public String reason() {
            return reason;
        }

        public String args() {
            return args;
        }

        public String returnType() {
            return returnType;
        }

        public String throwsClause() {
            return throwsClause;
        }

        public List<String> steps() {
            return Collections.unmodifiableList(steps);
        }

        /** 処理内容 + 番号付きステップを、LLM に渡す 1 つのフロー文にする。 */
        public String flow() {
            StringBuilder out = new StringBuilder(StrUtil.trimToEmpty(behavior));
            for (String step : steps) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(step);
            }
            return out.toString();
        }
    }

    public static JavaFeatureOutline parse(String markdown) {
        JavaFeatureOutline outline = new JavaFeatureOutline();
        String section = "";
        MethodDef current = null;

        for (String rawLine : StrUtil.normalizeNewline(markdown).split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                section = line.substring(3).trim();
                current = null;
                continue;
            }
            if (line.startsWith("### ")) {
                String name = stripMarkup(line.substring(4));
                if (section.contains("メソッド") && !name.isEmpty()) {
                    current = new MethodDef(name);
                    outline.methods.add(current);
                } else {
                    current = null;
                }
                continue;
            }

            if (current != null && section.contains("メソッド")) {
                Matcher numbered = NUMBERED.matcher(line);
                if (numbered.matches()) {
                    current.steps.add(numbered.group(1).trim());
                    continue;
                }
                if (line.startsWith("- ")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(2, colon).trim();
                        String value = StrUtil.trimToEmpty(line.substring(colon + 1));
                        if ("目的".equals(key)) {
                            current.purpose = value;
                        } else if ("処理内容".equals(key) || "処理".equals(key)) {
                            current.behavior = value;
                        } else if ("理由".equals(key)) {
                            current.reason = value;
                        } else if ("引数".equals(key)) {
                            current.args = stripMarkup(value);
                        } else if ("戻り値".equals(key)) {
                            current.returnType = stripMarkup(value);
                        } else if ("例外".equals(key)) {
                            current.throwsClause = stripMarkup(value);
                        }
                    }
                    continue;
                }
            }

            if (section.contains("フィールド") && line.startsWith("|")) {
                List<String> cells = cells(line);
                if (cells.size() >= 2 && !isTableBorder(cells)) {
                    outline.fields.add(new FieldDef(stripMarkup(cells.get(0)),
                            stripMarkup(cells.get(1)), cells.size() > 2 ? cells.get(2).trim() : ""));
                }
                continue;
            }

            if (section.contains("処理フロー")) {
                Matcher numbered = NUMBERED.matcher(line);
                if (numbered.matches()) {
                    outline.flowSteps.add(numbered.group(1).trim());
                    continue;
                }
            }

            if (line.startsWith("- ")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(2, colon).trim();
                    String value = StrUtil.trimToEmpty(line.substring(colon + 1));
                    if ("パッケージ名".equals(key)) {
                        outline.packageName = stripMarkup(value);
                    } else if ("パッケージコメント".equals(key)) {
                        outline.packageComment = value;
                    } else if ("クラス名".equals(key)) {
                        outline.className = stripMarkup(value);
                    } else if ("クラスコメント".equals(key)) {
                        outline.classComment = value;
                    }
                }
            }
        }
        return outline;
    }

    private static List<String> cells(String line) {
        List<String> result = new ArrayList<String>();
        String[] parts = line.split("\\|");
        for (String part : parts) {
            result.add(part.trim());
        }
        if (!result.isEmpty() && result.get(0).isEmpty()) {
            result.remove(0);
        }
        if (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static boolean isTableBorder(List<String> cells) {
        for (String cell : cells) {
            if (cell.startsWith("---")) {
                return true;
            }
        }
        return "フィールド名".equals(cells.get(0)) || "日本語名".equals(cells.get(0));
    }

    private static String stripMarkup(String value) {
        String result = StrUtil.trimToEmpty(value);
        if (result.startsWith("`") && result.endsWith("`") && result.length() >= 2) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    public String packageName() {
        return packageName;
    }

    public String packageComment() {
        return packageComment;
    }

    public String className() {
        return className;
    }

    public String classComment() {
        return classComment;
    }

    public List<FieldDef> fields() {
        return Collections.unmodifiableList(fields);
    }

    public List<MethodDef> methods() {
        return Collections.unmodifiableList(methods);
    }

    public List<String> flowSteps() {
        return Collections.unmodifiableList(flowSteps);
    }

    public boolean isEmpty() {
        return StrUtil.isBlank(className) && methods.isEmpty() && fields.isEmpty() && flowSteps.isEmpty();
    }

    public String methodNamesText() {
        List<String> names = new ArrayList<String>();
        for (MethodDef method : methods) {
            names.add(method.name());
        }
        return names.isEmpty() ? "（md にメソッド定義なし）" : join(names, ", ");
    }

    public String flowText() {
        List<String> all = new ArrayList<String>();
        for (String step : flowSteps) {
            all.add(step);
        }
        for (MethodDef method : methods) {
            String flow = method.flow();
            if (!flow.isEmpty()) {
                all.add(method.name() + ": " + flow);
            }
        }
        return all.isEmpty() ? "（md に処理フロー記載なし）" : join(all, "\n");
    }

    /** プロンプトへ渡す固定契約。モデルはこの内容を変更してはならない。 */
    public String toPromptText() {
        StringBuilder out = new StringBuilder();
        out.append("md 契約（変更禁止）:\n");
        append(out, "- パッケージ名", packageName);
        append(out, "- パッケージコメント", packageComment);
        append(out, "- クラス名", className);
        append(out, "- クラスコメント", classComment);
        out.append("- メソッド（この名前と順序を使用）: ").append(methodNamesText()).append('\n');
        for (MethodDef method : methods) {
            out.append("  - ").append(method.name()).append('\n');
            append(out, "    目的", method.purpose());
            append(out, "    処理内容", method.flow());
            append(out, "    理由", method.reason());
            append(out, "    引数", method.args());
            append(out, "    戻り値", method.returnType());
            append(out, "    例外", method.throwsClause());
        }
        out.append("- フィールド:\n");
        if (fields.isEmpty()) {
            out.append("  - （md にフィールド定義なし）\n");
        } else {
            for (FieldDef field : fields) {
                out.append("  - ").append(field.name()).append(" : ").append(field.type())
                        .append(" — ").append(field.comment()).append('\n');
            }
        }
        out.append("- 処理フロー:\n").append(indent(flowText())).append('\n');
        return out.toString();
    }

    private static void append(StringBuilder out, String label, String value) {
        if (!StrUtil.isBlank(value)) {
            out.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String indent(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            out.append("  ").append(line).append('\n');
        }
        return out.toString();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(value);
        }
        return out.toString();
    }
}
