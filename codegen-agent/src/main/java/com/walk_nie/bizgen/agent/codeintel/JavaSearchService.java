package com.walk_nie.bizgen.agent.codeintel;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.agent.tool.ToolSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 索引に対する検索の窓口。並び順と上限を固定し、毎回同じ入力には同じ結果を返す。 */
public final class JavaSearchService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{1,}");
    private static final Set<String> STOP_WORDS = new HashSet<String>(Arrays.asList(
            "public", "private", "protected", "static", "final", "return", "new", "this",
            "void", "int", "long", "boolean", "byte", "char", "short", "double", "float",
            "if", "else", "for", "while", "switch", "case", "break", "continue", "try",
            "catch", "throw", "throws", "true", "false", "null", "class", "interface",
            "string", "list", "optional", "override", "import", "package"));

    private final AgentConfig config;
    private final JavaIndexStore store;

    public JavaSearchService(AgentConfig config, JavaIndexStore store) {
        this.config = config;
        this.store = store;
    }

    public String listFiles(String prefix, int limit) throws SQLException {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String path : store.knownPaths()) {
            if (!prefix.isEmpty() && !path.toLowerCase().contains(prefix.toLowerCase())) {
                continue;
            }
            out.append(path).append('\n');
            count++;
            if (count >= limit) {
                out.append("...（上限 ").append(limit).append(" 件）\n");
                break;
            }
        }
        if (count == 0) {
            return "該当する Java ファイルがありません。先に java-index build を実行してください。";
        }
        return out.toString();
    }

    public String keyword(String query, int limit) throws SQLException {
        List<JavaIndexStore.Hit> hits = store.keywordSearch(query, limit);
        return formatHits(hits, "キーワード", query, limit);
    }

    public String findClass(String query, int limit) throws SQLException {
        return formatHits(store.searchClasses(query, limit), "クラス", query, limit);
    }

    public String findMethod(String query, int limit) throws SQLException {
        return formatHits(store.searchMethods(query, limit), "メソッド", query, limit);
    }

    public String references(String name, int limit) throws SQLException {
        return formatHits(store.references(name, limit), "参照", name, limit);
    }

    public String calls(String name, int limit) throws SQLException {
        return formatHits(store.calls(name, limit), "呼び出し関係", name, limit);
    }

    public String classStructure(String name) throws SQLException {
        return store.classStructure(name, Math.min(1200, config.javaIndexMaxResultChars()));
    }

    /** 既存 Java を索引から読む。索引に無い場合は設定ルート配下の実ファイルを読む。 */
    public String readFile(String relativePath) throws SQLException, IOException {
        String relative = relativePath.replace('\\', '/');
        if (relative.isEmpty() || relative.contains("..")) {
            throw new IllegalArgumentException("不正なパスです: " + relativePath);
        }
        Path root = root();
        Path target = root.resolve(relative).normalize().toAbsolutePath();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("設定されたルート外のファイルは読めません: " + relativePath);
        }
        String indexed = store.readSource(relative);
        if (indexed != null) {
            return ToolSupport.truncate(indexed, config.javaIndexMaxResultChars());
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("ファイルが見つかりません: " + relativePath);
        }
        return ToolSupport.truncate(new String(Files.readAllBytes(target), StandardCharsets.UTF_8),
                config.javaIndexMaxResultChars());
    }

    /**
     * 似たメソッドを Jaccard 類似度で探す。埋め込み模型を使わず、トークン集合だけを見るため再現性がある。
     */
    public String similarMethods(String target, int limit) throws SQLException {
        int bodyChars = 4000;
        JavaIndexStore.MethodSnippet targetMethod = null;
        try {
            long id = Long.parseLong(target.trim());
            targetMethod = store.methodSnippet(id, bodyChars);
        } catch (NumberFormatException ignored) {
            // ID でなければメソッド名として扱う
        }
        if (targetMethod == null) {
            for (JavaIndexStore.MethodSnippet candidate : store.methodSnippets(bodyChars)) {
                if (candidate.name.equals(target) || candidate.signature.contains(target)) {
                    targetMethod = candidate;
                    break;
                }
            }
        }
        if (targetMethod == null) {
            return "比較対象のメソッドが見つかりません: " + target;
        }
        Set<String> targetTokens = tokens(targetMethod);
        List<Scored> scored = new ArrayList<Scored>();
        for (JavaIndexStore.MethodSnippet candidate : store.methodSnippets(bodyChars)) {
            if (candidate.id == targetMethod.id) {
                continue;
            }
            Set<String> tokens = tokens(candidate);
            double score = jaccard(targetTokens, tokens);
            if (candidate.name.equals(targetMethod.name)) {
                score += 0.15;
            }
            if (candidate.ownerClass.equals(targetMethod.ownerClass)) {
                score += 0.05;
            }
            if (score > 0) {
                scored.add(new Scored(candidate, Math.min(1.0, score)));
            }
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored left, Scored right) {
                int byScore = Double.compare(right.score, left.score);
                if (byScore != 0) {
                    return byScore;
                }
                int byPath = left.method.path.compareTo(right.method.path);
                return byPath != 0 ? byPath : left.method.startLine - right.method.startLine;
            }
        });
        StringBuilder out = new StringBuilder();
        out.append("比較元: ").append(targetMethod.ownerClass).append('#')
                .append(targetMethod.name).append(" (").append(targetMethod.path).append(':')
                .append(targetMethod.startLine).append(")\n");
        if (scored.isEmpty()) {
            out.append("類似メソッドは見つかりませんでした。\n");
            return out.toString();
        }
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            Scored item = scored.get(i);
            out.append(String.format(Locale.ROOT, "  %.3f  %s#%s  %s:%d%n",
                    item.score, item.method.ownerClass, item.method.name,
                    item.method.path, item.method.startLine));
        }
        return out.toString();
    }

    /** 五级設計 md を決定的な順序で読み、全文を規約として返す。 */
    public String projectRules() throws IOException {
        Path mdRoot = config.mdRoot().toAbsolutePath().normalize();
        String[] levels = {"01-java-common", "02-project-common", "03-usecase",
                "04-java-class", "05-method"};
        StringBuilder out = new StringBuilder();
        for (String level : levels) {
            Path dir = mdRoot.resolve(level);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                java.util.Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    String name = path.getFileName().toString();
                    if (name.toLowerCase().endsWith(".md") && !name.startsWith("_")) {
                        files.add(path);
                    }
                }
            }
            Collections.sort(files);
            for (Path file : files) {
                out.append("===== ").append(level).append('/').append(file.getFileName())
                        .append(" =====\n");
                out.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return ToolSupport.truncate(out.toString(), config.javaIndexMaxResultChars());
    }

    private Path root() {
        Path root = config.javaIndexRoot();
        if (root == null) {
            throw new IllegalStateException("path.javaIndexRoot が設定されていません");
        }
        return root.toAbsolutePath().normalize();
    }

    private String formatHits(List<JavaIndexStore.Hit> hits, String label, String query, int limit) {
        if (hits.isEmpty()) {
            return label + "検索 '" + query + "': 該当なし";
        }
        StringBuilder out = new StringBuilder();
        out.append(label).append("検索 '").append(query).append("': ").append(hits.size())
                .append(" 件（上限 ").append(limit).append("）\n");
        for (JavaIndexStore.Hit hit : hits) {
            out.append("  ").append(hit.label()).append('\n');
        }
        return ToolSupport.truncate(out.toString(), config.javaIndexMaxResultChars());
    }

    private static Set<String> tokens(JavaIndexStore.MethodSnippet method) {
        Set<String> result = new java.util.TreeSet<String>();
        String text = (method.signature + " " + method.body).toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<String>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<String>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static final class Scored {
        final JavaIndexStore.MethodSnippet method;
        final double score;

        Scored(JavaIndexStore.MethodSnippet method, double score) {
            this.method = method;
            this.score = score;
        }
    }
}
