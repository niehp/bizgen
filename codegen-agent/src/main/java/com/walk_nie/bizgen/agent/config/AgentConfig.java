package com.walk_nie.bizgen.agent.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 設定値を保持するイミュータブルなオブジェクト。
 * LLM 情報はコマンドライン引数ではなく設定ファイルからのみ読み込む（--live 方式を採らない）。
 */
public final class AgentConfig {

    private final String baseUrl;
    private final String chatPath;
    private final String model;
    private final String apiKey;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutMs;
    private final Integer seed;
    private final String toolChoice;
    private final boolean toolCallRecovery;

    private final Path mdRoot;
    private final Path outputRoot;
    private final Path generatedJavaRoot;
    private final Path existingJavaRoot;
    private final Path searchJavaRoot;
    private final Path configFile;
    private final Path termRoot;
    private final Path apiRoot;
    private final Path featureOutputRoot;
    private final Path templateFillRoot;
    private final Path templateFillOutputRoot;
    private final String templateFillPrompt;
    private final int templateFillMaxFiles;
    private final Path specRoot;
    private final int specMaxChars;
    private final Path blockRoot;
    private final boolean reuseEnabled;
    private final int reuseMinScore;
    private final int reuseTopN;

    private final int maxTables;
    private final int maxSimilarFiles;
    private final int maxSimilarCodeChars;
    private final int searchMaxFiles;
    private final boolean reviewRequired;
    private final int indentSize;

    private final int agentMaxRounds;
    private final int agentMaxToolCalls;
    private final int agentMaxSameCall;
    private final int apiTopN;
    private final boolean selfCorrect;
    private final int maxBlocks;
    private final int maxAutoDesigns;

    /** 意味解析による検索意図の有効/無効・上限・prompt 名。 */
    private final boolean semanticRetrievalEnabled;
    private final int semanticMaxQueries;
    private final String semanticRetrievalPrompt;

    private final Path javaIndexRoot;
    private final Path javaIndexDb;
    private final int javaIndexMaxFiles;
    private final int javaIndexMaxFileChars;
    private final String javaIndexSkipDirs;
    private final int javaIndexMaxResultChars;
    private final int javaIndexMaxResults;
    private final int javaIndexSimilarTopN;
    private final boolean editRequireApprovalToken;
    private final String editBackupSuffix;

    private AgentConfig(Properties properties) {
        this.baseUrl = value(properties, "llm.baseUrl", "http://127.0.0.1:11434/v1");
        this.chatPath = value(properties, "llm.chatPath", "/chat/completions");
        this.model = value(properties, "llm.model", "qwen2.5-coder:14b");
        this.apiKey = value(properties, "llm.apiKey", "");
        this.temperature = Double.parseDouble(value(properties, "llm.temperature", "0.1"));
        this.maxTokens = Integer.parseInt(value(properties, "llm.maxTokens", "8192"));
        this.timeoutMs = Integer.parseInt(value(properties, "llm.timeoutMs", "180000"));
        String seedValue = value(properties, "llm.seed", "42").trim();
        this.seed = seedValue.isEmpty() ? null : Integer.valueOf(seedValue);
        this.toolChoice = value(properties, "llm.toolChoice", "auto")
                .toLowerCase(java.util.Locale.ROOT);
        this.toolCallRecovery = Boolean.parseBoolean(
                value(properties, "agent.toolCallRecovery", "true"));

        this.mdRoot = java.nio.file.Paths.get(value(properties, "path.mdRoot", "md"));
        this.outputRoot = java.nio.file.Paths.get(value(properties, "path.outputRoot", "out"));
        this.generatedJavaRoot = java.nio.file.Paths.get(
                value(properties, "path.generatedJavaRoot", "out/java"));
        String javaRoot = value(properties, "path.existingJavaRoot", "").trim();
        this.existingJavaRoot = javaRoot.isEmpty() ? null : java.nio.file.Paths.get(javaRoot);
        String searchRoot = value(properties, "path.searchJavaRoot", "").trim();
        this.searchJavaRoot = searchRoot.isEmpty()
                ? this.existingJavaRoot : java.nio.file.Paths.get(searchRoot);
        this.configFile = java.nio.file.Paths.get(
                value(properties, "path.configFile", "config/agent-config.properties"));
        this.termRoot = java.nio.file.Paths.get(value(properties, "path.termRoot", "md/term"));
        this.apiRoot = java.nio.file.Paths.get(value(properties, "path.apiRoot", "md/api"));
        this.featureOutputRoot = java.nio.file.Paths.get(
                value(properties, "path.featureOutputRoot", "output/src/main/java"));
        String templateFillRootValue = value(properties, "path.templateFillRoot", "").trim();
        this.templateFillRoot = templateFillRootValue.isEmpty()
                ? null : java.nio.file.Paths.get(templateFillRootValue);
        this.templateFillOutputRoot = java.nio.file.Paths.get(
                value(properties, "path.templateFillOutputRoot", "out/template-fill"));
        this.templateFillPrompt = value(properties, "templateFill.prompt", "template-fill");
        this.templateFillMaxFiles = Integer.parseInt(value(properties, "templateFill.maxFiles", "50"));
        this.specRoot = java.nio.file.Paths.get(value(properties, "path.specRoot", "spec"));
        this.specMaxChars = Integer.parseInt(value(properties, "spec.maxChars", "24000"));
        String blockRootValue = value(properties, "path.blockRoot", "spec/block").trim();
        this.blockRoot = blockRootValue.isEmpty()
                ? this.specRoot.resolve("block") : java.nio.file.Paths.get(blockRootValue);
        this.reuseEnabled = Boolean.parseBoolean(value(properties, "reuse.enabled", "true"));
        this.reuseMinScore = Integer.parseInt(value(properties, "reuse.minScore", "5"));
        this.reuseTopN = Integer.parseInt(value(properties, "reuse.topN", "5"));

        this.maxTables = Integer.parseInt(value(properties, "gen.maxTables", "5"));
        this.maxSimilarFiles = Integer.parseInt(value(properties, "gen.maxSimilarFiles", "5"));
        this.maxSimilarCodeChars = Integer.parseInt(value(properties, "gen.maxSimilarCodeChars", "12000"));
        this.searchMaxFiles = Integer.parseInt(value(properties, "gen.searchMaxFiles", "2000"));
        this.reviewRequired = Boolean.parseBoolean(value(properties, "gen.reviewRequired", "true"));
        this.indentSize = Integer.parseInt(value(properties, "gen.indentSize", "4"));

        this.agentMaxRounds = Integer.parseInt(value(properties, "agent.maxRounds", "8"));
        this.agentMaxToolCalls = Integer.parseInt(value(properties, "agent.maxToolCalls", "30"));
        this.agentMaxSameCall = Integer.parseInt(value(properties, "agent.maxSameCall", "3"));
        this.apiTopN = Integer.parseInt(value(properties, "gen.apiTopN", "8"));
        this.selfCorrect = Boolean.parseBoolean(value(properties, "gen.selfCorrect", "true"));
        this.maxBlocks = Integer.parseInt(value(properties, "gen.maxBlocks", "12"));
        this.maxAutoDesigns = Math.max(0,
                Integer.parseInt(value(properties, "gen.maxAutoDesigns", "6")));

        this.semanticRetrievalEnabled = Boolean.parseBoolean(
                value(properties, "semanticRetrieval.enabled", "true"));
        this.semanticMaxQueries = Math.max(1,
                Integer.parseInt(value(properties, "semanticRetrieval.maxQueries", "8")));
        this.semanticRetrievalPrompt = value(properties,
                "semanticRetrieval.prompt", "semantic-retrieval");

        String indexRoot = value(properties, "path.javaIndexRoot", "").trim();
        this.javaIndexRoot = indexRoot.isEmpty() ? null : java.nio.file.Paths.get(indexRoot);
        this.javaIndexDb = java.nio.file.Paths.get(
                value(properties, "path.javaIndexDb", "out/java-index.db"));
        this.javaIndexMaxFiles = Math.max(1,
                Integer.parseInt(value(properties, "javaIndex.maxFiles", "5000")));
        this.javaIndexMaxFileChars = Math.max(1,
                Integer.parseInt(value(properties, "javaIndex.maxFileChars", "200000")));
        this.javaIndexSkipDirs = value(properties, "javaIndex.skipDirs",
                "target,build,out,.git,node_modules,.workbuddy");
        this.javaIndexMaxResultChars = Math.max(1,
                Integer.parseInt(value(properties, "javaIndex.maxResultChars", "16000")));
        this.javaIndexMaxResults = Math.max(1,
                Integer.parseInt(value(properties, "javaIndex.maxResults", "20")));
        this.javaIndexSimilarTopN = Math.max(1,
                Integer.parseInt(value(properties, "javaIndex.similarTopN", "10")));
        this.editRequireApprovalToken = Boolean.parseBoolean(
                value(properties, "edit.requireApprovalToken", "true"));
        this.editBackupSuffix = properties.getProperty("edit.backupSuffix", ".bak") == null
                ? ".bak" : properties.getProperty("edit.backupSuffix", ".bak").trim();
    }

    private static String value(Properties properties, String key, String defaultValue) {
        String v = properties.getProperty(key);
        return v == null || v.trim().isEmpty() ? defaultValue : v.trim();
    }

    /** 設定ファイルを読み込む。ファイルが無い場合は既定値のみで生成する。 */
    public static AgentConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (InputStream stream = Files.newInputStream(path);
                 InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return new AgentConfig(properties);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String chatPath() {
        return chatPath;
    }

    public String endpoint() {
        return baseUrl.replaceAll("/+$", "") + (chatPath.startsWith("/") ? chatPath : "/" + chatPath);
    }

    public String model() {
        return model;
    }

    public String apiKey() {
        return apiKey;
    }

    public double temperature() {
        return temperature;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public Integer seed() {
        return seed;
    }

    /** OpenAI 互換 API の tool_choice（auto / required / none）。 */
    public String toolChoice() {
        return toolChoice;
    }

    /** content に漏れた tool 呼び出しテキストを復元するか。 */
    public boolean toolCallRecovery() {
        return toolCallRecovery;
    }

    public Path mdRoot() {
        return mdRoot;
    }

    public Path outputRoot() {
        return outputRoot;
    }

    /** 生成 Java の保存先ルート。機能 md ではなく設定ファイルのみで決める。 */
    public Path generatedJavaRoot() {
        return generatedJavaRoot;
    }

    public Path existingJavaRoot() {
        return existingJavaRoot;
    }

    /** 類似コード検索対象ルート。未設定なら existingJavaRoot を利用する。 */
    public Path searchJavaRoot() {
        return searchJavaRoot;
    }

    public Path configFile() {
        return configFile;
    }

    public Path termRoot() {
        return termRoot;
    }

    public Path apiRoot() {
        return apiRoot;
    }

    /** feature-gen の出力ルート（パッケージ構造はこの下に作られる）。 */
    public Path featureOutputRoot() {
        return featureOutputRoot;
    }

    /** テンプレート一括入力ルート。未設定なら template-fill は使用不可。 */
    public Path templateFillRoot() {
        return templateFillRoot;
    }

    /** テンプレート一括出力ルート（相対ディレクトリ構造を維持して書き出す）。 */
    public Path templateFillOutputRoot() {
        return templateFillOutputRoot;
    }

    public String templateFillPrompt() {
        return templateFillPrompt;
    }

    public int templateFillMaxFiles() {
        return templateFillMaxFiles;
    }

    /** 参考資料ルート（spec/api, spec/dict, spec/template, spec/example を格納）。 */
    public Path specRoot() {
        return specRoot;
    }

    /** prompt に注入する spec 資料の合計文字数上限。 */
    public int specMaxChars() {
        return specMaxChars;
    }

    /** 再利用可能なコード片 / API 呼び出しテンプレートのルート。 */
    public Path blockRoot() {
        return blockRoot;
    }

    /** 再利用機能の有効 / 無効。 */
    public boolean reuseEnabled() {
        return reuseEnabled;
    }

    /** 再利用ブロックを採用する最低スコア。これ未満はモデル生成に任せる。 */
    public int reuseMinScore() {
        return reuseMinScore;
    }

    /** 一度に候補として返す再利用ブロックの最大件数。 */
    public int reuseTopN() {
        return reuseTopN;
    }

    public int apiTopN() {
        return apiTopN;
    }

    /** 意味解析フェーズ（モデルが検索意図を立てる）を有効にするか。 */
    public boolean semanticRetrievalEnabled() {
        return semanticRetrievalEnabled;
    }

    /** 1 回の意味解析で許す検索意図の最大件数。 */
    public int semanticMaxQueries() {
        return semanticMaxQueries;
    }

    /** 意味解析に使う prompt 名（md/prompt 配下）。 */
    public String semanticRetrievalPrompt() {
        return semanticRetrievalPrompt;
    }

    public boolean selfCorrect() {
        return selfCorrect;
    }

    public int maxBlocks() {
        return maxBlocks;
    }

    /** 機能 md 本文との一致で自動的に追加する設計 md の上限（0 で無効）。 */
    public int maxAutoDesigns() {
        return maxAutoDesigns;
    }

    public int maxTables() {
        return maxTables;
    }

    public int maxSimilarFiles() {
        return maxSimilarFiles;
    }

    public int maxSimilarCodeChars() {
        return maxSimilarCodeChars;
    }

    public int searchMaxFiles() {
        return searchMaxFiles;
    }

    public boolean reviewRequired() {
        return reviewRequired;
    }

    public int indentSize() {
        return indentSize;
    }

    public int agentMaxRounds() {
        return agentMaxRounds;
    }

    public int agentMaxToolCalls() {
        return agentMaxToolCalls;
    }

    public int agentMaxSameCall() {
        return agentMaxSameCall;
    }

    // --- Java コード知能（JDT + SQLite） ---

    public Path javaIndexRoot() {
        return javaIndexRoot;
    }

    public Path javaIndexDb() {
        return javaIndexDb;
    }

    public int javaIndexMaxFiles() {
        return javaIndexMaxFiles;
    }

    public int javaIndexMaxFileChars() {
        return javaIndexMaxFileChars;
    }

    public String javaIndexSkipDirs() {
        return javaIndexSkipDirs;
    }

    public int javaIndexMaxResultChars() {
        return javaIndexMaxResultChars;
    }

    public int javaIndexMaxResults() {
        return javaIndexMaxResults;
    }

    public int javaIndexSimilarTopN() {
        return javaIndexSimilarTopN;
    }

    public boolean editRequireApprovalToken() {
        return editRequireApprovalToken;
    }

    public String editBackupSuffix() {
        return editBackupSuffix;
    }

    /** API キーをマスクして表示用文字列を返す。 */
    public String describe() {
        String masked = apiKey.isEmpty() ? "(未設定)" : "****" + apiKey.substring(Math.max(0, apiKey.length() - 4));
        return "endpoint  : " + endpoint() + "\n"
                + "model     : " + model + "\n"
                + "apiKey    : " + masked + "\n"
                + "temperature: " + temperature + "\n"
                + "maxTokens : " + maxTokens + "\n"
                + "seed      : " + seed + "\n"
                + "toolChoice: " + toolChoice + "\n"
                + "toolRecovery: " + toolCallRecovery + "\n"
                + "mdRoot    : " + mdRoot.toAbsolutePath().normalize() + "\n"
                + "javaOutRoot: " + generatedJavaRoot.toAbsolutePath().normalize() + "\n"
                + "outputRoot: " + outputRoot.toAbsolutePath().normalize() + "\n"
                + "javaRoot  : " + (existingJavaRoot == null ? "(未設定)" : existingJavaRoot.toAbsolutePath().normalize()) + "\n"
                + "searchRoot: " + (searchJavaRoot == null ? "(未設定)" : searchJavaRoot.toAbsolutePath().normalize()) + "\n"
                + "termRoot  : " + termRoot.toAbsolutePath().normalize() + "\n"
                + "apiRoot   : " + apiRoot.toAbsolutePath().normalize() + "\n"
                + "featureOut: " + featureOutputRoot.toAbsolutePath().normalize() + "\n"
                + "tplFillIn : " + (templateFillRoot == null ? "(未設定)" : templateFillRoot.toAbsolutePath().normalize()) + "\n"
                + "tplFillOut: " + templateFillOutputRoot.toAbsolutePath().normalize()
                + " (prompt=" + templateFillPrompt + ", maxFiles=" + templateFillMaxFiles + ")\n"
                + "specRoot  : " + specRoot.toAbsolutePath().normalize()
                + " (maxChars=" + specMaxChars + ")\n"
                + "maxTables : " + maxTables + "\n"
                + "similarCode: maxFiles=" + maxSimilarFiles + ", maxChars=" + maxSimilarCodeChars
                + ", scanLimit=" + searchMaxFiles + "\n"
                + "agentLimits: rounds=" + agentMaxRounds + ", toolCalls=" + agentMaxToolCalls
                + ", sameCall=" + agentMaxSameCall + "\n"
                + "featureGen: apiTopN=" + apiTopN + ", selfCorrect=" + selfCorrect
                + ", maxBlocks=" + maxBlocks + ", maxAutoDesigns=" + maxAutoDesigns + "\n"
                + "semanticRetrieval: enabled=" + semanticRetrievalEnabled
                + ", maxQueries=" + semanticMaxQueries
                + ", prompt=" + semanticRetrievalPrompt + "\n"
                + "javaIndex: root=" + (javaIndexRoot == null ? "(未設定)"
                        : javaIndexRoot.toAbsolutePath().normalize())
                + ", db=" + javaIndexDb.toAbsolutePath().normalize()
                + ", maxFiles=" + javaIndexMaxFiles + ", maxFileChars=" + javaIndexMaxFileChars + "\n"
                + "editApproval: tokenRequired=" + editRequireApprovalToken
                + ", backupSuffix=" + (editBackupSuffix.isEmpty() ? "(なし)" : editBackupSuffix) + "\n"
                + "reviewRequired: " + reviewRequired;
    }
}
