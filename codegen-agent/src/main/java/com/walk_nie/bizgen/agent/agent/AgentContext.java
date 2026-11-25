package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.llm.LlmClient;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Agent が利用する依存をまとめたコンテキスト。 */
public final class AgentContext {

    private final AgentConfig config;
    private final MdRepository repository;
    private final VariableTable variables;
    private final LlmClient llm;
    private final Set<String> selectedApiKeys = new LinkedHashSet<String>();
    private final List<ApiCatalog.ApiEntry> selectedApis = new ArrayList<ApiCatalog.ApiEntry>();
    /** 編集提案のうち、人間だけに見せる承認トークンを含む表示文。モデルへは渡さない。 */
    private String lastProposalForHuman = "";

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

    /** モデルが find_api で実際に参照した API を記録する（監査・手レビュー用）。 */
    public void recordApis(List<ApiCatalog.ApiEntry> entries) {
        if (entries == null) {
            return;
        }
        for (ApiCatalog.ApiEntry entry : entries) {
            if (selectedApiKeys.add(entry.className() + "#" + entry.signature())) {
                selectedApis.add(entry);
            }
        }
    }

    public List<ApiCatalog.ApiEntry> selectedApis() {
        return Collections.unmodifiableList(selectedApis);
    }

    /** 編集提案を人間向け表示として記録する（LLM への tool 結果には含めない）。 */
    public void recordProposalForHuman(String text) {
        this.lastProposalForHuman = text == null ? "" : text;
    }

    public String lastProposalForHuman() {
        return lastProposalForHuman;
    }
}
