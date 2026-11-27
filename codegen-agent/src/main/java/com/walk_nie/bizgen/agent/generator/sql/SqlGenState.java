package com.walk_nie.bizgen.agent.generator.sql;

import java.util.ArrayList;
import java.util.List;

/** SQL 生成パイプラインの型付き状態。 */
public final class SqlGenState {

    public enum Phase {
        INIT, TABLES_LOADED, PROMPT_READY, FLOW_READY, GENERATED, WRITTEN
    }

    private final SqlGenRequest request;
    private Phase phase = Phase.INIT;
    private TableDictionary dictionary = new TableDictionary();
    /** flow 式で prompt / lookup_variable へ渡す変数（コード側で確定）。 */
    private com.walk_nie.bizgen.agent.variable.VariableTable flowVariables =
            new com.walk_nie.bizgen.agent.variable.VariableTable();
    private String renderedPrompt;
    /** flow 式でモデルが function tool で読み込んだ表（監査用）。 */
    private List<String> usedTables = new ArrayList<String>();
    private String sql;
    private SqlGenResult result;
    private com.walk_nie.bizgen.agent.agent.AgentResult agentResult;

    public SqlGenState(SqlGenRequest request) {
        this.request = request;
    }

    public SqlGenRequest request() {
        return request;
    }

    public Phase phase() {
        return phase;
    }

    public void assertPhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("期待フェーズ " + expected + " に対し現在 " + phase);
        }
    }

    public void advance(Phase next) {
        this.phase = next;
    }

    public TableDictionary dictionary() {
        return dictionary;
    }

    public com.walk_nie.bizgen.agent.variable.VariableTable flowVariables() {
        return flowVariables;
    }

    public String renderedPrompt() {
        return renderedPrompt;
    }

    public void setRenderedPrompt(String renderedPrompt) {
        this.renderedPrompt = renderedPrompt;
    }

    public List<String> usedTables() {
        return usedTables;
    }

    public void setUsedTables(List<String> usedTables) {
        this.usedTables = usedTables == null ? new ArrayList<String>() : new ArrayList<String>(usedTables);
    }

    public String sql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public SqlGenResult result() {
        return result;
    }

    public void setResult(SqlGenResult result) {
        this.result = result;
    }

    public com.walk_nie.bizgen.agent.agent.AgentResult agentResult() {
        return agentResult;
    }

    public void setAgentResult(com.walk_nie.bizgen.agent.agent.AgentResult agentResult) {
        this.agentResult = agentResult;
    }
}
