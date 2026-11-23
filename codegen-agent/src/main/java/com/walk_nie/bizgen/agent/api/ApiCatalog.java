package com.walk_nie.bizgen.agent.api;

import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 既存プロジェクトの工具类・メソッドシグネチャを保持する API 库。
 * md ファイル 1 つを 1 クラスとし、次の表形式でメソッドを列挙する。
 *
 *   # StringUtils
 *   | シグネチャ | 説明 |
 *   | --- | --- |
 *   | `public static boolean isBlank(String value)` | 文字列が空か判定する |
 *
 * 検索は決定論的なキーワード一致のみ。モデルに API を「想像」させないための上下文として使う。
 */
public final class ApiCatalog {

    private static final Pattern CJK = Pattern.compile("[\\u3040-\\u30ff\\u4e00-\\u9fff]{2,}");
    private static final Pattern ASCII = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}");

    private final List<ApiEntry> entries;

    private ApiCatalog(List<ApiEntry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<ApiEntry>(entries));
    }

    public static ApiCatalog load(Path root) throws IOException {
        List<ApiEntry> result = new ArrayList<ApiEntry>();
        if (root == null || !Files.isDirectory(root)) {
            return new ApiCatalog(result);
        }
        for (Path path : FileUtil.listFiles(root)) {
            if (!path.getFileName().toString().toLowerCase().endsWith(".md")) {
                continue;
            }
            String className = FileUtil.baseName(path);
            String source = path.getFileName().toString();
            for (String rawLine : FileUtil.read(path).split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("# ")) {
                    className = stripTicks(line.substring(2).trim());
                    continue;
                }
                if (!line.startsWith("|")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                if (cells.length < 3) {
                    continue;
                }
                String signature = stripTicks(cells[1].trim());
                String description = cells[2].trim();
                if (signature.isEmpty() || isHeaderOrSeparator(signature, description)) {
                    continue;
                }
                result.add(new ApiEntry(className, signature, description, source));
            }
        }
        return new ApiCatalog(result);
    }

    public List<ApiEntry> entries() {
        return entries;
    }

    /** クエリ（機能 md + 术语）から関連 API をスコア順に返す。 */
    public List<ApiEntry> match(String query, int topN) {
        List<String> tokens = tokenize(query);
        List<Scored> scored = new ArrayList<Scored>();
        for (ApiEntry entry : entries) {
            int score = 0;
            String description = entry.description().toLowerCase(Locale.ROOT);
            String signature = entry.signature().toLowerCase(Locale.ROOT);
            String className = entry.className().toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (description.contains(lower)) {
                    score += 3;
                }
                if (signature.contains(lower)) {
                    score += 2;
                }
                if (className.contains(lower)) {
                    score += 2;
                }
            }
            if (score > 0) {
                scored.add(new Scored(entry, score));
            }
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                if (a.score != b.score) {
                    return b.score - a.score;
                }
                int byClass = a.entry.className().compareTo(b.entry.className());
                return byClass != 0 ? byClass : a.entry.signature().compareTo(b.entry.signature());
            }
        });
        List<ApiEntry> result = new ArrayList<ApiEntry>();
        for (Scored item : scored) {
            if (result.size() >= topN) {
                break;
            }
            result.add(item.entry);
        }
        return result;
    }

    /** prompt に注入する形に整形する。 */
    public String promptText(List<ApiEntry> matched) {
        StringBuilder out = new StringBuilder();
        String currentClass = "";
        for (ApiEntry entry : matched) {
            if (!currentClass.equals(entry.className())) {
                currentClass = entry.className();
                out.append("### ").append(currentClass).append('\n');
            }
            out.append("- `").append(entry.signature()).append("` — ")
                    .append(entry.description()).append('\n');
        }
        if (out.length() == 0) {
            out.append("（該当する既存 API なし。勝手な API を生成しないこと。）\n");
        }
        return out.toString();
    }

    private static List<String> tokenize(String text) {
        List<String> result = new ArrayList<String>();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<String>();
        Matcher cjk = CJK.matcher(text);
        while (cjk.find()) {
            seen.add(cjk.group());
        }
        Matcher ascii = ASCII.matcher(text);
        while (ascii.find()) {
            seen.add(ascii.group());
        }
        result.addAll(seen);
        return result;
    }

    private static boolean isHeaderOrSeparator(String signature, String description) {
        String s = signature.toLowerCase(Locale.ROOT);
        String d = description.toLowerCase(Locale.ROOT);
        return s.contains("シグネチャ") || s.contains("signature")
                || s.matches("-+:?") || d.matches("-+:?");
    }

    private static String stripTicks(String value) {
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("`") && result.endsWith("`")) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    /** 1 つの API エントリ。 */
    public static final class ApiEntry {
        private final String className;
        private final String signature;
        private final String description;
        private final String source;

        public ApiEntry(String className, String signature, String description, String source) {
            this.className = className;
            this.signature = signature;
            this.description = description;
            this.source = source;
        }

        public String className() {
            return className;
        }

        public String signature() {
            return signature;
        }

        public String description() {
            return description;
        }

        public String source() {
            return source;
        }

        @Override
        public String toString() {
            return className + "." + signature;
        }
    }

    private static final class Scored {
        final ApiEntry entry;
        final int score;

        Scored(ApiEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
