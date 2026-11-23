package com.walk_nie.bizgen.agent.pipeline.stage.feature;

import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.feature.FeatureGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.JavaCodeExtractor;
import com.walk_nie.bizgen.agent.util.JavaCodeValidator;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.util.List;

/**
 * Step 4: 纯 Java コードを抽出し、非法文字・术语違反を检查する。
 * 違反があれば Self-Correction を 1 回だけ依頼する（それ以上はループしない）。
 * 共通処理は CodegenSupport に集約し、java-gen 本流と同じ判定を使う。
 */
public final class ValidateAndCorrectStage implements Stage<FeatureGenState, FeatureGenState> {

    @Override
    public String id() {
        return "04-validate-correct";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public FeatureGenState execute(FeatureGenState state, PipelineContext context) {
        state.assertPhase(FeatureGenState.Phase.GENERATED);

        String code = JavaCodeExtractor.extract(state.rawResponse());
        List<String> problems = JavaCodeValidator.validate(code, state.terms());

        if (!problems.isEmpty()) {
            try {
                CodegenSupport.Correction correction = CodegenSupport.correct(
                        "feature-self-correct", code, problems, true,
                        state.terms(), state.apis(), state.packageName(), state.className(), context);
                if (correction.invoked() && !StrUtil.isBlank(correction.code())) {
                    code = correction.code();
                    state.setRawResponse(correction.rawResponse());
                    state.setCorrected(true);
                    problems = JavaCodeValidator.validate(code, state.terms());
                }
            } catch (IOException e) {
                throw new IllegalStateException("feature-self-correct prompt の読み込みに失敗", e);
            }
        }

        if (StrUtil.isBlank(code) || !code.contains("class ")) {
            throw new IllegalStateException("有効な Java クラスが生成結果から抽出できませんでした");
        }

        state.setCode(code);
        state.setViolations(problems);
        state.advance(FeatureGenState.Phase.VALIDATED);
        return state;
    }
}
