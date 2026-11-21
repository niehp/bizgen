package com.walk_nie.bizgen.agent.mdgen;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * AI Agent の設定（LLM 情報は設定ファイルから取得する、100% 確定的）。
 *
 * <p>既定ファイル：{@code config/agent.properties}（作業ディレクトリ基準）。
 * {@code --config <file>} で変更できる。ファイルが無い場合は内蔵既定値で動く
 * （明示指定したファイルが無い場合は例外＝黙って既定値に逃げない）。
 *
 * <p>サポートするキー（未知キーは即拒否：タイポで既定値に逃げるのを防ぐ）：
 * <ul>
 *   <li>{@code llm.mode}      … mock / live（live でローカル vLLM を呼ぶ）</li>
 *   <li>{@code llm.base-url}  … vLLM のベース URL</li>
 *   <li>{@code llm.model}     … モデル名</li>
 *   <li>{@code common.dir}    … 共通 md フォルダ</li>
 *   <li>{@code table.dir}     … 表構造 md フォルダ（1 ファイル 1 テーブル）</li>
 * </ul>
 *
 * <p>各項目が設定ファイルに無い場合は環境変数（VLLM_BASE_URL / VLLM_MODEL /
 * BIZGEN_COMMON_MD_DIR / BIZGEN_TABLE_MD_DIR）で補い、それも無ければ内蔵既定値を使う。
 * 設定ファイルの値が環境変数より優先する（設定ファイルが単一真源）。
 */
public record AgentConfig(
        LlmMode mode,
        String baseUrl,
        String model,
        Path commonDir,
        Path tableDir,
        String source
) {
    public enum LlmMode { MOCK, LIVE }

    public static final String DEFAULT_PATH = "config/agent.properties";

    private static final Set<String> KNOWN_KEYS =
            Set.of("llm.mode", "llm.base-url", "llm.model", "common.dir", "table.dir");

    private static final String DEFAULT_URL = "http://127.0.0.1:8000";
    private static final String DEFAULT_MODEL = "Qwen/Qwen2.5-Coder-14B-Instruct";
    private static final String DEFAULT_TABLE_DIR = "specs/tables";

    public boolean live() { return mode == LlmMode.LIVE; }

    /** path=null なら既定ファイルを探し、無ければ内蔵既定値を返す。 */
    public static AgentConfig load(Path path) throws IOException {
        boolean explicit = path != null;
        Path p = (explicit ? path : Path.of(DEFAULT_PATH)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            if (explicit) throw new IllegalArgumentException("設定ファイルが無い: " + p);
            return new AgentConfig(LlmMode.MOCK, DEFAULT_URL, DEFAULT_MODEL,
                    Path.of(CommonSpecScanner.DEFAULT_DIR), Path.of(DEFAULT_TABLE_DIR),
                    "内蔵既定値（" + p + " は無し）");
        }

        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        for (String key : props.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException("不明な設定キー「" + key + "」: " + p
                        + "（許可: " + KNOWN_KEYS + "）");
            }
        }

        String modeRaw = props.getProperty("llm.mode", "mock").strip();
        LlmMode mode = switch (modeRaw.toLowerCase(Locale.ROOT)) {
            case "mock" -> LlmMode.MOCK;
            case "live" -> LlmMode.LIVE;
            default -> throw new IllegalArgumentException(
                    "llm.mode は mock または live のみ: " + modeRaw + " @" + p);
        };

        String baseUrl = pick(props, "llm.base-url", "VLLM_BASE_URL", DEFAULT_URL);
        String model = pick(props, "llm.model", "VLLM_MODEL", DEFAULT_MODEL);
        Path commonDir = Path.of(pick(props, "common.dir", "BIZGEN_COMMON_MD_DIR",
                CommonSpecScanner.DEFAULT_DIR));
        Path tableDir = Path.of(pick(props, "table.dir", "BIZGEN_TABLE_MD_DIR", DEFAULT_TABLE_DIR));

        return new AgentConfig(mode, baseUrl, model, commonDir, tableDir, p.toString());
    }

    private static String pick(Properties props, String key, String envKey, String fallback) {
        String v = props.getProperty(key);
        if (v != null && !v.isBlank()) return v.strip();
        String env = System.getenv(envKey);
        return env == null || env.isBlank() ? fallback : env.strip();
    }
}
