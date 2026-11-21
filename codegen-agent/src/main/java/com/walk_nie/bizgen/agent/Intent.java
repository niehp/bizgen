package com.walk_nie.bizgen.agent;

import java.util.List;

/** 意图分类：分类任务用 enum + guided_json 的 enum 约束，模型无法产出集合外的值。 */
public enum Intent {
    NEW_METHOD,
    CHANGE_METHOD,
    DELETE_METHOD,
    UNKNOWN
}
