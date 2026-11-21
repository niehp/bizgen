package com.walk_nie.bizgen.agent.temporal;

import com.walk_nie.bizgen.agent.CodeDraft;
import com.walk_nie.bizgen.agent.SemanticMapping;
import com.walk_nie.bizgen.agent.temporal.CodeGenWorkflow.CodeGenRequest;
import com.walk_nie.bizgen.agent.temporal.Dtos.ApiWhitelistView;
import com.walk_nie.bizgen.agent.temporal.Dtos.CompileResultView;
import com.walk_nie.bizgen.agent.temporal.Dtos.PatchResultView;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

/**
 * Workflow 实现：确定性编排层。
 *
 * 铁律：这里不调用 LLM、不读系统时间、不做 IO。全部经 Activity。
 * 编译失败由 Activity 抛异常 → Temporal 按重试策略自动重试（确定性重试）；
 * 人工审批用 signal 挂起，可等待任意长时间（数天也不占资源）。
 */
public final class CodeGenWorkflowImpl implements CodeGenWorkflow {

    private final CodeGenActivities activities = Workflow.newActivityStub(
            CodeGenActivities.class,
            io.temporal.activity.ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(io.temporal.common.RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setInitialInterval(Duration.ofSeconds(1))
                            .build())
                    .build());

    private String status = "CREATED";
    private boolean approvalReceived = false;
    private boolean approved = false;
    private String reviewer = "";
    private String approvalComment = "";
    private String runId = "";

    @Override
    public String execute(CodeGenRequest request) {
        runId = Workflow.getInfo().getWorkflowId();

        // 1) LLM 语义映射（叶子节点）
        status = "MAPPING";
        SemanticMapping mapping = activities.mapSpec(request.getSpecText());
        Workflow.getLogger(CodeGenWorkflowImpl.class).info("语义映射完成: " + mapping.targetMethod());

        // 2) 确定性：API 白名单校验（越界即打回）
        status = "VALIDATING_API";
        activities.loadWhitelist(); // 加载快照（演示：真正校验在 validateApis 内）
        activities.validateApis(mapping.apiCalls());

        // 3) LLM 草稿生成（叶子节点）
        status = "DRAFTING";
        CodeDraft draft = activities.draftPatch(mapping, request.getTargetSource());

        // 4) 确定性：AST 渲染
        status = "RENDERING";
        PatchResultView patch = activities.renderPatch(request.getTargetSource(), draft);

        // 5) 确定性：编译闸门（失败会抛异常，触发 Temporal 重试策略）
        status = "COMPILING";
        CompileResultView compiled = activities.compileCode(request.getClassName(), patch.getSource());
        if (!compiled.isOk()) {
            status = "COMPILE_FAILED";
            throw new IllegalStateException("编译闸门未通过: " + compiled.getDiagnostics());
        }

        // 6) 人工审批：挂起等待 signal，可等待任意时长
        status = "AWAITING_APPROVAL";
        Workflow.await(() -> approvalReceived);

        status = approved ? "APPROVED" : "REJECTED";
        activities.recordApproval(runId, approved, reviewer, approvalComment);
        return status;
    }

    @Override
    public void approve(boolean approved, String reviewer, String comment) {
        if (approvalReceived) return; // 幂等：只接受第一次审批
        this.approved = approved;
        this.reviewer = reviewer;
        this.approvalComment = comment == null ? "" : comment;
        this.approvalReceived = true;
    }

    @Override
    public String status() {
        return status + (approvalReceived ? ("(by " + reviewer + ")") : "");
    }
}
