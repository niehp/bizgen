package com.walk_nie.bizgen.agent.pipeline.stage;

import com.walk_nie.bizgen.agent.generator.sql.SqlGenState;
import com.walk_nie.bizgen.agent.generator.sql.TableSchema;
import com.walk_nie.bizgen.agent.md.MdRepository;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.pipeline.Stage;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
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
        List<String> names = state.request().tables();
        if (names.isEmpty()) {
            throw new IllegalStateException("使用する表を 1 つ以上指定してください (--tables)");
        }
        int max = context.config().maxTables();
        if (names.size() > max) {
            throw new IllegalStateException("表の指定数 " + names.size() + " が上限 " + max
                    + " を超えています。SQL で使用する表だけを指定してください。");
        }
        Set<String> seen = new HashSet<String>();
        for (String name : names) {
            if (!seen.add(name)) {
                continue;
            }
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
        state.advance(SqlGenState.Phase.TABLES_LOADED);
        return state;
    }

    private String available(MdRepository repository) {
        try {
            return StrUtil.trimToEmpty(repository.schemaNames().toString());
        } catch (IOException e) {
            return "(取得失敗)";
        }
    }
}
