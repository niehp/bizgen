package com.walk_nie.bizgen.agent.temporal;

import com.walk_nie.bizgen.agent.temporal.CodeGenWorkflow.CodeGenRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;

/** 发起一次生成流程，并演示人工审批 signal 与状态 query。 */
public final class WorkflowStarter {

    public static void main(String[] args) {
        String server = System.getenv().getOrDefault("TEMPORAL_TARGET", "127.0.0.1:7233");
        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                io.temporal.serviceclient.WorkflowServiceStubsOptions.newBuilder().setTarget(server).build());
        WorkflowClient client = WorkflowClient.newInstance(service);

        String workflowId = "codegen-" + System.currentTimeMillis();
        CodeGenWorkflow workflow = client.newWorkflowStub(
                CodeGenWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(WorkerMain.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());

        String spec = "入金消込バッチに matchPayments メソッドを追加する。";
        String source = "package sample;\npublic class Service { public void match() {} }";

        // 异步启动（workflow 会挂起等待审批 signal）
        WorkflowClient.start(workflow::execute, new CodeGenRequest(spec, source, "sample.Service"));
        System.out.println("已启动 workflow: " + workflowId);

        WorkflowStub untyped = client.newUntypedWorkflowStub(workflowId);
        System.out.println("当前状态: " + untyped.query("status", String.class));

        // 模拟人工审批
        workflow.approve(true, "reviewer-01", "LGTM");
        System.out.println("已发送审批 signal");

        String result = untyped.getResult(String.class);
        System.out.println("最终结果: " + result);
    }
}
