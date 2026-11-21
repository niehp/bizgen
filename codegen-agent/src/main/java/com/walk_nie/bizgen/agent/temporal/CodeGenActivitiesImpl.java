package com.walk_nie.bizgen.agent.temporal;

import com.walk_nie.bizgen.agent.*;
import com.walk_nie.bizgen.agent.ast.AstPatcher;
import com.walk_nie.bizgen.agent.ast.AstPatcher.PatchResult;
import com.walk_nie.bizgen.agent.ast.CompileGate;
import com.walk_nie.bizgen.agent.ast.CompileGate.CompileResult;
import com.walk_nie.bizgen.agent.index.ApiWhitelist;
import com.walk_nie.bizgen.agent.temporal.Dtos.*;
import io.temporal.activity.Activity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Activity 实现：把外部调用与副作用集中在这里，Workflow 主体保持纯确定性。 */
public final class CodeGenActivitiesImpl implements CodeGenActivities {

    private final VllmClient llm;
    private final AstPatcher patcher = new AstPatcher();
    private final CompileGate compiler = new CompileGate();
    private final Path sqliteIndex;

    public CodeGenActivitiesImpl(VllmClient llm, Path sqliteIndex) {
        this.llm = llm;
        this.sqliteIndex = sqliteIndex;
    }

    @Override
    public SemanticMapping mapSpec(String specText) {
        try {
            String schema = RecordSchema.toJsonSchema(SemanticMapping.class);
            String json = llm.guidedJson(SYSTEM, specText, schema);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, Object>) Json.parse(json);
            return RecordSchema.fromMap(map, SemanticMapping.class);
        } catch (Exception e) {
            throw Activity.wrap(e);
        }
    }

    @Override
    public CodeDraft draftPatch(SemanticMapping mapping, String targetSource) {
        try {
            String schema = RecordSchema.toJsonSchema(CodeDraft.class);
            String prompt = "対象メソッド: " + mapping.targetMethod()
                    + "\n使用してよいAPI: " + String.join(", ", mapping.apiCalls())
                    + "\n既存ソース:\n" + targetSource;
            String json = llm.guidedJson(SYSTEM, prompt, schema);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, Object>) Json.parse(json);
            return RecordSchema.fromMap(map, CodeDraft.class);
        } catch (Exception e) {
            throw Activity.wrap(e);
        }
    }

    @Override
    public PatchResultView renderPatch(String targetSource, CodeDraft draft) {
        try {
            PatchResult r = patcher.apply(targetSource, draft);
            return new PatchResultView(r.source(), r.appliedHunks());
        } catch (Exception e) {
            throw Activity.wrap(e);
        }
    }

    @Override
    public ApiWhitelistView loadWhitelist() {
        try {
            ApiWhitelist wl = new ApiWhitelist(sqliteIndex);
            return new ApiWhitelistView(new ArrayList<>(wl.allowedSnapshot()));
        } catch (Exception e) {
            throw Activity.wrap(e);
        }
    }

    @Override
    public void validateApis(List<String> apiCalls) {
        try {
            ApiWhitelist wl = new ApiWhitelist(sqliteIndex);
            List<String> bad = wl.reject(apiCalls);
            if (!bad.isEmpty()) {
                throw new SecurityException("白名单外 API，拒绝继续: " + bad);
            }
        } catch (Exception e) {
            throw Activity.wrap(e);
        }
    }

    @Override
    public CompileResultView compileCode(String className, String source) {
        CompileResult r = compiler.compile(className, source);
        return new CompileResultView(r.ok(), r.diagnostics());
    }

    @Override
    public void recordApproval(String runId, boolean approved, String reviewer, String comment) {
        // 演示实现：打印审计日志。生产应写入审批表（ PostgreSQL ）并与人工作业单关联。
        System.out.println("[审批记录] runId=" + runId + " approved=" + approved
                + " reviewer=" + reviewer + " comment=" + comment);
    }

    private static final String SYSTEM = """
            あなたは日系SIのコード生成アシスタントです。必ず指定JSONスキーマに従ってください。
            推論は reasoning に自由記述し、その他はスキーマ制約に従うこと。
            """;
}
