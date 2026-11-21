package com.walk_nie.bizgen.agent.mdgen;

import com.github.javaparser.StaticJavaParser;

import java.util.List;

/**
 * Javadoc の「形」を確定的に組み立てるビルダ。
 *
 * <p>役割分担（重要）：
 * <ul>
 *   <li>説明文（何をするか／なぜ）は LLM が書く。</li>
 *   <li>{@code @param} の引数名・{@code @return} の有無・タグの並びは、実際のシグネチャ（MethodSpec）
 *       から Java が組み立てる。なぜなら JavaParser は @param 名と実引数の不一致を検査せず、
 *       モデル任せにするとレビューをすり抜けるため。</li>
 * </ul>
 *
 * <p>さらに、LLM が誤ってタグ文字列や {@code * /} を埋め込んでも壊れないよう、
 * 説明文からタグ行を除去し Javadoc 終端を無害化する（確定的ゲート）。
 */
final class JavadocBuilder {

    private JavadocBuilder() {}

    /** メソッド Javadoc：説明 + 実引数名から生成した @param +（非 void なら）@return。 */
    static String methodJavadoc(MethodSpec spec, MethodBody body) {
        StringBuilder sb = new StringBuilder();
        sb.append(clean(body.methodComment(), "処理を実行する（仕様に従うため）"));

        List<String> params = spec.parameters() == null ? List.of() : spec.parameters();
        List<String> pcs = body.paramComments();
        if (pcs != null && !pcs.isEmpty()) {
            if (pcs.size() != params.size()) {
                throw new IllegalArgumentException(
                        "引数コメント数が引数数と不一致（ゲート）: 引数 " + params.size()
                                + " 件 / コメント " + pcs.size() + " 件");
            }
            for (int i = 0; i < params.size(); i++) {
                sb.append("\n@param ").append(paramName(params.get(i)))
                  .append(' ').append(clean(pcs.get(i), "引数として渡す（処理に必要なため）"));
            }
        }

        String ret = spec.returnType() == null ? "" : spec.returnType().strip();
        if (!ret.isEmpty() && !ret.equals("void")) {
            sb.append("\n@return ").append(clean(body.returnComment(),
                    ret + " を返す（呼び出し側が結果を判定できるようにするため）"));
        }
        return sb.toString();
    }

    /** クラス Javadoc：説明のみ（タグは付けない——短く保ちレビューしやすくするため）。 */
    static String classJavadoc(MethodBody body) {
        return clean(body.classComment(), "業務処理を提供する（責務を集約して変更箇所を限定するため）");
    }

    /** パッケージ Javadoc：package-info.java に出す説明。 */
    static String packageJavadoc(MethodBody body) {
        return clean(body.packageComment(), "業務ロジックをまとめる（依存方向を保つため）");
    }

    /** 説明文の無害化：タグ行を落とし、Javadoc 終端 {@code * /} を無効化する。 */
    private static String clean(String text, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        StringBuilder out = new StringBuilder();
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("@")) continue;          // モデルが書いたタグは採用しない
            if (line.startsWith("*/")) line = line.replace("*/", "* /");
            if (!line.isEmpty()) {
                if (!out.isEmpty()) out.append('\n');
                out.append(line);
            }
        }
        return out.isEmpty() ? fallback : out.toString();
    }

    /** "List&lt;String&gt; payments" → "payments"（実シグネチャから引数名を取り出す）。 */
    private static String paramName(String declaration) {
        try {
            return StaticJavaParser.parseParameter(declaration).getNameAsString();
        } catch (Exception e) {
            String s = declaration.strip();
            int sp = s.lastIndexOf(' ');
            return sp >= 0 ? s.substring(sp + 1).strip() : s;
        }
    }
}
