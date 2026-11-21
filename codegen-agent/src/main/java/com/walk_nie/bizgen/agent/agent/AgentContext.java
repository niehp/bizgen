package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.llm.LlmClient;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.variable.VariableTable;

/** Agent が利用する依存をまとめたコンテキスト。 */
public final class AgentContext {

    private final AgentConfig config;
    private final MdRepository repository;
    private final VariableTable variables;
    private final LlmClient llm;

    public AgentContext(AgentConfig config, MdRepository repository,
                        VariableTable variables, LlmClient llm) {
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
}
