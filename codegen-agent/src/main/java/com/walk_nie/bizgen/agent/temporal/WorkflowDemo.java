package com.walk_nie.bizgen.agent.temporal;

import com.walk_nie.bizgen.agent.*;
import com.walk_nie.bizgen.agent.Intent;
import com.walk_nie.bizgen.agent.temporal.Dtos.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import com.walk_nie.bizgen.agent.temporal.CodeGenWorkflow.CodeGenRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 第 3 件的离线验证：用 Temporal 内存测试环境跑完整 workflow（无需 Server）。
 * 用 mock Activity 替代真实 LLM/索引，验证重点是「编排 + signal 挂起/唤醒 + query」。
 */
public final class WorkflowDemo {

    public static void main(String[] args) {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = env.newWorker(WorkerMain.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(CodeGenWorkflowImpl.class);
            worker.registerActivitiesImplementations(new MockActivities());
            env.start();

            CodeGenWorkflow wf = env.getWorkflowClient().newWorkflowStub(
                    CodeGenWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(WorkerMain.TASK_QUEUE).build());

            // workflow 会在编译后挂起等待审批；延迟 1 秒自动发 approve signal
            env.registerDelayedCallback(Duration.ofSeconds(1),
                    () -> wf.approve(true, "reviewer-01", "テスト承認"));

            String result = wf.execute(new CodeGenRequest("仕様テキスト", "public class Service {}", "Service"));
            System.out.println("=== workflow 最终结果: " + result + " ===");

            if (!"APPROVED".equals(result)) throw new AssertionError("审批 signal 未生效");
            System.out.println("Temporal workflow + signal 自检完成。");
        } finally {
            env.close();
        }
    }

    /** mock Activity：返回结构合法、编译可通过的最小结果。 */
    static final class MockActivities implements CodeGenActivities {
        @Override public SemanticMapping mapSpec(String spec) {
            return new SemanticMapping("mock", Intent.NEW_METHOD, "Service", "match",
                    List.of("PaymentRepository.findByDate"), List.of("空リストを返す"));
        }
        @Override public CodeDraft draftPatch(SemanticMapping m, String src) {
            return new CodeDraft("mock", List.of(), List.of());
        }
        @Override public PatchResultView renderPatch(String src, CodeDraft d) {
            return new PatchResultView(src, 0);
        }
        @Override public ApiWhitelistView loadWhitelist() {
            return new ApiWhitelistView(List.of("PaymentRepository.findByDate"));
        }
        @Override public void validateApis(List<String> apiCalls) { /* mock: 全部允许 */ }
        @Override public CompileResultView compileCode(String cls, String src) {
            return new CompileResultView(true, List.of());
        }
        @Override public void recordApproval(String runId, boolean approved, String reviewer, String comment) {
            System.out.println("[mock 审批记录] " + runId + " -> " + approved + " by " + reviewer);
        }
    }
}
