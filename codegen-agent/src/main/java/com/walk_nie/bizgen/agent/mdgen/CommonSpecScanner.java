package com.walk_nie.bizgen.agent.mdgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 「共通 md フォルダ」を自動走査して階層ごとの md を集める（100% 確定的）。
 *
 * <p>約束（CLI で --layer を書かなくても読まれる）：
 * <b>1 レベル = 1 フォルダ</b>。各フォルダには複数の md を置いてよく、全部読まれる。
 * <pre>
 * specs/common/                       … 既定の共通フォルダ（--common-dir / 環境変数で変更可）
 * ├── java-common/  *.md              … java共通
 * ├── project-common/ *.md            … プロジェクト共通
 * ├── feature/      *.md              … 機能（置いても自動読込）
 * └── class/        *.md              … Javaクラス（置いても自動読込）
 *     *.md（直下）                    … プロジェクト共通として読込
 * </pre>
 *
 * <p>メソッド級だけは「今回何を作るか」を決める単一真源なので位置引数で指定する。
 * common/method/ に md を置いても読まず、理由を明示して拒否する（暗黙に上書きしないため）。
 *
 * <p>走査順は「レベル順 → 相対パス順」に固定する。フォルダに入れたファイルが
 * 増えても LLM への文脈順が変わらないようにするため（再現性の確保）。
 */
public final class CommonSpecScanner {

    /** 既定の共通 md フォルダ（作業ディレクトリ基準）。 */
    public static final String DEFAULT_DIR = "specs/common";

    private CommonSpecScanner() {}

    /**
     * 共通フォルダを走査し、レベル→md パスの表を返す。
     * フォルダが無い場合は空表（旧来の --layer だけの使い方も動く）。
     */
    public static Map<SpecLevel, List<Path>> scan(Path root) throws IOException {
        Map<SpecLevel, List<Path>> found = new LinkedHashMap<>();
        if (root == null) return found;

        Path dir = root.toAbsolutePath().normalize();
        if (!Files.exists(dir)) return found;
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("共通 md フォルダがディレクトリではない: " + dir);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("_")) // _README.md 等は説明用として除外
                    .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                    .toList();
        }

        for (Path p : files) {
            Path rel = dir.relativize(p);
            SpecLevel level;
            if (rel.getNameCount() == 1) {
                // 直下に置いた md は「プロジェクト共通」として扱う（共通フォルダの既定）
                level = SpecLevel.PROJECT_COMMON;
            } else {
                String sub = rel.getName(0).toString();
                level = SpecLevel.fromCliName(sub);
                if (level == null) {
                    throw new IllegalArgumentException(
                            "共通フォルダに未知のサブフォルダ「" + sub + "」: " + p.toAbsolutePath()
                            + "（java-common / project-common / feature / class のいずれか）");
                }
                if (level == SpecLevel.METHOD) {
                    throw new IllegalArgumentException(
                            "メソッド級 md は共通フォルダではなく位置引数で指定してください: " + p.toAbsolutePath());
                }
            }
            found.computeIfAbsent(level, k -> new ArrayList<>()).add(p.toAbsolutePath().normalize());
        }
        return found;
    }

    /** 自動走査分を先に、明示指定（--layer）分を後に連結する。 */
    public static Map<SpecLevel, List<Path>> merge(Map<SpecLevel, List<Path>> auto,
                                                   Map<SpecLevel, List<Path>> explicit) {
        Map<SpecLevel, List<Path>> merged = new LinkedHashMap<>();
        for (SpecLevel level : SpecLevel.values()) {
            List<Path> all = new ArrayList<>();
            if (auto != null) all.addAll(auto.getOrDefault(level, List.of()));
            if (explicit != null) all.addAll(explicit.getOrDefault(level, List.of()));
            if (!all.isEmpty()) merged.put(level, List.copyOf(all));
        }
        return merged;
    }

    /** 走査されたファイル数（ログ用）。 */
    public static int count(Map<SpecLevel, List<Path>> found) {
        if (found == null) return 0;
        int n = 0;
        for (List<Path> paths : found.values()) n += paths.size();
        return n;
    }
}
