package com.walk_nie.bizgen.agent.temporal;

import java.util.List;

/** Temporal 数据契约：用简单可变 POJO（Jackson 友好），避免 record/复杂泛型跨边界序列化问题。 */

public final class Dtos {

    /** 白名单视图：只传允许集合的快照，避免把整个 ApiWhitelist 对象跨进程序列化。 */
    public static final class ApiWhitelistView {
        private List<String> allowed;
        public ApiWhitelistView() {}
        public ApiWhitelistView(List<String> allowed) { this.allowed = allowed; }
        public List<String> getAllowed() { return allowed; }
        public void setAllowed(List<String> allowed) { this.allowed = allowed; }
    }

    /** AST 渲染结果视图。 */
    public static final class PatchResultView {
        private String source;
        private int appliedHunks;
        public PatchResultView() {}
        public PatchResultView(String source, int appliedHunks) {
            this.source = source; this.appliedHunks = appliedHunks;
        }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public int getAppliedHunks() { return appliedHunks; }
        public void setAppliedHunks(int appliedHunks) { this.appliedHunks = appliedHunks; }
    }

    /** 编译结果视图。 */
    public static final class CompileResultView {
        private boolean ok;
        private List<String> diagnostics;
        public CompileResultView() {}
        public CompileResultView(boolean ok, List<String> diagnostics) {
            this.ok = ok; this.diagnostics = diagnostics;
        }
        public boolean isOk() { return ok; }
        public void setOk(boolean ok) { this.ok = ok; }
        public List<String> getDiagnostics() { return diagnostics; }
        public void setDiagnostics(List<String> diagnostics) { this.diagnostics = diagnostics; }
    }
}
