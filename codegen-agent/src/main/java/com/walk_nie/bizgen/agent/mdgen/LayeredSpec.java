package com.walk_nie.bizgen.agent.mdgen;

import java.util.List;
import java.util.Map;

/**
 * 読み込んだ階層 md の詰め合わせ（不変）。
 *
 * @param method    メソッド級 md を解析した MethodSpec（署名の単一真源）
 * @param contexts  上位 4 級の文脈（変数解決済み、粗い順）
 * @param vars      置換に使った変数表（審査用）
 */
public record LayeredSpec(
        MethodSpec method,
        List<LayerDoc> contexts,
        Map<String, String> vars
) {
    /** 1 つの階層ドキュメント。 */
    public record LayerDoc(SpecLevel level, String source, String content) {}
}
