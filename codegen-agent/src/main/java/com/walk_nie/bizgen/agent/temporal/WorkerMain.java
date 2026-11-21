package com.walk_nie.bizgen.agent.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import com.walk_nie.bizgen.agent.VllmClient;

import java.nio.file.Path;

/**
 * 生产 Worker 启动器：连接本地 Temporal Server（默认 127.0.0.1:7233）。
 * 启动前需先运行 Temporal Server（docker 或本地二进制），模型与索引也需就绪。
 */
public final class WorkerMain {

    public static final String TASK_QUEUE = "codegen-task-queue";

    public static void main(String[] args) {
        String server = System.getenv().getOrDefault("TEMPORAL_TARGET", "127.0.0.1:7233");
        String vllmBase = System.getenv().getOrDefault("VLLM_BASE_URL", "http://127.0.0.1:8000");
        String model = System.getenv().getOrDefault("VLLM_MODEL", "Qwen/Qwen2.5-Coder-14B-Instruct");
        Path index = Path.of(System.getenv().getOrDefault("CODE_INDEX_DB", "code_master.db"));

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                io.temporal.serviceclient.WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(server)
                        .build());
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);

        worker.registerWorkflowImplementationTypes(CodeGenWorkflowImpl.class);
        worker.registerActivitiesImplementations(new CodeGenActivitiesImpl(new VllmClient(vllmBase, model), index));

        System.out.println("Worker 启动，连接 Temporal Server: " + server + "，任务队列: " + TASK_QUEUE);
        factory.start();
    }
}
