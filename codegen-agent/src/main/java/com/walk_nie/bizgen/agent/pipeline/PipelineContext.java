package com.walk_nie.bizgen.agent.pipeline;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.llm.LlmClient;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** パイプライン全体で共有する依存と実行トレース。 */
public final class PipelineContext {

    private final AgentConfig config;
    private final MdRepository repository;
    private final VariableTable variables;
    private final LlmClient llm;
    private final List<StageTrace> traces = new ArrayList<StageTrace>();

    private LlmRequest lastRequest;

    public PipelineContext(AgentConfig config, MdRepository repository, VariableTable variables, LlmClient llm) {
        this.config = config;
        this.repository = repository;
        this.variables = variables;
        this.llm = llm;
    }

    public AgentConfig config() {
        return config;
    }

    public MdRepository repository() {
        return repository;
    }

    public VariableTable variables() {
        return variables;
    }

    public LlmClient llm() {
        return llm;
    }

    /** LLM 呼び出しを 1 箇所に集約し、監査用に最後のリクエストを保持する。 */
    public LlmResponse ask(LlmRequest request) {
        this.lastRequest = request;
        return llm.chat(request);
    }

    public LlmRequest lastRequest() {
        return lastRequest;
    }

    public void trace(StageTrace trace) {
        traces.add(trace);
    }

    public List<StageTrace> traces() {
        return Collections.unmodifiableList(traces);
    }
}
