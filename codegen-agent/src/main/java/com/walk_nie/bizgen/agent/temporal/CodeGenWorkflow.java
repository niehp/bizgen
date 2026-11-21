package com.walk_nie.bizgen.agent.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Workflow 契约：一次代码生成 = 一个长时间运行的 workflow 实例。 */
@WorkflowInterface
public interface CodeGenWorkflow {

    /** 启动一次生成流程，返回最终状态文本。 */
    @WorkflowMethod
    String execute(CodeGenRequest request);

    /** 人工审批信号：由审核人在任意时刻发出，workflow 被唤醒继续。 */
    @SignalMethod
    void approve(boolean approved, String reviewer, String comment);

    /** 查询当前状态，供审核界面/运维查看，不改变 workflow 状态。 */
    @QueryMethod
    String status();

    /** 请求 DTO。 */
    final class CodeGenRequest {
        private String specText;
        private String targetSource;
        private String className;
        public CodeGenRequest() {}
        public CodeGenRequest(String specText, String targetSource, String className) {
            this.specText = specText; this.targetSource = targetSource; this.className = className;
        }
        public String getSpecText() { return specText; }
        public void setSpecText(String specText) { this.specText = specText; }
        public String getTargetSource() { return targetSource; }
        public void setTargetSource(String targetSource) { this.targetSource = targetSource; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
    }
}
