package com.walk_nie.bizgen.agent;

import java.util.List;

/**
 * 语义映射：LLM 把日文式样书条目映射为强类型结构。
 * 这是「单一真源」——同一份 record 既用来派生 JSON Schema（发给 vLLM 做 guided_json），
 * 也用来反序列化模型回填的结果。字段扁平（≤2 层），分类字段用 enum。
 *
 * @param reasoning        模型推理说明（仅供人工审核阅读，不参与流程控制）
 * @param intent           意图，enum 约束
 * @param targetClass      目标类名（必须命中代码索引白名单，由 Java 校验）
 * @param targetMethod     目标方法名
 * @param apiCalls         允许调用的 API 白名单，Java 侧逐项校验
 * @param acceptanceChecks 可判定的验收条件
 */
public record SemanticMapping(
        String reasoning,
        Intent intent,
        String targetClass,
        String targetMethod,
        List<String> apiCalls,
        List<String> acceptanceChecks
) {}
