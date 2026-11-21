package com.walk_nie.bizgen.agent.md;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.util.FileUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        Path path = resolve(levelDir(DesignLevel.USECASE), name);
        return new FeatureSpec(MdDoc.parse(path, DesignLevel.USECASE));
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
     * Java共通・プロジェクト共通は常時読み込み、それ以外（ユースケース/クラス/メソッド）は明示参照時のみ。
     */
    public List<MdDoc> loadDesigns(List<DesignRef> refs) throws IOException {
        Set<String> loaded = new LinkedHashSet<String>();
        List<MdDoc> result = new ArrayList<MdDoc>();

        for (DesignLevel base : new DesignLevel[]{
                DesignLevel.JAVA_COMMON, DesignLevel.PROJECT_COMMON}) {
            for (MdDoc doc : list(base)) {
                if (loaded.add(doc.path().toString())) {
                    result.add(doc);
                }
            }
        }

        for (DesignRef ref : refs) {
            if (ref.level() != null) {
                MdDoc doc = load(ref.level(), ref.name());
                if (loaded.add(doc.path().toString())) {
                    result.add(doc);
                }
            } else {
                List<MdDoc> matches = findByName(ref.name());
                for (MdDoc doc : matches) {
                    if (loaded.add(doc.path().toString())) {
                        result.add(doc);
                    }
                }
            }
        }
        return result;
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
