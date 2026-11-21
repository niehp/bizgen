# 项目长期约定

## 项目性质
- 日系 SI / CRM・ERP 方向的「根据既有用例代码生成新需求代码」代码生成项目。
- 既存 Java 资产：`CodeIndexManager.java`（JavaParser + Lucene + SQLite 代码索引/检索）、`PostgreToSQLite.java` 实际类名 `PgToSqliteJapaneseLuceneSync`（PostgreSQL→SQLite + JapaneseAnalyzer 日文索引）。纯 Java，未见 pom.xml / Spring Boot 结构。
- 三份规范文档并存：`结构化需求编写规范.md`（中文通用方法论）→ `AI向け構造化仕様書ガイド.md`（日文落地版）→ `Excel仕様書_構造化手順書.md`（上游 Excel ETL）。

## 文档与需求约定
- 需求 = 工作契约而非说明文；事实/材料/要求物理隔离；唯一 primary + Delta 差分。
- API 白名单优于黑名单；硬约束首尾各一次；验收条件必须可判定。
- 式样书日文术语不译为中文/英文，日式需求文档一律用日文仕様書文体。
- L1 引用 L0 不复制。
- 代码生成的 md 约定：`specs/common/` 是共通 md 文件夹，自动递归读入喂给模型；**一个级别一个文件夹，文件夹内可放任意多个 md，全部读入**（`java-common/` `project-common/` `feature/` `class/` 子目录映射层级，根目录 md 算项目共通，`_*.md` 跳过；同级按相对路径排序确定性加载）；不需要命令行 `--layer`。方法级 md 作为位置参数单独给出，是签名唯一真源，禁止放进共通目录。变量在共通 md 用 `{{key=value}}` 定义、各级用 `{{key}}` 引用；优先级 `--var` ＞ 共通定义 ＞ `{{key:default}}` ＞ 报错；按共通→方法顺序解析，喂给模型前全部替换为具体值、不残留 `{{...}}`；方法级禁止定义变量。
- **变量 6 种命名风格**（`mdgen/CaseVars`，与 `template-studio/shared/casevars.js` 同语义）：`{{keyUpper}}` 首字母大写 / `{{keyLower}}` 首字母小写 / `{{keyPascal}}` 大驼峰 / `{{keyCamel}}` 小驼峰 / `{{keyUpperSnake}}` 大蛇形 / `{{keySnake}}` 小蛇形。长后缀优先判定；`application_id` ⇔ `applicationId` 按 camel 规范化互匹，撞多个定义报暧昧；优先级 显式定义 ＞ 后缀派生 ＞ camel 匹配 ＞ 默认值 ＞ 报错。SQL bind marker 名恒为小驼峰：`{{application_id}}` → `/*applicationId*/'...'`，值仍按原参照名解析。

## 架构选型约定（2026-11-20 确立）
- 确定性优先：流程控制、参数校验、状态跳转 100% 由 Java 代码承担；LLM 仅作类型化叶节点（语义映射、意图分类、代码草稿、润色），不得决定路由。
- 数据不出内网：模型本地部署，可观测与存储全部自托管，不用云端 LLM/托管 tracing。
- 强类型单一真源：Java record 派生 JSON Schema，配合 vLLM XGrammar 约束解码保证输出结构合法；schema 扁平 ≤2 层，分类用 enum。
- 代码生成：LLM 产出结构化补丁，Java 侧用 AST 模板渲染；编译/静态检查/单测/API 白名单作为确定性闸门，人工审核后方可合并。
- 大模型信息由 `config/agent.properties` 配置驱动（`llm.mode=mock|live`、`llm.base-url`、`llm.model`、`common.dir`），`--live` 仅兼容覆盖非推荐；`--config <file>` 换文件；未知键/非法 mode 拒绝，缺项才回退环境变量。
- SQL 生成走独立 `sqlgen/` 包，共**三条链路**：① `SqlgenFromMd` 纯确定性、② `SqlDraftFromMd` LLM 生成草稿 md、③ `SqlLlmFromMd` LLM 直接生成 SQL 文本。①② 的最终 SQL 由 Java `SqlRenderer` 确定性组装（SQL 结构化、模型会引拼写/分号错误）；③ 按用户最新要求由大模型基于「prompt 内的 SQL 雏形 + md 表结构」产出 SQL，但**草稿先落盘人工审核，且必须过 `SqlTextGate` 闸门才落正式 .sql**。种别/模板/表结构/变量/闸门/落盘判定始终在 Java 侧，模型不决定路由。表结构 md（`## テーブル` + `## カラム` 表格）+ 请求 md（`## 対象` 種別/テーブル、`## 選択列`/`## 設定値`/`## 条件`/`## 並び順`），可写同一文件；强类型 TableSchema/SqlRequest → SqlRenderer；闸门=表名一致、列存在、种别必须校验、DELETE 必须有条件、注入片段拒绝；落盘 `<table>_<kind>.sql`。
  - SQL 变量格式 `/*变量名*/'值'`（SQL 注释+字面量，可直接执行也可替换为绑定参数）：md 中写 `{{key}}`，CLI 在**变量替换前**调 `SqlVariables.normalizeRequest` 改为 `/*key*/'{{key}}'`，替换后得到 `/*key*/'APP001'`；也允许 md 直接写该格式。变量名限 Java 标识符，ORDER BY 禁用；注入闸门只放行合法形式。
  - 表结构库：**一个表一个 md 文件**，模板 `specs/tables/_template.md`（`_` 开头跳过），示例 `receipt_detail.md` / `receipt_header.md`；`TableSchemaLibrary` 按表名索引（大小写不敏感），重复表名/模板占位符残留/非标识符列名均拒绝；`table.dir` 配置（env `BIZGEN_TABLE_MD_DIR`）/ `--tables-dir` 可改。SQL 请求只写 `## 対象` 时按表名自动取表结构，优先级 `--schema` ＞ 表结构库 ＞ 共通 md ＞ 请求文件。LLM 草稿也把库中表结构作为上下文，mock 直接采用该文件（不猜列名）。
  - prompt 模板也外置 md：`specs/prompts/sql-draft.md`（`--prompt <file>` 可换，缺省文件不存在回退内置）。占位符只允许大写 `{{DESCRIPTION}}/{{VARS}}/{{CONTEXTS}}/{{TABLES}}`（Java 确定性填充）；小写 `{{key}}` 是说明文字不替换；`<!-- ... -->` 注释剥离；占位符缺失/未知/填充残留均拒（`SqlPromptTemplate`）。
  - 「表结构 + SQL 大概定义」可由 LLM 生成：`SqlDraft(record) → JSON Schema → guided_json`，模型只产出草稿 md（tableMd/requestMd/note），草稿先落盘供人工审核，再走同一确定性闸门出 SQL；mock/live 由 `config/agent.properties` 决定，`SqlDraftFromMd` CLI 执行，`--no-render` 只看草稿。
  - SQL 变量按**列型分三格式**（`SqlVariableType.of(sqlType)`，未知型保守取 CHAR）：字符 `/*变量*/'xx'`、日期 `/*变量*/CURRENT_DATE`、数字 `/*变量*/9`。`SqlVariables.normalizeRequest(raw, schema)` 在变量替换前按该行左辺列型包装（旧 `normalizeRequest(text)` 兼容为全字符型）；三格式均合法 SQL 且工具可换绑定参数，`gate` 只放行这三种严格形式（ORDER BY 禁用、值查注入）。共通定义见 `specs/common/project-common/05-sql-vars.md`。
  - 表结构 md 支持日语逻辑名：`## テーブル` 加 `- テーブル日本語名: xxx`，`## カラム` 表格加 `日本語名` 列（5 列格式；旧 4 列兼容）。`TableColumn.japaneseName` / `TableSchema.tableJapaneseName`；`NameMapping.from(库)` 构建 日语→英语 对照（长名优先替换；同日语名指向不同英语名即拒）。概要/prompt/共通 md 可用日语表名/字段名，**喂大模型前** `translate` 全部替换为英语物理名，模型只看到英语名。
  - 分种别 prompt 模板：`specs/prompts/sql-select.md`（SQL 雏形含 INNER JOIN，支持多表结合）/`sql-update.md`/`sql-delete.md`，各含 SQL 雏形 + 4 个大写占位符；`SqlPromptTemplate.defaultKindPath(kind)` 确定性选模板，`--prompt` 可覆盖（不存在即拒）。
  - **喂给 LLM 的表结构只限使用表**（`sqlgen/TableSelector`）：按 `## 対象 - テーブル`（日语）→ 翻译后英语表名 → 原文日语表名三规则确定；选不到才回退全表并日志标注。`TableSchemaLibrary.subset` 造 scoped 库/对应表给 prompt；闸门仍传全库，模型偷加表照样拦。链路② `SqlDraftFromMd` 同样只喂 `detectTable` 指名的表。
  - `SqlTextDraft(sql,note)` → JSON Schema → `guided_json`；`SqlTextGate` 闸门=单语句/末尾一个分号/首关键字与种别一致/禁 DDL・UNION・注释断片/FROM・JOIN・UPDATE 表必须在库/`别名.列` 必须在该表存在/未知标识符拒/DELETE・UPDATE 必须 WHERE/变量三格式合法。`SqlLlmFromMd` 草稿 `_draft.sql`+`_note.md` 必落盘（闸门拒否也保留）→ 闸门 → `<主表>_<kind>.sql`；示例 `specs/sql/llm-{select-join,update,delete}.md`。
