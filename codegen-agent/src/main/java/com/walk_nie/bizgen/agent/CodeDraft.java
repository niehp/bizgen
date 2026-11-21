package com.walk_nie.bizgen.agent;

import java.util.List;

/**
 * 代码草稿：LLM 只产出「补丁计划」，不直接吐裸代码。
 * Java 侧拿到这个 record 后，用 JavaParser 把 patchHunks 渲染成 AST，
 * 再经过编译/静态检查闸门。模型无权决定文件如何落盘。
 *
 * @param reasoning   推理说明
 * @param imports     需要新增的 import
 * @param patchHunks  补丁片段（每条含目标方法 + 语句文本）
 */
public record CodeDraft(
        String reasoning,
        List<String> imports,
        List<PatchHunk> patchHunks
) {
    /** 补丁片段，仍然是扁平结构。 */
    public record PatchHunk(
            String targetMethod,
            String statement,
            HunkKind kind
    ) {}

    public enum HunkKind {
        INSERT_BEFORE,
        INSERT_AFTER,
        REPLACE
    }
}
