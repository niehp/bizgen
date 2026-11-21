package com.walk_nie.bizgen.agent.pipeline;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/** prompt md の読み込みと変数展開、LLM リクエスト生成を共通化する。 */
public final class PromptRenderer {

    private PromptRenderer() {
    }

    /** prompt md を読み込み、渡された変数で展開する。 */
    public static String render(MdRepository repository, String promptName, Map<String, ?> variables)
            throws IOException {
        VariableTable table = new VariableTable();
        table.putAll(variables);
        return table.resolveText(repository.prompt(promptName));
    }

    /** 展開済み prompt から LLM リクエストを生成する。 */
    public static LlmRequest request(AgentConfig config, String renderedPrompt) {
        return new LlmRequest(config.model(),
                Collections.singletonList(LlmMessage.user(renderedPrompt)),
                config.temperature(), config.maxTokens(), config.seed());
    }
}
