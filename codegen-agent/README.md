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
bash build.sh          # Git Bash；内部调用 mvn package，生成 target/bizgen-agent.jar
# 或 Windows: build.bat
java -Dfile.encoding=UTF-8 -jar target/bizgen-agent.jar
```

**注意**：加入 JDT AST 与 SQLite 后必须经 Maven 解析依赖（`mvn package`），构建脚本已改为调用 Maven。首次拉取后可用 `mvn -o package` 离线构建，依赖会复制到 `target/lib/`，jar 的 manifest 已带 Class-Path。建议 Windows 控制台先执行 `chcp 65001` 以正确显示日语。

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
path.specRoot=spec                    # 参考资料根（api/dict/template/example 四类 md）
spec.maxChars=24000                   # spec 资料注入 prompt 的总字符上限，超限直接报错
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
gen --feature <md名>                   总体流程统一入口：按 md 的 generate: java|sql|both 分流
plan --feature <md名>                  不调模型查看 md 契约（生成种别/Java 类方法流程/SQL 对象表与字段）
list java|project|usecase|class|method|schema|prompt|template
template java-class|table-schema [--out <file>]      输出 md 模板
pipeline java|sql|feature|flow        查看流水线阶段（区分 CODE / LLM）
preview java --feature <md名>         只展开 prompt，不调用模型（含真实检索到的类似代码）
preview sql --type select --desc "..." --tables "受注,顧客" [--shape "..."]   查看 SQL flow 工具与展开后的 prompt（不调模型）
preview agent                         查看 Agent 工具定义与 system prompt 模板
search --feature <md名>               只执行既有代码检索，不调用模型
tools                                 查看已注册的 function tools 与上限
terms                                 查看术语库
api-list                              查看 API 库
spec-list                             查看 spec/api・dict・template・example 四类参考资料
reuse-list                            查看可复用代码块/API 模板（spec/block，不调模型）
java-gen --feature <md名>             13 阶段 Java 生成框架（含术语/API/spec/自检，不经过 Agent 循环）
flow-gen --feature <md名>             6 步流程：功能块分解 → API 走 tool / 其余新作 → 循环 → 结合润色
sql-gen --type select|update|delete --desc "..." --tables "受注,顧客"
         [--shape <SQL雛形>] [--prompt <名>] [--var k=v] [--out <file>]
agent-gen --feature <md名>            多轮 Agent + function tool 生成 Java
agent-sql --type select|update|delete --desc "..." --tables "受注,顧客"
agent-trace                           查看最近一次 Agent 的轮次与工具调用轨迹
exit
```

## 总体流程（md 是唯一入口）

需求写在 md 文件中；`gen --feature <md名>` 读取该 md，由 front matter 的 `generate:` 决定生成 java、sql 还是两者。代码负责分流与校验，模型只负责语义与草稿。

```yaml
---
generate: java        # java | sql | both，省略按 java 处理
# java 时：md 正文写包名、类、字段、方法（目的/処理内容/理由/引数/戻り値/例外）与 ## 処理フロー
# sql 时：写下面的契约
sqltype: select       # select | update | delete
tables: 受注,顧客      # 对象表名（日语）
fields:               # 对象字段（日语，表.字段 形式）
  - 受注.受注ID
  - 顧客.顧客名
---
```

- Java：类名、方法名、字段名与处理顺序由代码从 md 解析为 `JavaFeatureOutline` 强类型契约并注入 prompt，模型不得改名；语义映射阶段用契约覆盖模型的推断。
- SQL：种别、对象表、对象字段从 md 读取；送入模型前完成「表名→表结构→字段日语名→英语物理名」转换，未定义的表/字段直接报错。`tables:` 省略时会从 `fields:` 的表名前缀或 md 正文自动确定要加载的表。
- `plan --feature <md名>` 可不调用模型检查入口是否写对；`java-gen` / `sql-gen` 仍可单独调用。

示例：`md/03-usecase/sample-order-query-service.md`（java）、`sample-order-select-sql.md`（sql）、`sample-order-both.md`（both）。

## Java 生成

usecase md 示例见 `md/03-usecase/sample-order-query-service.md`。front matter 中：

- `mode: NEW_FILE | ADD_METHOD | MODIFY_METHOD`：显式指定时由代码决定意图，不调用分类模型
- `targetFile:`：既有项目相对路径
- `targetMethod:`：`MODIFY_METHOD` 的目标方法名；生成代码里必须含同名方法，否则直接报错交人工审核，不会退化为改写第一个方法
- `designs:`：设计文档引用，如 `java-common/naming-convention`（显式引用始终加载）
- 其余 key 自动作为变量；**front matter 自身也能用变量**（见下节）
- **保存路径不在 MD 中指定**，统一由配置文件 `path.generatedJavaRoot` 决定

五级设计目录（`md/01-java-common` ~ `md/05-method`）**不再全量加载**：先加载 `designs:` 显式引用，再按功能 MD 正文的日语/英语关键词自动匹配，最多追加 `gen.maxAutoDesigns=6` 份（设为 0 可关闭）。加载顺序固定为「相关度降序 → 级别升序 → 路径升序」，变量在送入模型前统一展开。

流水线（13 阶段）：`加载功能md → 加载设计md → 变量展开 → 参考资料加载(spec) → 术语提取(代码) → API匹配(代码) → 意图分类(LLM/代码) → 语义映射(LLM) → 既有代码类似检索(代码) → 代码草稿(LLM) → 日语注释润色(LLM) → 静态校验+一次自我修正(LLM) → 校验写盘(代码)`。

`java-gen` 已把 5 步 Feature Pipeline 的能力集成进主框架：术语库与 API 库在代码侧筛选后注入 prompt，生成结果先抽取纯 Java 再静态校验，有违规则只自我修正一次，剩余问题作为 `[WARN]` 交人工审核。

### 既有代码类似检索（新增）

- 读取路径来自配置：`path.searchJavaRoot`（留空则取 `path.existingJavaRoot`）
- 关键词来自功能 MD 变量、方法标题，以及语义映射给出的英语/日语类名、字段名、方法名；过滤掉 `applicationId` 和「フィールド名」等通用表头
- 对配置目录下的 `.java` 文件做确定性关键词打分（文件名命中加权、单关键词最多计 5 次），按「分数降序 + 路径升序」排序，取 Top-N
- 排除当前要修改的目标文件；单个片段与总字符数分别受 `gen.maxSimilarFiles` / `gen.maxSimilarCodeChars` 限制，超限截断并标注「省略」
- 结果以 `{{similarCode}}` 注入 `md/prompt/java-generate.md`，作为「既有项目写法参考」，prompt 明确禁止把类似代码里的功能带入规格外
- **无命中时记录输入**：标准输出打印 `[similar-code][no-hit]`，同时把功能 MD 名称、关键词、检索根目录、扫描文件数与输入正文追加到 `out/logs/similar-code.log`（UTF-8）；日志失败不会中断生成
- `flow-gen` 的每个 NEW_CODE 块会按该块描述单独再做一次类似检索，并覆盖该块的 `{{similarCode}}`；不是复用全局检索结果
- 可用 `search --feature <md名>` 单独查看检索结果，`preview java --feature <md名>` 查看最终送模型的完整 prompt

### 术语 / API 注入与静态校验（集成自 Feature Pipeline）

- `04-extract-terms`：从展开后的功能 MD 抽取业务词汇，从 `path.termRoot` 的 `日本語 -> 英語` txt/md 中确定性匹配，结果经 `{{termsText}}` 注入 prompt
- `05-match-apis`：用「功能 MD + 术语日英」查询 `path.apiRoot` 的 API 库，按 `gen.apiTopN` 取既存方法签名，经 `{{apisText}}` 注入；prompt 明确禁止调用表外 API
- `11-validate-correct`：抽取纯 Java 块，检查全角/控制字符、package/class 结构、标识符中的日语；`ADD_METHOD` / `MODIFY_METHOD` 只校验方法片。违规时用 `md/prompt/java-self-correct.md` 只请求一次修正；修正后仍不合规则保留 `[WARN]`，不循环
- 日语 Javadoc 与字符串字面量不算违规：非法字符检查只作用于屏蔽注释/字符串后的代码区域
- 术语分析、API 匹配、修正请求统一由 `generator/CodegenSupport` 提供，`java-gen` 与 `feature-gen` 共用同一套判定

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

**front matter 里的变量**：`title:`、`targetFile:`、`generate:`、`sqltype:`、`tables:`、`fields:` 等保留字段的值也会替换，且替换发生在阅读 md 时（不依赖流水线）。按 front matter 的书写顺序迭代多轮（最多 8 轮），因此后定义的变量也能被前面的字段引用；命令行 `--var` 作为初始值，md 中的定义优先。例如：

```yaml
title: {{classTitle}}
targetFile: {{packagePath}}/{{className}}
classTitle: 受注検索サービスの新規作成
packagePath: com/walk_nie/bizgen/sample
entityName: Order
className: {{entityName|大驼峰}}QueryService
```

解析结果：`title=受注検索サービスの新規作成`、`targetFile=com/walk_nie/bizgen/sample/OrderQueryService`。未定义变量或循环引用会保留 `{{...}}` 原文，`plan --feature` 会打印 `[WARN] 未解決の front matter 変数`。设计 md 的 front matter 同样参与变量展开。

## SQL 生成（flow pipeline 式）

SQL 生成与 Java flowGen 使用同一套路：确定性前置 → 模型用 function tool 取知识 → 确定性写盘。

| 阶段 | 担当 | 内容 |
| --- | --- | --- |
| 1 | 代码 | 校验输入（表数不超过 `gen.maxTables`）、确定变量（`sql-flow-01-prepare`） |
| 2 | 模型 + tool | 用 `list_tables` / `load_table_schema` 取表结构、`translate_terms` / `inject_terms` 取日英对訳、`lookup_variable` 取变量、`load_spec` 取模板，然后生成 SQL（`sql-flow-02-generate`） |
| 3 | 代码 | 写盘，并记录模型实际读过的表用于人工审核（`04-write-sql`） |

- 表结构：一表一 md，见 `md/schema/受注.md`、`md/schema/顧客.md`，模板见 `md/template/table-schema.md`
- SQL 功能 md 模板见 `md/template/sql-feature.md`；`sql-gen` 也支持 `--fields "受注.受注ID,顧客.顧客名"`
- flow prompt：`md/prompt/sql-flow.md`（知识全部由模型 tool 获取，不再静态注入表结构）；旧的 `select-sql.md`（支持多表 JOIN）、`update-sql.md`、`delete-sql.md` 仍保留供 `preview` 与旧链路使用
- 只把 SQL 中用到的表结构喂给模型：模型自己按需要调用 `load_table_schema`，超过 `gen.maxTables` 时工具报错而非静默截断
- 日语表名/字段名 → 表结构 → 英语物理名，由模型用工具获取的对訳确定；prompt 与 md 中可直接使用日语
- 旧的单发链路已改为 flow：`sql-gen` / `gen --feature xxx`（SQL 分支）/ `flow-gen --feature xxx`（SQL 分支）现在都走同一条 flow pipeline

SQL 变量格式（按类型固定）：

| 类型 | 格式 |
| --- | --- |
| 字符型 | `/*customerName*/'xx';` |
| 日期型 | `/*orderDate*/CURREN_DATE;` |
| 数字型 | `/*orderId*/9;` |

变量名取英语物理名的小驼峰。上述字面量（`CURREN_DATE`）按需求原样实现，如需调整可修改 `SqlVarType` 常量。

## Agent 模式（多轮 function tool）

`java-gen` / `flow-gen`（Java）/ `sql-gen` 是**确定性流水线**（其中 SQL 与 flow-gen 的 LLM 阶段也用 function tool 取知识）；`agent-gen` / `agent-sql` 则启用**完整的 Agent 状态管理 + 多轮模型调用 + function tool**。`agent-sql` 现在复用 SQL flow 的工具白名单与 `sql-flow.md` prompt，不再静态注入表结构。

### 状态与循环

- `AgentState` 的 Phase：`INIT → THINKING → TOOL_EXECUTED → … → FINAL / FAILED`，非法跳转直接抛异常
- 每一轮请求都把完整消息历史（system / user / assistant / tool / assistant …）和 `tools` 定义一起发给本地模型
- 模型返回 `tool_calls` 时，代码只做三件事：白名单校验 → 参数校验 → 调用对应工具；结果以 `role=tool` 回灌模型，进入下一轮
- 模型返回普通文本时即为最终代码草稿，随后交回确定性阶段做润色、校验与写盘
- 三重防暴走：`agent.maxRounds`（轮数）、`agent.maxToolCalls`（工具总次数）、`agent.maxSameCall`（同一 tool+参数重复次数）；超限即 `FAILED` 而不是无限循环

### 已注册工具

| 工具 | 用途 | 模式 |
| --- | --- | --- |
| `list_design_docs` / `load_design_doc` | 列出、读取五级设计 md（正文有字符上限） | Java / SQL |
| `lookup_variable` | 从功能 md 变量表取确定值，并按 6 种命名风格（首字母大/小写、大/小驼峰、大/小蛇形）转换；未定义即报错 | Java / SQL |
| `translate_terms` / `inject_terms` | 用术语库做日语→英语映射、给功能/设计文本注入对訳表；词典外不翻译 | Java / SQL |
| `load_spec` | 按 `api` / `dict` / `template` / `example` 列出或读取 `spec/*.md`，变量已由代码展开 | Java / SQL |
| `find_api` | 由模型主动检索 API 库；只返回 md 中真实存在的签名，并记录选中结果供人工审核 | Java |
| `search_similar_code` / `read_java_file` | 确定性检索既有 Java 类似代码、读取配置根目录内的既有 Java（拒绝路径穿越） | Java |
| `list_tables` / `load_table_schema` | 列出表结构 md、读取指定表结构；超过 `gen.maxTables` 报错而非截断 | SQL |

上表是常用工具；完整白名单（含 JDT AST 的 `find_class` / `find_method` / `propose_java_edit` / `approve_java_edit` 等 12 个工具、复用模板工具）以 `tools` 命令输出为准。工具执行失败也会作为 `[ERROR]` 文本返回给模型，让其自行修正后重试；未注册工具与非法参数由代码直接拒绝。所有工具结果都有字符上限，保护 14B 上下文。

### 使用

```
agent-gen --feature sample-order-query-service
agent-sql --type select --desc "受注IDと顧客名を取得する" --tables "受注,顧客"
agent-trace
```

`preview agent` 可查看工具定义与 system prompt 模板，`tools` 只看工具列表——两者都不调用模型。Agent 模式下**写盘仍然只由代码侧的最终阶段执行**，且仍受 `gen.reviewRequired` 约束。

Java 生成时，模型可先用 `find_api` 决定调用哪些既存 API：工具只从 `md/api` 返回真实签名（可按 `className` 收窄），模型再把 API 调用与新写的代码组合成一个类。`lookup_variable`、`translate_terms`、`load_spec` 分别把变量、日英对訳、`spec/{api,dict,template,example}` 资料改为模型按需调用；工具结果仍全部受字符上限与白名单校验。工具实际返回过的 API 会在 `agent-gen` 输出里以「モデルが find_api で確認した API」列出，便于人工审核。

prompt 模板：`md/prompt/agent-java.md`；SQL（flow 与 agent-sql 共用）为 `md/prompt/sql-flow.md`。

### 工具调用没有出现时（Qwen2.5-Coder + LM Studio）

Qwen2.5-Coder 不在 LM Studio 的 **native tool use** 支持列表中，常把工具调用当作普通文本写进 `message.content`，而 `tool_calls` 字段为空。此时 Agent 会把那段文本当成最终回答，看起来就是"没有调用工具"。

排查步骤：

1. 确认 LM Studio 本地服务器已开启，且配置指向它的端口与模型 ID：

   ```properties
   llm.baseUrl=http://127.0.0.1:1234/v1
   llm.model=qwen2.5-coder-14b-instruct   # 以 /v1/models 返回值为准
   ```

2. 运行 `llm-probe`，它会发一次带 `echo` 工具的最小请求并打印原始响应、结构化 `tool_calls`、`content`，以及是否能从文本中恢复工具调用。

3. 若 `content` 里有 `<tool_call>{...}</tool_call>`、`[TOOL_REQUEST]{...}[END_TOOL_REQUEST]` 或 JSON 代码块，`agent.toolCallRecovery=true`（默认）会在 Agent 模式下自动把它提升为真正的工具调用，`agent-trace` 中会显示 `[WARN]` 说明发生了恢复。若不想启用该兜底，设为 `false`。

4. 若原始响应中既没有结构化调用也没有上述文本，说明模型没有打算调用工具：可尝试把 `llm.toolChoice` 设为 `required`、换用 LM Studio 标记为原生支持工具调用的模型（如 Qwen2.5-7B-Instruct、Llama-3.1-8B-Instruct），或提高量化等级（Q4_K_M → Q5_K_M）。

## 6 ステップ Flow Pipeline（flow-gen）

`flow-gen --feature <md名>` は、機能 md の `generate: java|sql|both` を読んで生成先を振り分ける（既定は `java`）。`both` なら Flow Pipeline で Java を生成した後に SQL も生成する。

| ステップ | 担当 | 内容 |
| --- | --- | --- |
| 1 | コード | 機能 md を読み込み、`generate` と SQL 種別・表を確定 |
| 2 | コード | `{{変数}}` を展開（設計 md も展開） |
| 3 | モデル + tool | 意味を解析し、コードの流れと機能ブロックに分解（`code-plan.md`） |
| 4 | モデル + tool | API ブロックは `find_api`、それ以外は `search_similar_code` / `semantic_search` で実在の根拠を集めてコード生成 |
| 5 | コード | 機能ブロックを最後まで 1 つずつループ（上限 `gen.maxBlocks=12`、進行は `FlowGenState` が管理） |
| 6 | モデル + tool | 全ブロックを 1 クラスに結合し、日本語コメントを潤色（`combine-polish.md`） |
| 7 | コード | 静的検証 → 1 回だけ自己修正 → パス構造に沿って UTF-8 落盤 |

`agent-gen` がモデルに探索を任せるのに対し、flow-gen は**ループ回数とブロック順をコード側が固定**し、モデルには各ブロックの中身だけを任せる。

### 知識はすべて function tool で取得

flow-gen の 4 つの LLM ステージ（ブロック計画 / API ブロック / 新規ブロック / 結合・潤色）は、変数・API・术语・日英対訳・類似コード・テンプレートを prompt へ事前注入しない。代わりに共通白名单 `FlowAgentSupport.tools()` から次を必要に応じて呼ぶ。

| 工具 | 用途 |
| --- | --- |
| `lookup_variable` | `{{変数}}` の確定値と 6 種命名スタイル |
| `translate_terms` / `inject_terms` | 术语库だけを使った日本語→英語変換・対訳表 |
| `load_spec` | `spec/api`・`spec/dict`・`spec/template`・`spec/example` の一覧／取得 |
| `find_api` | 実在シグネチャの確定（API ブロック） |
| `search_similar_code` / `semantic_search` | 既存 Java の本物の実装を検索（無命中はログ） |
| `list_design_docs` / `load_design_doc` / `read_java_file` | 設計規約・対象ファイルの確認 |

- どのツールを呼ぶかはモデルが決めるが、**白名单・引数検証・結果の文字数上限・ループ上限はすべてコード側**（`AgentLoop` / `ToolRegistry`）。
- `java-gen` の `LoadSpecStage` による一括注入は従来どおり残っているが、flow-gen では実行しない。

```
flow-gen --feature sample-order-query-service   # generate: java|both なら Java を生成
pipeline flow        # ステージ順番を表示
```

```
spec-list   # 4 類の資料一覧（LLM 不使用）
```

## コード片 / API 呼び出しテンプレートの再利用（flow-gen / agent-gen 共通）

「似たコードを参考にする」だけではなく、**登録済みのテンプレートをコード側で選び、変数を展開し、そのまま生成結果に埋め込む**仕組み。`spec/example` はあくまで書き方の参考であり、ここで扱うテンプレートは「一字も変更してはいけない確定値」が違い。

### テンプレートの書き方（`spec/block/*.md`、1 ファイル = 1 ブロック）

````markdown
# ブロック: require-non-blank

- kind: API_CALL                # API_CALL | CODE
- description: 引数が空でないことを検証し、未設定なら例外を投げる
- keywords: 必須チェック, 引数検証, requireNonBlank
- api: `public static void requireNonBlank(String value, String name)`   # API_CALL は必須
- vars: value=orderId, name="orderId"   # 変数の既定値。機能 md の変数が優先

```java
Validate.requireNonBlank({{value}}, {{name}});
```
````

- キーは日本語 (`種別 / 説明 / キーワード / 変数`) でも読める。コード部分は最初の ` ```java ` ブロックのみ。
- `{{変数}}` は機能 md の front matter 変数 → テンプレート既定値の順で展開する。未解決のプレースホルダが残るテンプレートは**採用しない**。
- `API_CALL` は `api:` のシグネチャが `path.apiRoot`（既定 `md/api`）に実在しなければ採用しない。モデルに存在しない API を書かせないための白名单。
- 一致はキーワード一致のみ（埋め込み・外部 API 不使用）。同点なら id の辞書順で安定ソートする。

設定: `path.blockRoot=spec/block`、`reuse.enabled=true`、`reuse.minScore=5`、`reuse.topN=5`。

### flow-gen での流れ（LLM なしで確定）

1. `flow-01-plan-blocks` が機能ブロックを分解するとき、`{{reusableHints}}` にテンプレート一覧を渡す（採用判断はしない）。
2. 新しい `flow-01a-reuse-blocks` がブロックごとにテンプレートを検索し、採用できれば **モデルを呼ばずに** `Block.markDone()` する。API_CALL は確認済み API として監査記録にも残る。
3. 残ったブロックだけが `find_api` / 新規生成の対象。
4. `combine-polish.md` に「固定フラグメント」として注入し、結合後に **コード側で一字も変わっていないか検証**する。欠落していれば生成を失敗させ、黙って直さない。

### agent-gen での流れ（function tool）

| tool | 役割 |
| --- | --- |
| `find_reusable_block` | `spec/block` から候補を決定論的に検索。id・種別・説明・確認済み API・必要変数を返す |
| `apply_block_template` | id と `variables` を受け取り、**コード側で**変数展開して確定コードを返す。同時に `AgentContext` へ記録 |

モデルは `find_reusable_block` → `apply_block_template` の順で呼び、返ったコードを変更せず最終回答に入れる。最終回答の草稿に対し `ReuseVerifier` が全フラグメントの包含を検証し、1 つでも欠けていればエラー（人間レビューに回す）。`agent-java.md` と `agent-gen` の user prompt にこの順序と禁止事項を明記している。

```
reuse-list                                   # テンプレート一覧（LLM 不使用）
flow-gen --feature sample-order-query-service
agent-gen --feature sample-order-query-service
```

サンプル: `spec/block/require-non-blank.md`（API_CALL）、`spec/block/find-order-by-id.md`（API_CALL）、`spec/block/empty-optional.md`（CODE）。
`reuse.enabled=false` にすれば両方の経路で旧挙動（参考としてのみ注入）に戻せる。

## 5 ステップ Feature Pipeline（feature-gen）

`feature-gen --feature <md名>` は、機能描写 MD を入力に次の 5 ステージを固定順で実行する。
`agent-gen` が「モデルが tool を選ぶ」多輪型なのに対し、こちらは**全部コード側で決める直線型**パイプライン。

| ステップ | ステージ | 担当 | 内容 |
| --- | --- | --- | --- |
| 1 | `01-extract-terms` | CODE | 機能 md の変数を展開し、业务词汇を抽出して术语库から日英マッピングを引く |
| 2 | `02-match-apis` | CODE | 機能 md + 术语をクエリに、API 库から関連する既存クラス／メソッドシグネチャを選ぶ |
| 3 | `03-generate-java` | LLM | 機能 MD + 术语 + API + 日本語開発規約を structured prompt にして Java を生成 |
| 4 | `04-validate-correct` | LLM | 纯 Java コードを抽出し、非法文字・术语違反を検査。違反なら Self-Correction を 1 回だけ依頼 |
| 5 | `05-write-file` | CODE | パッケージ構造に従って UTF-8 で `output/src/main/java/...` に落盤 |

> 同じ 5 ステップの能力は `java-gen`（13 ステージ本流、参考資料ロードを含む）にも統合済みです。术语/API/自己修正の判定は `CodegenSupport` を共用しているため、どちらを呼んでも同じルールが適用されます。`feature-gen` は「機能 MD だけから 1 クラスを生成して `featureOutputRoot` に落盤する」簡易モードとして残しています。

### 术语库と API 库

- 术语库: `path.termRoot`（既定 `md/term`）直下の `.txt` / `.md` を全部読み込む。1 行 `日本語 -> 英語`。長い語が優先され、Javadoc の日本語は違反扱いしない。
- API 库: `path.apiRoot`（既定 `md/api`）直下の `.md` を 1 クラスとして読み、`# クラス名` と表形式の `| シグネチャ | 説明 |` を解釈する。 Hits 上位 `gen.apiTopN` 件だけを模型に渡す。
- どちらも决定论的文字列マッチングで、LLM には「どの术语／API を使うか」を選ばせない。

### 自己修正と出力

- 検査項目: 全角スペース・全角記号・BOM・制御文字、`package` / `class` 宣言の欠落、識別子中の日本語（术语表の英語名を提案）。
- 違反時に `md/prompt/feature-self-correct.md` で 1 回だけ再生成し、それ以上はループしない。残存違反は `[WARN]` として表示し、人間レビューに委ねる。
- 出力先は `path.featureOutputRoot`（既定 `output/src/main/java`）。コード内の `package` 宣言からディレクトリを作る。

### コマンド

```
feature-gen --feature sample-order-query-service
terms                     # 术语库の一覧（LLM 不使用）
api-list                  # API 库の一覧（LLM 不使用）
pipeline feature          # 5 ステージの構成を表示
```

設定項目は `path.termRoot` / `path.apiRoot` / `path.featureOutputRoot` / `gen.apiTopN` / `gen.selfCorrect`。
prompt テンプレートは `md/prompt/feature-codegen.md` と `md/prompt/feature-self-correct.md`。

## テンプレート一括填充（template-fill）

`template` コマンドが「空のテンプレートを人手で書き始めるためのコピー」だったのに対し、`template-fill` は**テンプレートを LLM に 1 ファイルずつ渡して空欄を埋めさせる**機能です。

- 入力: `path.templateFillRoot` 配下の**すべてのファイルを再帰的に**列挙（拡張子問わず、パス順に処理、`templateFill.maxFiles` 件まで）
- 填充: 各ファイルの `{{...}}` を検出し、front matter／`--var` で既知の変数は**コード側で確定置換**した上で、`md/prompt/template-fill.md` に「置換済みテンプレート + 元のプレースホルダ一覧 + 参照情報」を渡して残りの空欄を LLM に埋めさせる
- 参照情報: `--feature <md名>` で機能描写 md を展開して渡す。front matter の全キー（title / targetFile 等も含む）が変数として利用可能。`--var key=value` / `--var.key=value` で追加変数も渡せ、組み込みの `templatePath` 等より優先される
- 出力: `path.templateFillOutputRoot` 配下に**元の相対ディレクトリ構造を維持**して UTF-8 で書き出す（既存ファイルは上書き）
- 安全策: 出力先を入力ルート配下に置くことは禁止。填充後にプレースホルダが残っていれば `[WARN]` で表示するが、自動再試行はしない

```
template-fill --feature sample-order-query-service --var.company=自社名
```

設定項目: `path.templateFillRoot` / `path.templateFillOutputRoot` / `templateFill.prompt` / `templateFill.maxFiles`。
prompt を差し替えれば、Java / XML / JS / Markdown などテンプレートの種類ごとに填充ルールを変えられます（ファイルごとに prompt を選ぶ仕組みではありません）。

## Java 代码智能检索（JDT AST + SQLite）

对 `path.javaIndexRoot` 指定的文件夹递归扫描 `.java`，用 JDT AST 提取类/接口/enum、方法签名与本文、字段、import、方法调用与名字引用，写入本地 SQLite（`path.javaIndexDb`）。**解析和索引全部是确定性代码，不调用大模型，数据不出本机。** 用 SHA-256 判断文件是否变化，`java-index refresh` 只重解析变更文件，`build` 为全量重建。

### function tool（12 个）

| 工具 | 用途 |
| --- | --- |
| `list_java_files` | 列出索引中的 Java 文件 |
| `read_java_file` | 读取 Java 文件（索引优先，拒绝根目录外路径） |
| `search_java_code` | 横断关键字搜索（类名/方法名/Javadoc/源码） |
| `find_class` | 查找类、接口、enum |
| `find_method` | 查找方法与签名 |
| `find_references` | 查找名字引用；`includeCalls=true` 时返回调用关系 |
| `get_class_structure` | 类的 import / 字段 / 方法结构 |
| `find_similar_method` | 基于确定性 token Jaccard 的相似方法检索 |
| `get_project_rules` | 读取五级设计 md 作为编码规范 |
| `propose_java_edit` | 生成编辑提案（**不写盘**） |
| `get_diff` | 查看提案的 before/after 行级 LCS 差异 |
| `approve_java_edit` | 人工审批后写盘（带备份、原子替换、索引更新） |

这 12 个工具已注册进 `agent-gen` 的 Java 工具白名单，可用 `tools` 查看。所有结果都有字符上限、固定排序（分数降序 → 路径 → 行号），保护 14B 上下文。

### 编辑审批的状态机

`PROPOSED → DIFFED → APPROVED → WRITTEN`（或任意阶段 `REJECTED`），非法跳转直接报错。

- `propose_java_edit` 会生成提案 ID 和**审批令牌**。令牌只显示在 CLI 输出里，不进入模型的 tool 结果。
- `approve_java_edit` 必须同时提供提案 ID、CLI 显示的令牌、`approve=true`；令牌错误/缺失即拒绝写盘，模型无法自行批准。
- 写盘前自动备份 `.bak`，用临时文件 + 原子替换，成功后刷新索引。重复审批会被状态机拦下。
- 方式二选一：`--newContent <完整Java>` 或 `--methodName <名> + --newMethod <方法>`；方法级替换使用括号感知的确定性源码编辑器。

### CLI 示例

```
java-index build                           # 首次构建索引
java-index stats                           # 文件/类/方法/字段/import/调用/引用计数
java-files --prefix Order
java-search --query customerId
java-class --class OrderRepository
java-method --query find
java-refs --symbol findNameById --calls
java-similar --method findByOrderId
java-rules
propose-edit --path com/.../CustomerService.java --summary "..." --rationale "..." \
             --methodName findCustomerName --newMethod "..."
diff --proposal <提案ID>
approve-edit --proposal <提案ID> --token <CLIが表示したトークン>
```

配置：`path.javaIndexRoot` / `path.javaIndexDb` / `javaIndex.maxFiles` / `javaIndex.maxFileChars` / `javaIndex.skipDirs` / `javaIndex.maxResultChars` / `javaIndex.maxResults` / `javaIndex.similarTopN` / `edit.requireApprovalToken` / `edit.backupSuffix`。

## 注意

- 模型输出必须返回结构化 JSON（分类/映射）或规定的分段标记（Java 草稿）；解析失败会明确报错，不会猜测。
- `ADD_METHOD` / `MODIFY_METHOD` 使用括号感知的源码编辑器做确定性插入/替换；找不到目标方法会失败并列出已检测到的方法名。
- 生成结果一律需要人工审核后再合入项目。
