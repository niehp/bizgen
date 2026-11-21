package com.walk_nie.bizgen.agent.generator.sql;

/** SQL 生成パイプラインの型付き状態。 */
public final class SqlGenState {

    public enum Phase {
        INIT, TABLES_LOADED, PROMPT_READY, GENERATED, WRITTEN
    }

    private final SqlGenRequest request;
    private Phase phase = Phase.INIT;
    private TableDictionary dictionary = new TableDictionary();
    private String renderedPrompt;
    private String sql;
    private SqlGenResult result;

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

    public String renderedPrompt() {
        return renderedPrompt;
    }

    public void setRenderedPrompt(String renderedPrompt) {
        this.renderedPrompt = renderedPrompt;
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
}
