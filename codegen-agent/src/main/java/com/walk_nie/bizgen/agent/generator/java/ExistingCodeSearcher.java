package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.md.FeatureSpec;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;

import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 既存 Java プロジェクトから、機能 md に関連するコードを決定論的に検索する。
 * 埋め込みモデルや外部 API は使わず、キーワード一致と固定スコアのみで順序を決めるため、
 * 同じ入力・同じ設定なら常に同じ結果になる。14B モデルのコンテキスト保護のため上限を厳守する。
 */
public final class ExistingCodeSearcher {

    private static final Set<String> SKIP_VARIABLE_KEYS = buildSkipKeys();
    private static final Set<String> GENERIC_TERMS = new HashSet<String>(java.util.Arrays.asList(
            "フィールド名", "日本語コメント", "日本語名", "英語物理名", "型", "備考", "目的", "処理内容",
            "理由", "引数", "戻り値", "例外", "パッケージ名", "クラス名", "クラスコメント",
            "パッケージコメント", "概要", "主キー", "メソッド名", "変更種別", "使用表"));

    private static Set<String> buildSkipKeys() {
        Set<String> normalized = new HashSet<String>();
        for (String key : new String[]{
                "applicationId", "packageComment", "classComment", "classTitle", "classKind"}) {
            normalized.add(VariableTable.normalize(key));
        }
        return normalized;
    }

    private ExistingCodeSearcher() {
    }

    public static SimilarCodeResult search(AgentConfig config, ResolvedFeature resolved,
                                           SemanticMap semanticMap, String targetFileRelative) {
        Map<String, Integer> keywords = extractKeywords(resolved, semanticMap, targetFileRelative);
        return searchWithKeywords(config, resolveRoot(config), keywords, targetFileRelative);
    }

    /** Agent の function tool 用。LLM が選んだキーワードだけで決定論的に検索する。 */
    public static SimilarCodeResult searchByKeywords(AgentConfig config, List<String> keywords,
                                                     String targetFileRelative) {
        Map<String, Integer> weighted = new LinkedHashMap<String, Integer>();
        Set<String> seen = new HashSet<String>();
        if (keywords != null) {
            for (String keyword : keywords) {
                addKeyword(weighted, seen, keyword, 3);
            }
        }
        return searchWithKeywords(config, resolveRoot(config), weighted, targetFileRelative);
    }

    private static Path resolveRoot(AgentConfig config) {
        Path root = config.searchJavaRoot() != null ? config.searchJavaRoot() : config.existingJavaRoot();
        return root == null ? null : root.toAbsolutePath().normalize();
    }

    private static SimilarCodeResult searchWithKeywords(AgentConfig config, Path root,
                                                        Map<String, Integer> keywords,
                                                        String targetFileRelative) {
        if (root == null || !Files.isDirectory(root)) {
            return new SimilarCodeResult(root, Collections.<SimilarCodeSnippet>emptyList(),
                    new ArrayList<String>(keywords.keySet()), 0,
                    "（類似コード検索の対象ディレクトリが未設定、または存在しません。path.searchJavaRoot を確認してください）");
        }

        List<Path> files = collectFiles(root, config.searchMaxFiles());
        List<Candidate> candidates = scoreFiles(files, root, keywords, targetFileRelative);
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate left, Candidate right) {
                int byScore = right.score - left.score;
                return byScore != 0 ? byScore : left.path.toString().compareTo(right.path.toString());
            }
        });

        int slots = Math.max(1, config.maxSimilarFiles());
        int perFileLimit = Math.max(800, config.maxSimilarCodeChars() / slots);
        int remaining = config.maxSimilarCodeChars();
        List<SimilarCodeSnippet> snippets = new ArrayList<SimilarCodeSnippet>();
        for (Candidate candidate : candidates) {
            if (snippets.size() >= slots || remaining <= 0) {
                break;
            }
            String content = candidate.content;
            int limit = Math.min(perFileLimit, remaining);
            if (content.length() > limit) {
                content = content.substring(0, limit) + "\n...（省略）";
            }
            remaining -= content.length();
            snippets.add(new SimilarCodeSnippet(candidate.path, candidate.relativePath,
                    candidate.score, content));
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("検索キーワード: ").append(keywords.keySet()).append('\n');
        prompt.append("走査 Java ファイル数: ").append(files.size())
                .append(" / 抽出: ").append(snippets.size()).append("\n\n");
        if (snippets.isEmpty()) {
            prompt.append("（設定された検索対象から類似コードは見つかりませんでした）");
        } else {
            for (int i = 0; i < snippets.size(); i++) {
                SimilarCodeSnippet snippet = snippets.get(i);
                prompt.append("===== 類似コード ").append(i + 1).append(": ")
                        .append(snippet.relativePath()).append(" (一致度 ")
                        .append(snippet.score()).append(") =====\n")
                        .append(snippet.content()).append("\n\n");
            }
        }
        return new SimilarCodeResult(root, snippets,
                new ArrayList<String>(keywords.keySet()), files.size(), prompt.toString());
    }

    /** 機能 md・意味マッピング・対象ファイル名から検索キーワードを決める。 */
    private static Map<String, Integer> extractKeywords(ResolvedFeature resolved,
                                                        SemanticMap map, String targetFileRelative) {
        Map<String, Integer> keywords = new LinkedHashMap<String, Integer>();
        Set<String> seen = new HashSet<String>();

        if (!StrUtil.isBlank(map.className())) {
            addKeyword(keywords, seen, map.className(), 3);
        }
        for (SemanticMap.NamePair pair : map.fields()) {
            addKeyword(keywords, seen, pair.english(), 3);
            addKeyword(keywords, seen, pair.japanese(), 2);
        }
        for (SemanticMap.NamePair pair : map.methods()) {
            addKeyword(keywords, seen, pair.english(), 3);
            addKeyword(keywords, seen, pair.japanese(), 2);
        }
        for (SemanticMap.NamePair pair : map.tables()) {
            addKeyword(keywords, seen, pair.english(), 2);
            addKeyword(keywords, seen, pair.japanese(), 2);
        }
        for (Map.Entry<String, String> entry : resolved.variables().snapshot().entrySet()) {
            if (SKIP_VARIABLE_KEYS.contains(entry.getKey())) {
                continue;
            }
            addKeyword(keywords, seen, entry.getValue(), 2);
        }
        FeatureSpec spec = resolved.spec();
        String packageName = resolved.variable("packageName", "");
        if (!StrUtil.isBlank(packageName)) {
            String last = packageName.substring(packageName.lastIndexOf('.') + 1);
            addKeyword(keywords, seen, last, 1);
        }
        for (String line : resolved.featureBody().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                addKeyword(keywords, seen, trimmed.substring(4).replace("`", "").trim(), 1);
            }
        }
        if (!StrUtil.isBlank(targetFileRelative)) {
            String name = targetFileRelative.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            if (name.endsWith(".java")) {
                name = name.substring(0, name.length() - 5);
            }
            addKeyword(keywords, seen, name, 3);
        }

        // 順序を固定したまま上位 40 件に制限する
        Map<String, Integer> limited = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : keywords.entrySet()) {
            if (limited.size() >= 40) {
                break;
            }
            limited.put(entry.getKey(), entry.getValue());
        }
        return limited;
    }

    private static void addKeyword(Map<String, Integer> keywords, Set<String> seen,
                                   String value, int weight) {
        String keyword = StrUtil.trimToEmpty(value).replace("`", "");
        if (keyword.length() < 2 || keyword.matches("\\d+") || GENERIC_TERMS.contains(keyword)) {
            return;
        }
        String key = keyword.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            Integer existing = keywords.get(key);
            if (existing != null && weight > existing) {
                keywords.put(key, weight);
            }
            return;
        }
        keywords.put(key, weight);
    }

    private static List<Path> collectFiles(Path root, int limit) {
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java"))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("既存 Java の走査に失敗: " + root, e);
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.toString().compareTo(right.toString());
            }
        });
        if (files.size() > limit) {
            files = new ArrayList<Path>(files.subList(0, limit));
        }
        return files;
    }

    private static List<Candidate> scoreFiles(List<Path> files, Path root,
                                              Map<String, Integer> keywords, String targetFileRelative) {
        Path target = null;
        if (!StrUtil.isBlank(targetFileRelative)) {
            target = root.resolve(targetFileRelative).toAbsolutePath().normalize();
        }
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (Path file : files) {
            Path absolute = file.toAbsolutePath().normalize();
            if (target != null && absolute.equals(target)) {
                continue; // 修正対象そのものは別セクションで渡すため重複させない
            }
            String content;
            try {
                content = FileUtil.read(absolute);
            } catch (IOException e) {
                continue; // 読めないファイルは検索対象外（検索はベストエフォート）
            }
            if (content.length() > 200000) {
                continue;
            }
            String haystack = content.toLowerCase(Locale.ROOT);
            String fileName = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
            int score = 0;
            for (Map.Entry<String, Integer> entry : keywords.entrySet()) {
                String needle = entry.getKey();
                int count = countOccurrences(haystack, needle);
                if (count == 0) {
                    continue;
                }
                score += entry.getValue() * Math.min(count, 5);
                if (fileName.contains(needle)) {
                    score += entry.getValue() * 2;
                }
            }
            if (score > 0) {
                String relative = root.relativize(absolute).toString().replace('\\', '/');
                candidates.add(new Candidate(absolute, relative, score, content));
            }
        }
        return candidates;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int found = haystack.indexOf(needle, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + needle.length();
        }
    }

    private static final class Candidate {
        private final Path path;
        private final String relativePath;
        private final int score;
        private final String content;

        Candidate(Path path, String relativePath, int score, String content) {
            this.path = path;
            this.relativePath = relativePath;
            this.score = score;
            this.content = content;
        }
    }
}
