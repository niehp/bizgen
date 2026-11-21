package com.walk_nie.bizgen.agent.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import com.walk_nie.bizgen.agent.SemanticMapping;
import com.walk_nie.bizgen.agent.CodeDraft;
import com.walk_nie.bizgen.agent.temporal.Dtos.ApiWhitelistView;
import com.walk_nie.bizgen.agent.temporal.Dtos.CompileResultView;
import com.walk_nie.bizgen.agent.temporal.Dtos.PatchResultView;

import java.util.List;

/**
 * 所有「不确定」或「有副作用」的操作都放这里——这是 Temporal 确定性的关键：
 * Activity 的结果会记入 workflow 历史，重放时不重跑，因此 LLM 的非确定性不会破坏 replay。
 *
 * Workflow 主体不允许调用 LLM、不读系统时间、不直接访问 IO，只能通过 Activity。
 */
@ActivityInterface
public interface CodeGenActivities {

    /** LLM 叶子节点：语义映射（guided_json 约束），输出强类型 record。 */
    @ActivityMethod
    SemanticMapping mapSpec(String specText);

    /** LLM 叶子节点：代码草稿生成（guided_json 约束）。 */
    @ActivityMethod
    CodeDraft draftPatch(SemanticMapping mapping, String targetSource);

    /** 确定性：把补丁渲染进 AST。 */
    @ActivityMethod
    PatchResultView renderPatch(String targetSource, CodeDraft draft);

    /** 确定性：从索引加载 API 白名单。 */
    @ActivityMethod
    ApiWhitelistView loadWhitelist();

    /** 确定性：校验 apiCalls 是否全在白名单内；不在则抛 Activity 异常触发重试/打回。 */
    @ActivityMethod
    void validateApis(List<String> apiCalls);

    /** 确定性：编译生成的源码；失败抛异常。 */
    @ActivityMethod
    CompileResultView compileCode(String className, String source);

    /** 记录人工审核结论的持久化副作用。 */
    @ActivityMethod
    void recordApproval(String runId, boolean approved, String reviewer, String comment);
}
