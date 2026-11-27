package com.walk_nie.bizgen.agent.pipeline.stage.flow;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolRegistry;
import com.walk_nie.bizgen.agent.agent.tool.FindApiTool;
import com.walk_nie.bizgen.agent.agent.tool.InjectTermsTool;
import com.walk_nie.bizgen.agent.agent.tool.ListDesignDocsTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadDesignDocTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadSpecTool;
import com.walk_nie.bizgen.agent.agent.tool.LookupVariableTool;
import com.walk_nie.bizgen.agent.agent.tool.ReadJavaFileTool;
import com.walk_nie.bizgen.agent.agent.tool.SearchSimilarCodeTool;
import com.walk_nie.bizgen.agent.agent.tool.SemanticSearchTool;
import com.walk_nie.bizgen.agent.agent.tool.TranslateTermsTool;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.variable.VariableTable;

/**
 * flow-gen の各 LLM ステージが共通して使う function tool 白名单と共有手順。
 * 変数・API・用語・日英対訳・類似コード・テンプレートはすべてモデルがツールで取りに行く。
 */
public final class FlowAgentSupport {

    private FlowAgentSupport() {
    }

    public static ToolRegistry tools(AgentConfig config, boolean semanticEnabled) {
        ToolRegistry registry = new ToolRegistry()
                .register(new LookupVariableTool())
                .register(new TranslateTermsTool())
                .register(new InjectTermsTool())
                .register(new LoadSpecTool())
                .register(new ListDesignDocsTool())
                .register(new LoadDesignDocTool())
                .register(new FindApiTool())
                .register(new SearchSimilarCodeTool())
                .register(new ReadJavaFileTool());
        if (semanticEnabled) {
            registry.register(new SemanticSearchTool());
        }
        return registry;
    }

    public static AgentContext agentContext(PipelineContext pipeline, VariableTable variables) {
        return new AgentContext(pipeline.config(), pipeline.repository(), variables, pipeline.llm());
    }

    /** prompt の {{knowledgeInstruction}} に展開する共通ルール。 */
    public static String knowledgeInstruction(boolean semanticEnabled) {
        StringBuilder out = new StringBuilder();
        out.append("知識はすべて function tool で取得する。事前差し込みされた用語・API・類似コード・spec 資料は存在しない。\n");
        out.append("- `lookup_variable`: {{変数}} の確定値と 6 種命名スタイル（首字母大/小写・大/小驼峰・大/小蛇形）を取得する。未定義値は推測しない。\n");
        out.append("- `translate_terms` / `inject_terms`: 日本語の業務用語・表名・項目名を术语库の対訳だけへ変換する。辞書に無い語は英訳しない。\n");
        out.append("- `load_spec`: `kind=api|dict|template|example` で spec 資料を一覧または名前指定で読む。API シグネチャ・テンプレート・機能例はここから取得する。\n");
        out.append("- `find_api`: 呼び出す既存 API の実在シグネチャを確定する。返らなかった API は呼ばない。\n");
        out.append("- `search_similar_code`");
        if (semanticEnabled) {
            out.append(" / `semantic_search`（意味解析から導いた purpose / keywords を指定）");
        }
        out.append(": 既存 Java の本物の実装を探す。無命中なら流用せず、仕様どおりに新規コードを書く。\n");
        out.append("- `list_design_docs` → `load_design_doc`: 必要な設計規約だけを読む。\n");
        return out.toString();
    }
}
