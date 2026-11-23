package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaDraft;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.generator.java.ResolvedFeature;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;

/** 日本語 Javadoc を整える（モデル担当）。ロジック変更はプロンプト側で禁止している。 */
public final class PolishJavaStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "10-polish-java";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.DRAFTED);
        ResolvedFeature resolved = state.resolved();
        JavaDraft draft = state.draft();
        try {
            VariableTable variables = new VariableTable();
            variables.put("generatedCode", draft.classCode());
            variables.put("designDocs", resolved.designText());
            String prompt = variables.resolveText(context.repository().prompt("java-polish"));

            AgentConfig config = context.config();
            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);

            String polished = StrUtil.stripCodeFence(response.content());
            if (!StrUtil.isBlank(polished)) {
                state.setDraft(draft.withClassCode(polished));
            }
            state.advance(JavaGenState.Phase.POLISHED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("java-polish prompt の読み込みに失敗", e);
        }
    }
}
