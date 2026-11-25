package com.walk_nie.bizgen.agent.codeintel;

import com.walk_nie.bizgen.agent.config.AgentConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 指定フォルダ配下の Java を走査し、JDT 解析結果を SQLite へ反映する（決定論処理）。 */
public final class JavaIndexer {

    /** インデックス更新の結果。LLM の推定ではなく、実際に行った操作数。 */
    public static final class IndexResult {
        public final int scanned;
        public final int parsed;
        public final int unchanged;
        public final int removed;
        public final int skipped;
        public final List<String> warnings;

        public IndexResult(int scanned, int parsed, int unchanged, int removed, int skipped,
                           List<String> warnings) {
            this.scanned = scanned;
            this.parsed = parsed;
            this.unchanged = unchanged;
            this.removed = removed;
            this.skipped = skipped;
            this.warnings = warnings;
        }

        public String describe() {
            return "走査=" + scanned + " 解析=" + parsed + " 変更なし=" + unchanged
                    + " 削除=" + removed + " スキップ=" + skipped
                    + (warnings.isEmpty() ? "" : " 警告=" + warnings.size());
        }
    }

    private final AgentConfig config;
    private final JavaIndexStore store;

    public JavaIndexer(AgentConfig config, JavaIndexStore store) {
        this.config = config;
        this.store = store;
    }

    /** 変更のあったファイルだけを再解析する。 */
    public IndexResult refresh() throws IOException, SQLException {
        return scan(false);
    }

    /** 既存索引を全て作り直す。 */
    public IndexResult rebuild() throws IOException, SQLException {
        for (String path : store.knownPaths()) {
            store.deleteFile(path);
        }
        return scan(true);
    }

    private IndexResult scan(boolean force) throws IOException, SQLException {
        Path root = config.javaIndexRoot();
        if (root == null) {
            throw new IllegalStateException("path.javaIndexRoot が設定されていません");
        }
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("索引対象フォルダが見つかりません: " + root);
        }
        Set<String> skipDirs = new HashSet<String>(Arrays.asList(
                config.javaIndexSkipDirs().toLowerCase().split("\\s*,\\s*")));
        List<Path> files = new ArrayList<Path>();
        List<String> warnings = new ArrayList<String>();
        try (Stream<Path> stream = Files.walk(root)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!name.toLowerCase().endsWith(".java")) {
                    continue;
                }
                if (containsSkippedDirectory(root, path, skipDirs)) {
                    continue;
                }
                files.add(path);
                if (files.size() >= config.javaIndexMaxFiles()) {
                    warnings.add("走査上限 " + config.javaIndexMaxFiles() + " 件に到達しました");
                    break;
                }
            }
        }
        java.util.Collections.sort(files);

        int parsedCount = 0;
        int unchangedCount = 0;
        int skippedCount = 0;
        Set<String> seen = new HashSet<String>();
        JavaIndexParser parser = new JavaIndexParser();
        for (Path path : files) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            seen.add(relative);
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (source.length() > config.javaIndexMaxFileChars()) {
                skippedCount++;
                warnings.add("ファイルが大きすぎるため索引外です: " + relative
                        + " (" + source.length() + " chars)");
                continue;
            }
            String hash = JavaIndexParser.sha256(source);
            if (!force && store.isCurrent(relative, hash)) {
                unchangedCount++;
                continue;
            }
            try {
                store.upsert(parser.parse(relative, source, Files.getLastModifiedTime(path).toMillis()));
                parsedCount++;
            } catch (RuntimeException e) {
                skippedCount++;
                warnings.add("解析に失敗しました: " + relative + " — " + e.getMessage());
            }
        }
        int removedCount = 0;
        for (String known : store.knownPaths()) {
            if (!seen.contains(known)) {
                store.deleteFile(known);
                removedCount++;
            }
        }
        return new IndexResult(files.size(), parsedCount, unchangedCount, removedCount,
                skippedCount, warnings);
    }

    private static boolean containsSkippedDirectory(Path root, Path file, Set<String> skipDirs) {
        Path relative = root.relativize(file);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (skipDirs.contains(relative.getName(i).toString().toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
