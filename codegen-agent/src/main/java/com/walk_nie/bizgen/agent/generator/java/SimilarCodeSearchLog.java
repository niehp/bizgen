package com.walk_nie.bizgen.agent.generator.java;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 類似コードが見つからなかったときの入力ログ。
 * 「見つからない」という事実と、検索に使った入力を必ず残す（要件: 無命中時は入力をログする）。
 * ログ失敗は生成処理を止めないが、標準エラーには必ず知らせる。
 */
public final class SimilarCodeSearchLog {

    private static final int MAX_INPUT_CHARS = 4000;

    private SimilarCodeSearchLog() {
    }

    public static void recordNoHit(AgentConfig config, String scope, String featureTitle,
                                   String inputText, SimilarCodeResult result) {
        if (config == null || result == null) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("[similar-code][no-hit] scope=").append(scope == null ? "-" : scope)
                .append(" feature=").append(featureTitle == null ? "-" : featureTitle)
                .append(" keywords=").append(result.keywords())
                .append(" root=").append(result.root())
                .append(" scannedFiles=").append(result.scannedFiles())
                .append('\n');
        message.append("input:\n").append(clip(inputText)).append('\n');

        System.out.print(message.toString());
        try {
            Path log = config.outputRoot().resolve("logs").resolve("similar-code.log");
            Path parent = log.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(log, StrUtil.normalizeNewline(message.toString()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[similar-code] ログ書き出しに失敗: " + e.getMessage());
        }
    }

    private static String clip(String text) {
        String value = text == null ? "" : text;
        if (value.length() <= MAX_INPUT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_INPUT_CHARS) + "\n...（省略）";
    }
}
