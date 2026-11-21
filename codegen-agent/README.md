# bizgen-agent

本地大模型驱动的 Java / SQL 生成 Agent。包名 `com.walk_nie.bizgen.agent`，Java 8 兼容、**零外部依赖**（可离线编译运行），面向既有 Java 项目的二次开发。

## 设计原则

| 原则 | 实现 |
| --- | --- |
| 高确定性、重编排 | 流程控制、参数校验、状态跳转、文件写入全部由 Java 代码完成；模型只负责语义映射、意图分类、代码草稿、润色，以及在 Agent 模式下「选择下一个要调用的 tool」 |
| 强类型、状态复杂 | `Stage<I,O>` + 枚举 Phase 状态机（`JavaGenState` / `SqlGenState` / `AgentState`），非法跳转直接抛异常；模型输出统一转为 `SemanticMap` / `IntentResult` / `ToolCall` 等类型 |
| 数据不外泄 | 默认只允许连接 `localhost` / `127.0.0.1`；非本地端点直接拒绝启动（显式放开需 `-Dagent.allowRemoteLlm=true`） |
| 人工审核 | `gen.reviewRequired=true`，每次生成后提示人工 Review；生成物默认写到 `out/`，不直接覆盖既有项目 |
| 内容来自 md | 功能定义、五级设计资料、prompt、模板、表结构全部是 md 文件 |

## 目录结构

```
config/agent-config.properties     # LLM 与生成参数配置（无 --live 参数）
md/
  01-java-common/                  # Java 共通级
  02-project-common/               # 项目共通级
  03-usecase/                      # usecase 级（原「功能级」）
  04-java-class/                   # 类级
  05-method/                       # 方法级
  prompt/                          # prompt 模板（含变量）
  template/                        # 功能 md / 表结构 md 模板
  schema/                          # 表结构，一表一 md
src/main/java/com/walk_nie/bizgen/agent/
  AgentApplication.java            # 入口
  cli/                             # 命令行交互
  config/                          # 配置
  json/ llm/                       # 零依赖 JSON + OpenAI 兼容 HTTP 客户端（支持 tools/tool_calls）
  variable/                        # {{变量}} 解析 + 六种命名风格
  md/                              # 五级设计文档仓库
  agent/                           # Agent 状态机、function tool 注册表、多轮循环
  agent/tool/                      # 具体工具（设计md/类似代码/表结构/既有文件）
  pipeline/ pipeline/stage/        # 确定性编排引擎与各阶段
  generator/java/ generator/sql/   # Java / SQL 生成模型与写入器
                                   # 其中 ExistingCodeSearcher = 既有代码确定性检索
sample-existing/                   # 演示用的既有 Java 项目（检索目标，可替换成真实项目）
out/                               # 生成结果（Java 保存到 out/java）
```

## 构建与运行

```bash
bash build.sh          # Git Bash；生成 target/bizgen-agent.jar
# 或 Windows: build.bat
java -Dfile.encoding=UTF-8 -jar target/bizgen-agent.jar
```

也可使用 Maven（`pom.xml`，无外部依赖）。建议Windows控制台先执行 `chcp 65001` 以正确显示日语。

## 配置（config/agent-config.properties）

```properties
llm.baseUrl=http://127.0.0.1:11434/v1
llm.chatPath=/chat/completions
llm.model=qwen2.5-coder:14b
llm.temperature=0.1
llm.seed=42
path.generatedJavaRoot=out/java       # 生成 Java 的唯一保存路径（MD 不再指定）
path.existingJavaRoot=...             # 既存 Java 项目根（加方法/改方法 + 类似代码搜索）
path.searchJavaRoot=                  # 类似代码搜索根；留空则等同 existingJavaRoot
gen.searchMaxFiles=2000               # 最多扫描多少个 Java 文件
gen.maxSimilarFiles=5                 # 最多把几个类似文件喂给模型
gen.maxSimilarCodeChars=12000         # 类似代码总字符上限（保护 14B 上下文）
gen.maxTables=5                       # 一次最多喂给模型的表结构数
gen.reviewRequired=true
agent.maxRounds=8                     # Agent 最多几轮 LLM 调用
agent.maxToolCalls=30                 # Agent 单次生成最多调用多少次 tool
agent.maxSameCall=3                   # 同一 tool+参数重复几次就判定为循环并停止
```
生成文件路径与喂给模型的既有代码路径**全部来自配置文件**，不在功能 MD 中指定。支持 Ollama / LM Studio / llama.cpp 等 OpenAI 兼容接口。仓库自带 `sample-existing/` 作为检索演示。

## 命令

```
help                                  命令列表
config                                查看配置（API Key 已脱敏）
list java|project|usecase|class|method|schema|prompt|template
template java-class|table-schema [--out <file>]      输出 md 模板
pipeline java|sql                     查看流水线阶段（区分 CODE / LLM）
preview java --feature <md名>         只展开 prompt，不调用模型（含真实检索到的类似代码）
preview sql --type select --desc "..." --tables "受注,顧客" [--shape "..."]
preview agent                         查看 Agent 工具定义与 system prompt 模板
search --feature <md名>               只执行既有代码检索，不调用模型
tools                                 查看已注册的 function tools 与上限
java-gen --feature <md名>             单发流水线生成 Java（不经过 Agent 循环）
sql-gen --type select|update|delete --desc "..." --tables "受注,顧客"
         [--shape <SQL雛形>] [--prompt <名>] [--var k=v] [--out <file>]
agent-gen --feature <md名>            多轮 Agent + function tool 生成 Java
agent-sql --type select|update|delete --desc "..." --tables "受注,顧客"
agent-trace                           查看最近一次 Agent 的轮次与工具调用轨迹
exit
```

## Java 生成

usecase md 示例见 `md/03-usecase/sample-order-query-service.md`。front matter 中：

- `mode: NEW_FILE | ADD_METHOD | MODIFY_METHOD`：显式指定时由代码决定意图，不调用分类模型
- `targetFile:`：既有项目相对路径
- `targetMethod:`：`MODIFY_METHOD` 的目标方法名
- `designs:`：按需加载的设计文档引用，如 `java-common/naming-convention`
- 其余 key 自动作为变量
- **保存路径不在 MD 中指定**，统一由配置文件 `path.generatedJavaRoot` 决定

流水线（9 阶段）：`加载功能md → 加载设计md → 变量展开 → 意图分类(LLM/代码) → 语义映射(LLM) → 既有代码类似检索(代码) → 代码草稿(LLM) → 日语注释润色(LLM) → 校验写盘(代码)`。

### 既有代码类似检索（新增）

- 读取路径来自配置：`path.searchJavaRoot`（留空则取 `path.existingJavaRoot`）
- 关键词来自功能 MD 变量、方法标题，以及语义映射给出的英语/日语类名、字段名、方法名；过滤掉 `applicationId` 和「フィールド名」等通用表头
- 对配置目录下的 `.java` 文件做确定性关键词打分（文件名命中加权、单关键词最多计 5 次），按「分数降序 + 路径升序」排序，取 Top-N
- 排除当前要修改的目标文件；单个片段与总字符数分别受 `gen.maxSimilarFiles` / `gen.maxSimilarCodeChars` 限制，超限截断并标注「省略」
- 结果以 `{{similarCode}}` 注入 `md/prompt/java-generate.md`，作为「既有项目写法参考」，prompt 明确禁止把类似代码里的功能带入规格外
- 可用 `search --feature <md名>` 单独查看检索结果，`preview java --feature <md名>` 查看最终送模型的完整 prompt

生成物包含 `package-info.java`（标准包注释）与类文件（类/字段/方法 Javadoc，日语，写清「做了什么、为什么」）。

### 变量与命名风格

格式 `{{变量名}}`，支持 `{{变量名|风格}}`；变量名本身跨风格自动匹配（`applicationId` / `APPLICATION_ID` / `application_id` 等价）。

| 风格 | 结果 |
| --- | --- |
| 首字母大写 / UPPER_FIRST | `ApplicationId` |
| 首字母小写 / LOWER_FIRST | `applicationId` |
| 大驼峰 / UPPER_CAMEL | `ApplicationId` |
| 小驼峰 / LOWER_CAMEL | `applicationId` |
| 大蛇形 / UPPER_SNAKE | `APPLICATION_ID` |
| 小蛇形 / LOWER_SNAKE | `application_id` |

未定义变量保持原样，不会被静默替换为空。

## SQL 生成

- 表结构：一表一 md，见 `md/schema/受注.md`、`md/schema/顧客.md`，模板见 `md/template/table-schema.md`
- prompt：`md/prompt/select-sql.md`（支持多表 JOIN）、`update-sql.md`、`delete-sql.md`
- 只把 SQL 中用到的表结构喂给模型，超过 `gen.maxTables` 直接报错，不静默截断
-  Japanese 表名/字段名 → 查表结构 → 英语物理名，在送入模型前完成转换；prompt 与 md 中可直接使用日语

SQL 变量格式（按类型固定）：

| 类型 | 格式 |
| --- | --- |
| 字符型 | `/*customerName*/'xx';` |
| 日期型 | `/*orderDate*/CURREN_DATE;` |
| 数字型 | `/*orderId`*/9;` |

变量名取英语物理名的小驼峰。上述字面量（`CURREN_DATE`、数字型反引号）按需求原样实现，如需调整可修改 `SqlVarType` 常量。

## Agent 模式（多轮 function tool）

`java-gen` / `sql-gen` 是单发流水线；`agent-gen` / `agent-sql` 则启用**状态管理 + 多轮模型调用 + function tool**。

### 状态与循环

- `AgentState` 的 Phase：`INIT → THINKING → TOOL_EXECUTED → … → FINAL / FAILED`，非法跳转直接抛异常
- 每一轮请求都把完整消息历史（system / user / assistant / tool / assistant …）和 `tools` 定义一起发给本地模型
- 模型返回 `tool_calls` 时，代码只做三件事：白名单校验 → 参数校验 → 调用对应工具；结果以 `role=tool` 回灌模型，进入下一轮
- 模型返回普通文本时即为最终代码草稿，随后交回确定性阶段做润色、校验与写盘
- 三重防暴走：`agent.maxRounds`（轮数）、`agent.maxToolCalls`（工具总次数）、`agent.maxSameCall`（同一 tool+参数重复次数）；超限即 `FAILED` 而不是无限循环

### 已注册工具

| 工具 | 用途 | 模式 |
| --- | --- | --- |
| `list_design_docs` | 列出五级设计 md | Java / SQL |
| `load_design_doc` | 读取指定级别的设计 md（正文有字符上限） | Java / SQL |
| `search_similar_code` | 用指定关键词确定性检索既有 Java 类似代码 | Java |
| `read_java_file` | 读取配置根目录内的既有 Java（拒绝路径穿越） | Java |
| `list_tables` | 列出可用表结构 md | SQL |
| `load_table_schema` | 读取指定表的结构，超过 `gen.maxTables` 报错而非截断 | SQL |

工具执行失败也会作为 `[ERROR]` 文本返回给模型，让其自行修正后重试；未注册工具与非法参数由代码直接拒绝。所有工具结果都有字符上限，保护 14B 上下文。

### 使用

```
agent-gen --feature sample-order-query-service
agent-sql --type select --desc "受注IDと顧客名を取得する" --tables "受注,顧客"
agent-trace
```

`preview agent` 可查看工具定义与 system prompt 模板，`tools` 只看工具列表——两者都不调用模型。Agent 模式下**写盘仍然只由代码侧的最终阶段执行**，且仍受 `gen.reviewRequired` 约束。

prompt 模板：`md/prompt/agent-java.md`、`md/prompt/agent-sql.md`。

## 注意

- 模型输出必须返回结构化 JSON（分类/映射）或规定的分段标记（Java 草稿）；解析失败会明确报错，不会猜测。
- `ADD_METHOD` / `MODIFY_METHOD` 使用括号感知的源码编辑器做确定性插入/替换；找不到目标方法会失败并列出已检测到的方法名。
- 生成结果一律需要人工审核后再合入项目。
