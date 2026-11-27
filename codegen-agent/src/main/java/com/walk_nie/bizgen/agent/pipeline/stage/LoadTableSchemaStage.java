package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.generator.sql.TableSchema;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.KeywordExtractor;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** SQL で使う表の構造だけを読み込む。上限超過は黙って切り捨てずエラーとする（決定論的）。 */
public final class LoadTableSchemaStage implements Stage<SqlGenState, SqlGenState> {

    @Override
    public String id() {
        return "01-load-table-schema";
    }

    @Override
    public boolean llmBacked() {
        return false;
    }

    @Override
    public SqlGenState execute(SqlGenState state, PipelineContext context) {
        state.assertPhase(SqlGenState.Phase.INIT);
        int max = context.config().maxTables();
        List<String> names = new ArrayList<String>(state.request().tables());
        if (names.isEmpty()) {
            names = tablesFromFields(state, context, max);
        }
        if (names.isEmpty()) {
            names = detectTables(state, context, max);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("使用する表を 1 つ以上指定してください (--tables または md の tables)");
        }
        if (names.size() > max) {
            throw new IllegalStateException("表の指定数 " + names.size() + " が上限 " + max
                    + " を超えています。SQL で使用する表だけを指定してください。");
        }
        Set<String> seen = new HashSet<String>();
        for (String name : names) {
            if (!seen.add(name)) {
                continue;
            }
            addSchema(state, context, name);
        }
        state.advance(SqlGenState.Phase.TABLES_LOADED);
        return state;
    }

    /** md の対象フィールドが「表.フィールド」形式なら、その表を先に採用する。 */
    private List<String> tablesFromFields(SqlGenState state, PipelineContext context, int max) {
        List<String> result = new ArrayList<String>();
        for (String raw : state.request().fields()) {
            String token = raw == null ? "" : raw.trim().replace("`", "");
            int dot = token.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String prefix = token.substring(0, dot).trim();
            String schemaName = resolveSchemaName(prefix, context.repository());
            if (schemaName != null && !result.contains(schemaName)) {
                result.add(schemaName);
                if (result.size() >= max) {
                    break;
                }
            }
        }
        return result;
    }

    private String resolveSchemaName(String prefix, MdRepository repository) {
        try {
            for (String name : repository.schemaNames()) {
                if (name.equalsIgnoreCase(prefix)) {
                    return name;
                }
                TableSchema schema = TableSchema.parse(repository.schema(name), name);
                if (prefix.equalsIgnoreCase(schema.japaneseName())
                        || prefix.equalsIgnoreCase(schema.physicalName())) {
                    return name;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("表構造の名前解決に失敗しました: " + prefix, e);
        }
        return null;
    }

    /** 明示指定が無い場合、説明文と雛形に出てくる日本語表名だけを決定的に選ぶ。 */
    private List<String> detectTables(SqlGenState state, PipelineContext context, int max) {
        List<String> result = new ArrayList<String>();
        String haystack = state.request().description() + "\n" + state.request().sqlShape();
        try {
            for (String name : context.repository().schemaNames()) {
                String markdown = context.repository().schema(name);
                TableSchema schema = TableSchema.parse(markdown, name);
                if (KeywordExtractor.contains(haystack, schema.japaneseName())
                        || KeywordExtractor.contains(haystack, schema.physicalName())) {
                    result.add(name);
                    if (result.size() >= max) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("表構造の自動選択に失敗しました", e);
        }
        return result;
    }

    private void addSchema(SqlGenState state, PipelineContext context, String name) {
        try {
            String markdown = context.repository().schema(name);
            TableSchema schema = TableSchema.parse(markdown, name);
            state.dictionary().add(schema);
        } catch (MdRepository.MdException e) {
            throw new IllegalStateException("表構造 md が見つかりません: " + name
                    + "\n利用可能: " + available(context.repository()), e);
        } catch (IOException e) {
            throw new IllegalStateException("表構造 md の読み込みに失敗: " + name, e);
        }
    }

    private String available(MdRepository repository) {
        try {
            return StrUtil.trimToEmpty(repository.schemaNames().toString());
        } catch (IOException e) {
            return "(取得失敗)";
        }
    }
}
