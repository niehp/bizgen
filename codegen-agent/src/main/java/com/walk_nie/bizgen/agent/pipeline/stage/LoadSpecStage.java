package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.spec.SpecCatalog;

import java.io.IOException;

/**
 * ステップ 7: 要件理解を助ける 4 類の資料を読み込み、変数展開して prompt 用 Text にする。
 *
 *   spec/api/*.md       利用可能な API シグネチャ
 *   spec/dict/*.md      术语
 *   spec/template/*.md  テンプレート
 *   spec/example/*.md   機能インスタンス
 *
 * 読み込み・並び順・変数展開・上限チェックはすべてコード側（決定論的）。
 * この Stage は位相を進めない。変数展開直後に差し込むことで既存 Stage の遷移を壊さない。
 */
public final class LoadSpecStage implements Stage<JavaGenState, JavaGenState> {

    @Override
    public String id() {
        return "03b-load-spec";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public JavaGenState execute(JavaGenState state, PipelineContext context) {
        state.assertPhase(JavaGenState.Phase.VARIABLES_RESOLVED);
        AgentConfig config = context.config();
        try {
            SpecCatalog catalog = SpecCatalog.load(
                    config.specRoot(),
                    state.resolved().variables(),
                    config.specMaxChars());
            state.setSpecCatalog(catalog);
            state.setSpecText(catalog.isEmpty()
                    ? "（参考資料なし。spec/api, spec/dict, spec/template, spec/example に md を置くと注入される）\n"
                    : catalog.allText());
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("spec 資料の読み込みに失敗: "
                    + config.specRoot(), e);
        }
    }
}
