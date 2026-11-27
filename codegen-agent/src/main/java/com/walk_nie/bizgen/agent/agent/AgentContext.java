package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.llm.LlmClient;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.reuse.ReuseSelection;
import com.walk_nie.bizgen.agent.semantic.SemanticSearchIntent;
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
    private final List<ReuseSelection> selectedReuses = new ArrayList<ReuseSelection>();
    /** モデルが semantic_search で自ら立てた意味解析の検索意図（監査用）。 */
    private final List<SemanticSearchIntent> semanticSearches = new ArrayList<SemanticSearchIntent>();
    /** モデルが load_table_schema で実際に読んだ表名（監査・手レビュー用）。 */
    private final java.util.Set<String> selectedTableKeys = new LinkedHashSet<String>();
    private final List<String> selectedTables = new ArrayList<String>();
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

    /** ツールで確定した再利用テンプレートを記録する（監査・最終検証用）。 */
    public void recordReuse(ReuseSelection selection) {
        if (selection != null) {
            selectedReuses.add(selection);
        }
    }

    public List<ReuseSelection> selectedReuses() {
        return Collections.unmodifiableList(selectedReuses);
    }

    /** モデルが意味解析して自ら発行した検索意図を記録する（人工レビュー用）。 */
    public void recordSemanticSearch(SemanticSearchIntent intent) {
        if (intent != null) {
            semanticSearches.add(intent);
        }
    }

    public List<SemanticSearchIntent> semanticSearches() {
        return Collections.unmodifiableList(semanticSearches);
    }

    /** モデルが load_table_schema で参照した表を記録する（監査・手レビュー用）。 */
    public void recordTables(java.util.Collection<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name != null && selectedTableKeys.add(name)) {
                selectedTables.add(name);
            }
        }
    }

    public List<String> selectedTables() {
        return Collections.unmodifiableList(selectedTables);
    }

    /** 編集提案を人間向け表示として記録する（LLM への tool 結果には含めない）。 */
    public void recordProposalForHuman(String text) {
        this.lastProposalForHuman = text == null ? "" : text;
    }

    public String lastProposalForHuman() {
        return lastProposalForHuman;
    }
}
