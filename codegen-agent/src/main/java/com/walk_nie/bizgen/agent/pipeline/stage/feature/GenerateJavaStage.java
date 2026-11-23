package com.walk_nie.bizgen.agent.pipeline.stage.feature;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.feature.FeatureGenState;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.Collections;

/** Step 3: 機能 md + 术语 + API + 日本語開発規約を structured prompt にしてコードを生成する（LLM）。 */
public final class GenerateJavaStage implements Stage<FeatureGenState, FeatureGenState> {

    @Override
    public String id() {
        return "03-generate-java";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public FeatureGenState execute(FeatureGenState state, PipelineContext context) {
        state.assertPhase(FeatureGenState.Phase.APIS_MATCHED);
        AgentConfig config = context.config();
        try {
            VariableTable variables = new VariableTable();
            variables.put("featureBody", state.featureBody());
            variables.put("termsText", CodegenSupport.termsText(state.terms()));
            variables.put("apisText", CodegenSupport.apisText(state.apis()));
            variables.put("designText", state.designText());
            variables.put("packageName", state.packageName());
            variables.put("className", state.className());
            variables.put("businessWords", String.join(", ", state.businessWords()));
            String prompt = variables.resolveText(context.repository().prompt("feature-codegen"));

            LlmRequest request = new LlmRequest(config.model(),
                    Collections.singletonList(LlmMessage.user(prompt)),
                    config.temperature(), config.maxTokens(), config.seed());
            LlmResponse response = context.ask(request);
            state.setRawResponse(response.content());
            state.advance(FeatureGenState.Phase.GENERATED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("feature-codegen prompt の読み込みに失敗", e);
        }
    }
}
