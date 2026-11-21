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

    private final Path mdRoot;
    private final Path outputRoot;
    private final Path generatedJavaRoot;
    private final Path existingJavaRoot;
    private final Path searchJavaRoot;
    private final Path configFile;

    private final int maxTables;
    private final int maxSimilarFiles;
    private final int maxSimilarCodeChars;
    private final int searchMaxFiles;
    private final boolean reviewRequired;
    private final int indentSize;

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

        this.maxTables = Integer.parseInt(value(properties, "gen.maxTables", "5"));
        this.maxSimilarFiles = Integer.parseInt(value(properties, "gen.maxSimilarFiles", "5"));
        this.maxSimilarCodeChars = Integer.parseInt(value(properties, "gen.maxSimilarCodeChars", "12000"));
        this.searchMaxFiles = Integer.parseInt(value(properties, "gen.searchMaxFiles", "2000"));
        this.reviewRequired = Boolean.parseBoolean(value(properties, "gen.reviewRequired", "true"));
        this.indentSize = Integer.parseInt(value(properties, "gen.indentSize", "4"));
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

    /** API キーをマスクして表示用文字列を返す。 */
    public String describe() {
        String masked = apiKey.isEmpty() ? "(未設定)" : "****" + apiKey.substring(Math.max(0, apiKey.length() - 4));
        return "endpoint  : " + endpoint() + "\n"
                + "model     : " + model + "\n"
                + "apiKey    : " + masked + "\n"
                + "temperature: " + temperature + "\n"
                + "maxTokens : " + maxTokens + "\n"
                + "seed      : " + seed + "\n"
                + "mdRoot    : " + mdRoot.toAbsolutePath().normalize() + "\n"
                + "javaOutRoot: " + generatedJavaRoot.toAbsolutePath().normalize() + "\n"
                + "outputRoot: " + outputRoot.toAbsolutePath().normalize() + "\n"
                + "javaRoot  : " + (existingJavaRoot == null ? "(未設定)" : existingJavaRoot.toAbsolutePath().normalize()) + "\n"
                + "searchRoot: " + (searchJavaRoot == null ? "(未設定)" : searchJavaRoot.toAbsolutePath().normalize()) + "\n"
                + "maxTables : " + maxTables + "\n"
                + "similarCode: maxFiles=" + maxSimilarFiles + ", maxChars=" + maxSimilarCodeChars
                + ", scanLimit=" + searchMaxFiles + "\n"
                + "reviewRequired: " + reviewRequired;
    }
}
