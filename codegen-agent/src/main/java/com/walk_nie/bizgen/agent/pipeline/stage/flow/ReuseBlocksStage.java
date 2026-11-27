package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.flow.CodePlan;
import com.walk_nie.bizgen.agent.generator.flow.FlowGenState;
import com.walk_nie.bizgen.agent.generator.java.JavaGenState;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.reuse.ReuseResolver;
import com.walk_nie.bizgen.agent.reuse.ReuseSelection;
import com.walk_nie.bizgen.agent.reuse.ReusableBlock;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * flow-gen の決定論的再利用ステージ。
 *
 * <p>機能ブロックごとに spec/block のテンプレートをスコアで選び、採用できるものは
 * モデルを呼ばずにブロックを完了させる。API_CALL は API 库の実在シグネチャが無ければ採用しない。
 * 未採用のブロックだけが後段の function tool / モデル生成に回る。</p>
 */
public final class ReuseBlocksStage implements Stage<FlowGenState, FlowGenState> {

    @Override
    public String id() {
        return "flow-01a-reuse-blocks";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public FlowGenState execute(FlowGenState state, PipelineContext context) {
        state.assertPhase(FlowGenState.Phase.PLANNED);
        AgentConfig config = context.config();
        if (config.reuseEnabled()) {
            ReuseResolver resolver = new ReuseResolver(config);
            Map<String, String> variables = variables(state.java(), context);
            try {
                for (CodePlan.Block block : state.plan().blocks()) {
                    if (block.status() == CodePlan.Status.DONE) {
                        continue;
                    }
                    String query = ReuseResolver.describeQuery(
                            block.description(), block.apiQuery())
                            + " " + state.plan().flow();
                    ReuseSelection selection = resolver.resolve(query, variables);
                    if (selection == null) {
                        continue;
                    }
                    String signature = selection.kind() == ReusableBlock.Kind.API_CALL
                            ? selection.apiSignature() : "";
                    block.markDone(selection.code(), signature);
                    state.recordReuse(selection);
                    if (!signature.isEmpty()) {
                        state.recordToolApi(signature);
                    }
                    state.countBlockRound();
                }
            } catch (IOException e) {
                throw new IllegalStateException("再利用ブロックの読み込みに失敗: "
                        + config.blockRoot(), e);
            }
        }
        state.advance(FlowGenState.Phase.REUSED);
        return state;
    }

    private static Map<String, String> variables(JavaGenState java, PipelineContext context) {
        Map<String, String> variables = new LinkedHashMap<String, String>();
        variables.putAll(context.variables().snapshot());
        variables.putAll(java.resolved().variables().snapshot());
        return variables;
    }
}
