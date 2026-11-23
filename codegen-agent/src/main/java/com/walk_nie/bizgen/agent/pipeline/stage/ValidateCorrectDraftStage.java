package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.CodegenSupport;
import com.walk_nie.bizgen.agent.generator.java.JavaChangeIntent;
import com.walk_nie.bizgen.agent.generator.java.JavaDraft;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.JavaCodeExtractor;
import com.walk_nie.bizgen.agent.util.JavaCodeValidator;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Java 生成フレームワークに組み込んだ Step 4: 纯 Java コードを抽出し静的検証する。
 * 違反があれば自己修正を 1 回だけ依頼し、以降はループせず警告として残す。
 */
public final class ValidateCorrectDraftStage implements Stage<JavaGenState, JavaGenState> {

    private static final Pattern TYPE = Pattern.compile(
            "(?:public\\s+)?(?:final\\s+)?(?:class|interface|enum)\\s+\\w+");

    @Override
    public String id() {
        return "11-validate-correct";
    }

    @Override
    public boolean llmBacked() {
        return true;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.POLISHED);
        JavaDraft draft = state.draft();
        boolean fullClass = state.intent().intent() == JavaChangeIntent.NEW_FILE;

        String code = JavaCodeExtractor.extract(draft.classCode());
        List<String> problems = inspect(code, fullClass, state.terms());

        if (!problems.isEmpty()) {
            try {
                String packageName = state.resolved().variable("packageName", "");
                String className = state.semanticMap().className();
                CodegenSupport.Correction correction = CodegenSupport.correct(
                        "java-self-correct", code, problems, fullClass,
                        state.terms(), state.apis(), packageName, className, context);
                if (correction.invoked()) {
                    state.setSelfCorrected(true);
                    code = correction.code();
                    problems = inspect(code, fullClass, state.terms());
                }
            } catch (IOException e) {
                throw new IllegalStateException("java-self-correct prompt の読み込みに失敗", e);
            }
        }

        if (StrUtil.isBlank(code)) {
            throw new IllegalStateException("有効な Java コードが生成結果から抽出できませんでした");
        }
        state.setDraft(draft.withClassCode(code));
        state.setViolations(problems);
        state.advance(JavaGenState.Phase.VALIDATED);
        return state;
    }

    private static List<String> inspect(String code, boolean fullClass,
                                        List<com.walk_nie.bizgen.agent.term.TermDictionary.TermEntry> terms) {
        List<String> problems = new ArrayList<String>(
                JavaCodeValidator.validate(code, terms, false));
        if (fullClass) {
            if (!TYPE.matcher(code == null ? "" : code).find()) {
                problems.add("class / interface / enum 宣言がありません");
            }
        } else if (CodegenSupport.extractMethods(code).isEmpty()) {
            problems.add("追加／修正するメソッドが検出されませんでした");
        }
        return problems;
    }
}
