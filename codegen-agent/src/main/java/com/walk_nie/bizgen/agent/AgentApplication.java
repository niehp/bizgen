package com.walk_nie.bizgen.agent;

import com.walk_nie.bizgen.agent.cli.Cli;
import com.walk_nie.bizgen.agent.config.AgentConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Paths;

/**
 * bizgen-agent エントリポイント。
 * ローカル LLM と md 設計資料を用い、決定論的なオーケストレーションで Java/SQL を生成する。
 */
public final class AgentApplication {

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        String configPath = System.getProperty("agent.config", "config/agent-config.properties");
        try {
            AgentConfig config = AgentConfig.load(Paths.get(configPath));
            HttpSafety.check(config, out);
            new Cli(config, out).loop();
        } catch (IOException e) {
            out.println("[FATAL] 起動に失敗しました: " + e.getMessage());
            System.exit(1);
        } catch (RuntimeException e) {
            out.println("[FATAL] " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            System.exit(1);
        }
    }

    /** 起動時に設定を検証し、外部送信のリスクを可視化する。 */
    private static final class HttpSafety {
        static void check(AgentConfig config, PrintStream out) {
            com.walk_nie.bizgen.agent.llm.HttpLlmClient.assertLocalOnly(config.endpoint());
            out.println("[SAFE] LLM エンドポイントはローカルホストです: " + config.endpoint());
        }
    }
}
