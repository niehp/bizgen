package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;

import java.io.IOException;

/** Java 生成フレームワークに組み込んだ Step 1: 業務词汇を抽出し术语库から日英対応を引く（決定論的）。 */
public final class ExtractTermsStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "04-extract-terms";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.VARIABLES_RESOLVED);
        try {
            CodegenSupport.TermAnalysis analysis = CodegenSupport.analyzeTerms(
                    state.resolved().featureBody(), context.config());
            state.setTerms(analysis.entries());
            state.setTermsText(analysis.text());
            state.advance(JavaGenState.Phase.TERMS_EXTRACTED);
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("术语库 の読み込みに失敗: "
                    + context.config().termRoot(), e);
        }
    }
}
