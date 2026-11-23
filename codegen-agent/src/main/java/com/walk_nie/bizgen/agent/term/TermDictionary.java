package com.walk_nie.bizgen.agent.term;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日本語 -> 英語 の术语库。ディレクトリ内の txt / md をすべて読み込む。
 * 1 行形式: 日本語 -> 英語
 *
 * マッチングは完全決定論的（文字列包含 + 長い語優先）で、LLM には任せない。
 */
public final class TermDictionary {

    private static final Pattern ARROW = Pattern.compile("^(.+?)\\s*->\\s*(.+)$");
    private static final Pattern CJK = Pattern.compile("[\\u3040-\\u30ff\\u4e00-\\u9fff]{2,}");

    private final List<TermEntry> entries;

    private TermDictionary(List<TermEntry> entries) {
        List<TermEntry> sorted = new ArrayList<TermEntry>(entries);
        Collections.sort(sorted, new Comparator<TermEntry>() {
            @Override
            public int compare(TermEntry a, TermEntry b) {
                int byLength = b.japanese().length() - a.japanese().length();
                if (byLength != 0) {
                    return byLength;
                }
                return a.japanese().compareTo(b.japanese());
            }
        });
        this.entries = Collections.unmodifiableList(sorted);
    }

    /** 术语库ディレクトリを読み込む。ファイルが無ければ空の辞書を返す。 */
    public static TermDictionary load(Path root) throws IOException {
        List<TermEntry> result = new ArrayList<TermEntry>();
        if (root == null || !Files.isDirectory(root)) {
            return new TermDictionary(result);
        }
        for (Path path : FileUtil.listFiles(root)) {
            String name = path.getFileName().toString().toLowerCase();
            if (!name.endsWith(".txt") && !name.endsWith(".md")) {
                continue;
            }
            String source = path.getFileName().toString();
            for (String rawLine : FileUtil.read(path).split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }
                Matcher matcher = ARROW.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String jp = matcher.group(1).trim();
                String en = matcher.group(2).trim();
                if (!jp.isEmpty() && !en.isEmpty()) {
                    result.add(new TermEntry(jp, en, source));
                }
            }
        }
        return new TermDictionary(result);
    }

    public List<TermEntry> entries() {
        return entries;
    }

    /** テキストに出現する术语を長い語優先で返す（重複なし）。 */
    public List<TermEntry> match(String text) {
        List<TermEntry> result = new ArrayList<TermEntry>();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (TermEntry entry : entries) {
            if (text.contains(entry.japanese()) && seen.add(entry.japanese())) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 機能 md から业务词汇を取り出す。CJK の連続語のみ（決定論的）。 */
    public List<String> extractBusinessWords(String text) {
        List<String> result = new ArrayList<String>();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<String>();
        Matcher matcher = CJK.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            if (seen.add(word)) {
                result.add(word);
            }
        }
        return result;
    }

    /** テキスト内の日本語术语を英語に置換する（長い語優先）。 */
    public String translate(String text) {
        String result = StrUtil.trimToEmpty(text);
        for (TermEntry entry : entries) {
            result = result.replace(entry.japanese(), entry.english());
        }
        return result;
    }

    /** prompt に注入する表形式のテキスト。 */
    public String promptText(List<TermEntry> matched) {
        StringBuilder out = new StringBuilder();
        out.append("| 日本語 | 英語 |\n| --- | --- |\n");
        for (TermEntry entry : matched) {
            out.append("| ").append(entry.japanese()).append(" | ")
                    .append(entry.english()).append(" |\n");
        }
        return out.toString();
    }

    /** 1 つの术语エントリ。 */
    public static final class TermEntry {
        private final String japanese;
        private final String english;
        private final String source;

        public TermEntry(String japanese, String english, String source) {
            this.japanese = japanese;
            this.english = english;
            this.source = source;
        }

        public String japanese() {
            return japanese;
        }

        public String english() {
            return english;
        }

        public String source() {
            return source;
        }

        @Override
        public String toString() {
            return japanese + " -> " + english;
        }
    }
}
