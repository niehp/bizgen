package com.walk_nie.bizgen.agent.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ファイル入出力ユーティリティ。文字コードを UTF-8 に固定し、環境差による揺れを排除する。
 */
public final class FileUtil {

    private FileUtil() {
    }

    public static Path resolve(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }

    public static String read(Path path) throws IOException {
        return StrUtil.normalizeNewline(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    public static String readIfExists(Path path) throws IOException {
        return Files.exists(path) ? read(path) : "";
    }

    /** 親ディレクトリが無ければ作成してから書き出す。 */
    public static void write(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, StrUtil.normalizeNewline(content).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    /** 指定ディレクトリ直下の .md ファイルをファイル名順に返す。 */
    public static List<Path> listMarkdown(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<Path> result = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .forEach(result::add);
        }
        return result;
    }

    /** 指定ディレクトリ直下の通常ファイルをファイル名順に返す。 */
    public static List<Path> listFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<Path> result = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).sorted().forEach(result::add);
        }
        return result;
    }

    /** 拡張子を除いたファイル名を返す。 */
    public static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** パッケージ名をディレクトリ相対パスに変換する。 */
    public static Path packageToPath(String packageName, String className) {
        return Paths.get(packageName.replace('.', '/'), className + ".java");
    }
}
