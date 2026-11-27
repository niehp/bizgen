package com.walk_nie.bizgen.agent.generator.sql;

import com.walk_nie.bizgen.agent.agent.AgentContext;
import com.walk_nie.bizgen.agent.agent.ToolRegistry;
import com.walk_nie.bizgen.agent.agent.tool.InjectTermsTool;
import com.walk_nie.bizgen.agent.agent.tool.ListDesignDocsTool;
import com.walk_nie.bizgen.agent.agent.tool.ListTablesTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadDesignDocTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadSpecTool;
import com.walk_nie.bizgen.agent.agent.tool.LoadTableSchemaTool;
import com.walk_nie.bizgen.agent.agent.tool.LookupVariableTool;
import com.walk_nie.bizgen.agent.agent.tool.TranslateTermsTool;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.variable.VariableTable;

/**
 * SQL 生成の flow ステージが共通して使う function tool 白名单と共有手順。
 * 表構造・术语・日英対訳辞書・変数・テンプレートはすべてモデルがツールで取りに行く。
 */
public final class SqlFlowSupport {

    private SqlFlowSupport() {
    }

    /** SQL 生成でモデルに公開するツール。 */
    public static ToolRegistry tools(AgentConfig config) {
        return new ToolRegistry()
                .register(new ListTablesTool())
                .register(new LoadTableSchemaTool())
                .register(new LookupVariableTool())
                .register(new TranslateTermsTool())
                .register(new InjectTermsTool())
                .register(new LoadSpecTool())
                .register(new ListDesignDocsTool())
                .register(new LoadDesignDocTool());
    }

    public static AgentContext agentContext(PipelineContext pipeline, VariableTable variables) {
        return new AgentContext(pipeline.config(), pipeline.repository(), variables, pipeline.llm());
    }

    /** prompt の {{knowledgeInstruction}} に展開する共通ルール。 */
    public static String knowledgeInstruction() {
        StringBuilder out = new StringBuilder();
        out.append("表構造・术语・日英対訳辞書・変数・テンプレートはすべて function tool で取得する。"
                + "事前に差し込まれた表構造や対訳表は存在しない。\n");
        out.append("- `list_tables`: 利用可能な表（日本語表名）を一覧する。\n");
        out.append("- `load_table_schema`: SQL に実際に登場する表だけを指定して表構造を読む。"
                + "日本語名→英語物理名・型・主キー・NOT NULL・SQL変数・リレーションが載っている。"
                + "上限（gen.maxTables）を超える数を渡すとエラーになるので、必要な表だけ選ぶ。"
                + "表構造に無いカラム・テーブルは絶対に使わない。\n");
        out.append("- `translate_terms` / `inject_terms`: 日本語の表名・項目名・業務用語を术语库の対訳だけへ変換する。"
                + "辞書に無い語は勝手に英訳せず、表構造に載っている英語物理名を優先する。\n");
        out.append("- `lookup_variable`: {{変数}} の確定値と 6 種命名スタイル"
                + "（首字母大/小写・大/小驼峰・大/小蛇形）を取得する。未定義値は推測しない。\n");
        out.append("- `load_spec`: kind=template で SQL prompt 雛形、kind=dict で术语辞書、"
                + "kind=example で機能例を読む。\n");
        out.append("- `list_design_docs` → `load_design_doc`: 設計上の規約が必要なら読む。\n");
        out.append("知識は取得したものだけを根拠にし、モデルが勝手にテーブル・カラム・値を想像しないこと。\n");
        return out.toString();
    }
}
