package com.walk_nie.bizgen.agent.spec;

import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * モデルが要件を理解するための参考資料カタログ。
 *
 * 次の 4 ディレクトリ配下の .md を決定的に読み込み、変数展開して prompt へ注入する。
 *   spec/api/*.md       利用可能な API メソッドシグネチャ
 *   spec/dict/*.md      术语（日本語 -> 英語）
 *   spec/template/*.md  コード / 出力テンプレート
 *   spec/example/*.md   機能インスタンス（既に完成した機能の例）
 *
 * 読み込み順はパス昇順で固定。文字数上限を超える場合は暗黙に切り捨てず例外とする
 * （14B のコンテキスト保護と、情報の黙った消失を防ぐため）。
 */
public final class SpecCatalog {

    /** 資料の種別。ディレクトリ名と 1 対 1 で対応する。 */
    public enum Kind {
        API("api", "API メソッドシグネチャ"),
        DICT("dict", "术语（日本語 -> 英語）"),
        TEMPLATE("template", "テンプレート"),
        EXAMPLE("example", "機能インスタンス");

        private final String dir;
        private final String label;

        Kind(String dir, String label) {
            this.dir = dir;
            this.label = label;
        }

        public String dir() {
            return dir;
        }

        public String label() {
            return label;
        }

        public static Kind fromDir(String value) {
            for (Kind kind : values()) {
                if (kind.dir.equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("不明な spec 種別: " + value);
        }
    }

    /** 1 つの資料ファイル。 */
    public static final class SpecDoc {
        private final Kind kind;
        private final String name;
        private final String relativePath;
        private final String body;

        public SpecDoc(Kind kind, String name, String relativePath, String body) {
            this.kind = kind;
            this.name = name;
            this.relativePath = relativePath;
            this.body = body;
        }

        public Kind kind() {
            return kind;
        }

        public String name() {
            return name;
        }

        public String relativePath() {
            return relativePath;
        }

        public String body() {
            return body;
        }
    }

    private final List<SpecDoc> docs;

    private SpecCatalog(List<SpecDoc> docs) {
        this.docs = Collections.unmodifiableList(new ArrayList<SpecDoc>(docs));
    }

    /**
     * 4 ディレクトリ配下の .md を読み込む。
     *
     * @param root      spec ルート（未設定 / 存在しない場合は空カタログ）
     * @param variables 本文内の {{変数}} を展開する変数表（null 可）
     * @param maxChars  読み込む本文の合計文字数上限（0 以下で無制限）
     */
    public static SpecCatalog load(Path root, VariableTable variables, int maxChars) throws IOException {
        List<SpecDoc> docs = new ArrayList<SpecDoc>();
        if (root == null || !Files.isDirectory(root)) {
            return new SpecCatalog(docs);
        }
        int total = 0;
        for (Kind kind : Kind.values()) {
            Path dir = root.resolve(kind.dir());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (Path path : FileUtil.listFiles(dir)) {
                String fileName = path.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
                    continue;
                }
                String raw = FileUtil.read(path);
                String body = variables == null ? raw : variables.resolveText(raw);
                total += body.length();
                if (maxChars > 0 && total > maxChars) {
                    throw new IllegalStateException(
                            "spec 資料が文字数上限 " + maxChars + " を超えました。"
                                    + "不要な資料を削除するか spec.maxChars を増やしてください。"
                                    + "（最後に読み込んだファイル: " + kind.dir() + "/" + fileName + "）");
                }
                docs.add(new SpecDoc(kind, FileUtil.baseName(path),
                        kind.dir() + "/" + fileName, body));
            }
        }
        return new SpecCatalog(docs);
    }

    public List<SpecDoc> docs() {
        return docs;
    }

    public boolean isEmpty() {
        return docs.isEmpty();
    }

    public int size() {
        return docs.size();
    }

    /** 指定種別の資料だけを prompt 用に整形する。 */
    public String text(Kind kind) {
        StringBuilder out = new StringBuilder();
        for (SpecDoc doc : docs) {
            if (doc.kind() != kind) {
                continue;
            }
            out.append("----- [").append(kind.label()).append("] ")
                    .append(doc.relativePath()).append(" -----\n")
                    .append(doc.body());
            if (!doc.body().endsWith("\n")) {
                out.append('\n');
            }
            out.append('\n');
        }
        if (out.length() == 0) {
            out.append("（該当資料なし）\n");
        }
        return out.toString();
    }

    public String apiText() {
        return text(Kind.API);
    }

    public String dictText() {
        return text(Kind.DICT);
    }

    public String templateText() {
        return text(Kind.TEMPLATE);
    }

    public String exampleText() {
        return text(Kind.EXAMPLE);
    }

    /** 4 種別すべてを 1 つの prompt 段落にまとめる。 */
    public String allText() {
        StringBuilder out = new StringBuilder();
        for (Kind kind : Kind.values()) {
            out.append("===== spec/").append(kind.dir()).append(" : ")
                    .append(kind.label()).append(" =====\n")
                    .append(text(kind)).append('\n');
        }
        return out.toString();
    }

    /** CLI 表示用の一覧。 */
    public String listText() {
        StringBuilder out = new StringBuilder();
        if (docs.isEmpty()) {
            out.append("（spec 資料なし）\n");
            return out.toString();
        }
        String current = "";
        for (SpecDoc doc : docs) {
            if (!current.equals(doc.kind().dir())) {
                current = doc.kind().dir();
                out.append("[").append(current).append("] ").append(doc.kind().label()).append('\n');
            }
            out.append("  ").append(doc.name()).append("  (").append(doc.body().length()).append(" 文字)\n");
        }
        out.append("合計: ").append(docs.size()).append(" ファイル\n");
        return out.toString();
    }

    /** 未使用の参考文献を明示するためのプレースホルダ除外ヘルパー。 */
    public static String safe(String value) {
        return StrUtil.trimToEmpty(value);
    }
}
