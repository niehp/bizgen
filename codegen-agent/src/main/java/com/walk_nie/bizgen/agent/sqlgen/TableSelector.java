package com.walk_nie.bizgen.agent.sqlgen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「今回の SQL で使う表」だけを<b>確定的に</b>選ぶ。
 *
 * <p>なぜ必要か：表構造ライブラリには多数の表がある。全部を 14B モデルの prompt に
 * 詰め込むと、無関係な表・列を「もっともらしく」混ぜた SQL が出やすい。
 * さらに日本語名→英語名の対応表も全件渡すと誤変換の起因になる。
 * したがって Java が先に使用表を特定し、<b>その表の構造と対応だけ</b>をモデルへ渡す。
 *
 * <p>選び方（すべて文字列照合、モデルの判断は入らない）：
 * <ol>
 *   <li>概要 md の {@code ## 対象 - テーブル} （日本語名可）を最初に採用</li>
 *   <li>翻訳後概要に出てくる英語物理表名をライブラリと照合</li>
 *   <li>元の概要に出てくる日本語表名を {@link NameMapping} で照合</li>
 * </ol>
 * 1 件も特定できない場合は、モデルに根拠を与えるため全表へフォールバックし、
 * その旨をログに出す（黙って空にして.sql をでたらめにさせない）。
 */
public final class TableSelector {

    /** 選択結果。スコープ済みライブラリ／対応表と、ゲート用の全ライブラリは別物。 */
    public record Selection(List<String> used, TableSchemaLibrary scoped,
                            NameMapping scopedMapping, boolean fallbackAll) {
        public int scopedSize() { return scoped.size(); }
    }

    private TableSelector() {}

    /**
     * @param rawDescription      翻訳前の概要 md（日本語名が残っている）
     * @param translatedDescription 翻訳後概要（英語物理名）
     * @param full                表構造ライブラリ（全表）
     * @param fullMapping         全表から作った日本語名→英語名対応
     */
    public static Selection select(String rawDescription, String translatedDescription,
                                   TableSchemaLibrary full, NameMapping fullMapping) {
        TableSchemaLibrary lib = full == null ? TableSchemaLibrary.empty() : full;
        NameMapping mapping = fullMapping == null ? NameMapping.from(lib) : fullMapping;
        LinkedHashSet<String> used = new LinkedHashSet<>();

        // 1) 明示指定（## 対象 - テーブル）を最優先
        Map<String, List<String>> sections = TableSchemaParser.splitSections(
                rawDescription == null ? "" : rawDescription);
        List<String> target = sections.get("対象");
        if (target != null) {
            Map<String, String> kv = TableSchemaParser.keyValues(target);
            for (String key : List.of("テーブル", "テーブル名")) {
                String v = kv.get(key);
                if (v == null || v.isBlank()) continue;
                for (String token : v.split("[,、\\s]+")) {
                    String en = mapping.englishTable(token.strip());
                    if (en != null && lib.find(en) != null) used.add(en);
                }
            }
        }

        // 2) 翻訳後テキストに出る英語物理表名（単語境界で一致）
        String translated = translatedDescription == null ? "" : translatedDescription;
        for (TableSchemaLibrary.Entry e : lib.all()) {
            if (containsIdentifier(translated, e.tableName())) used.add(e.tableName());
        }

        // 3) 元テキストに出る日本語表名
        String raw = rawDescription == null ? "" : rawDescription;
        for (NameMapping.Rule r : mapping.rules()) {
            if (!"テーブル".equals(r.scope())) continue;
            if (raw.contains(r.japanese()) && lib.find(r.english()) != null) used.add(r.english());
        }

        boolean fallback = used.isEmpty();
        if (fallback) for (TableSchemaLibrary.Entry e : lib.all()) used.add(e.tableName());

        List<String> names = new ArrayList<>(used);
        TableSchemaLibrary scoped = lib.subset(names);
        return new Selection(names, scoped, NameMapping.from(scoped), fallback);
    }

    /** 表名が別識別子の部分文字列に当たらないよう、前後を識別子境界で確認する。 */
    private static boolean containsIdentifier(String text, String identifier) {
        if (identifier == null || identifier.isBlank()) return false;
        Pattern p = Pattern.compile("(?i)(?<![A-Za-z0-9_])"
                + Pattern.quote(identifier.toUpperCase(Locale.ROOT)) + "(?![A-Za-z0-9_])");
        return p.matcher(text.toUpperCase(Locale.ROOT)).find();
    }
}
