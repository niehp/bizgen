package com.walk_nie.bizgen.agent.md;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.KeywordExtractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * md リポジトリ。5 段階の設計ドキュメント、prompt、template、表構造を一元管理する。
 * 読み込むファイルは機能 md の参照から決定し、LLM へ渡す情報を必要最小限に抑える。
 */
public final class MdRepository {

    private final AgentConfig config;
    private final Path root;

    public MdRepository(AgentConfig config) {
        this.config = config;
        this.root = config.mdRoot().toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path levelDir(DesignLevel level) {
        return level.resolve(root);
    }

    public Path promptDir() {
        return root.resolve("prompt");
    }

    public Path templateDir() {
        return root.resolve("template");
    }

    public Path schemaDir() {
        return root.resolve("schema");
    }

    /** 機能 md を読み込む。 */
    public FeatureSpec loadFeature(String name) throws IOException {
        return loadFeature(name, null);
    }

    /** 機能 md を読み込み、front matter の変数を baseVariables から解決する。 */
    public FeatureSpec loadFeature(String name, Map<String, String> baseVariables) throws IOException {
        Path path = resolve(levelDir(DesignLevel.USECASE), name);
        return new FeatureSpec(MdDoc.parse(path, DesignLevel.USECASE), baseVariables);
    }

    /** 任意レベルの設計 md を読み込む。 */
    public MdDoc load(DesignLevel level, String name) throws IOException {
        return MdDoc.parse(resolve(levelDir(level), name), level);
    }

    /** レベル内の md 一覧を返す。 */
    public List<MdDoc> list(DesignLevel level) throws IOException {
        List<MdDoc> result = new ArrayList<MdDoc>();
        for (Path path : FileUtil.listMarkdown(levelDir(level))) {
            result.add(MdDoc.parse(path, level));
        }
        return result;
    }

    /**
     * 機能 md が参照する設計ドキュメントを読み込む。
     *
     * 読み込み順は決定的:
     * 1. 機能 md の designs: / designRefs: による明示参照（全レベル対象）
     * 2. 機能 md 本文とのキーワード一致による自動追加（gen.maxAutoDesigns 件まで）
     *
     * Java共通・プロジェクト共通も全量読み込みはしない。無関係な設計文で 14B の
     * コンテキストを浪費しないため、必要なものだけを関連度順に取る。
     */
    public List<MdDoc> loadDesigns(FeatureSpec spec) throws IOException {
        if (spec == null) {
            return loadDesigns(Collections.<DesignRef>emptyList(), "", null);
        }
        return loadDesigns(spec.designRefs(), spec.body(), spec.doc().path());
    }

    /** 後方互換: 明示参照のみを読み込む（本文一致による自動追加なし）。 */
    public List<MdDoc> loadDesigns(List<DesignRef> refs) throws IOException {
        return loadDesigns(refs, "", null);
    }

    private List<MdDoc> loadDesigns(List<DesignRef> refs, String featureBody,
                                    Path featurePath) throws IOException {
        Set<String> loaded = new LinkedHashSet<String>();
        List<MdDoc> result = new ArrayList<MdDoc>();

        for (DesignRef ref : refs) {
            if (ref.level() != null) {
                MdDoc doc = load(ref.level(), ref.name());
                if (loaded.add(doc.path().toString())) {
                    result.add(doc);
                }
            } else {
                for (MdDoc doc : findByName(ref.name())) {
                    if (loaded.add(doc.path().toString())) {
                        result.add(doc);
                    }
                }
            }
        }

        int limit = config.maxAutoDesigns();
        if (limit > 0 && featureBody != null && !featureBody.trim().isEmpty()) {
            List<String> keywords = KeywordExtractor.extract(featureBody, 40);
            Path feature = featurePath == null ? null : featurePath.toAbsolutePath().normalize();
            List<ScoredDoc> scored = new ArrayList<ScoredDoc>();
            for (DesignLevel level : DesignLevel.values()) {
                for (MdDoc doc : list(level)) {
                    Path path = doc.path().toAbsolutePath().normalize();
                    if (feature != null && path.equals(feature)) {
                        continue; // 機能 md 自身は設計資料ではない
                    }
                    int score = scoreDesign(doc, keywords);
                    if (score > 0) {
                        scored.add(new ScoredDoc(score, level.ordinal(), path.toString(), doc));
                    }
                }
            }
            Collections.sort(scored, new Comparator<ScoredDoc>() {
                @Override
                public int compare(ScoredDoc left, ScoredDoc right) {
                    if (left.score != right.score) {
                        return right.score - left.score;
                    }
                    if (left.levelOrder != right.levelOrder) {
                        return left.levelOrder - right.levelOrder;
                    }
                    return left.path.compareTo(right.path);
                }
            });
            int added = 0;
            for (ScoredDoc item : scored) {
                if (added >= limit) {
                    break;
                }
                if (loaded.add(item.doc.path().toString())) {
                    result.add(item.doc);
                    added++;
                }
            }
        }
        return result;
    }

    private static int scoreDesign(MdDoc doc, List<String> keywords) {
        String head = (doc.title() + " " + doc.name()).toLowerCase(Locale.ROOT);
        String body = doc.body().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (head.contains(keyword)) {
                score += 4;
                continue;
            }
            score += Math.min(countOccurrences(body, keyword), 3);
        }
        return score;
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

    private List<MdDoc> findByName(String name) throws IOException {
        List<MdDoc> matches = new ArrayList<MdDoc>();
        for (DesignLevel level : DesignLevel.values()) {
            for (MdDoc doc : list(level)) {
                if (doc.name().equalsIgnoreCase(name)) {
                    matches.add(doc);
                }
            }
        }
        return matches;
    }

    private static final class ScoredDoc {
        private final int score;
        private final int levelOrder;
        private final String path;
        private final MdDoc doc;

        ScoredDoc(int score, int levelOrder, String path, MdDoc doc) {
            this.score = score;
            this.levelOrder = levelOrder;
            this.path = path;
            this.doc = doc;
        }
    }

    /** prompt テンプレートの本文を返す。 */
    public String prompt(String name) throws IOException {
        return readNamed(promptDir(), name);
    }

    /** 機能 md / 表構造などのテンプレート本文を返す。 */
    public String template(String name) throws IOException {
        return readNamed(templateDir(), name);
    }

    /** 表構造 md の本文を返す。 */
    public String schema(String name) throws IOException {
        return readNamed(schemaDir(), name);
    }

    /** 表構造 md の名前一覧（_ で始まるテンプレートは除外）。 */
    public List<String> schemaNames() throws IOException {
        List<String> names = new ArrayList<String>();
        for (Path path : FileUtil.listMarkdown(schemaDir())) {
            String base = FileUtil.baseName(path);
            if (!base.startsWith("_")) {
                names.add(base);
            }
        }
        return names;
    }

    private String readNamed(Path directory, String name) throws IOException {
        Path path = resolve(directory, name);
        return FileUtil.read(path);
    }

    private Path resolve(Path directory, String name) throws IOException {
        String cleaned = name == null ? "" : name.trim().replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        if (!cleaned.toLowerCase().endsWith(".md")) {
            cleaned = cleaned + ".md";
        }
        Path path = directory.resolve(cleaned).toAbsolutePath().normalize();
        if (!FileUtil.exists(path)) {
            List<String> available = new ArrayList<String>();
            for (Path candidate : FileUtil.listMarkdown(directory)) {
                available.add(FileUtil.baseName(candidate));
            }
            throw new MdException("md ファイルが見つかりません: " + path
                    + "\n利用可能: " + available);
        }
        return path;
    }

    public AgentConfig config() {
        return config;
    }

    /** md リポジトリに伴う失敗。 */
    public static final class MdException extends RuntimeException {
        public MdException(String message) {
            super(message);
        }

        public MdException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
