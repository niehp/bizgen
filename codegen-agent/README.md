# 确定性代码生成骨架（record + guided_json + AST 闸门 + Temporal）

核心原则不变：**流程控制、参数校验、状态跳转 100% 由 Java 代码承担；LLM 只是类型化叶子节点。**

## 环境

- JDK 18：`C:\java\jdk-18.0.2.1`（本机默认 java 是 JDK 8，不能用）
- 无 Maven/Gradle：`javac` 直接编译；第三方 jar 由 `fetch-deps.sh` 拉到 `lib/`
- 首次联网拉取依赖（之后可离线）：`bash fetch-deps.sh`

## 一键编译

```bash
cd codegen-agent
bash fetch-deps.sh
CP=$(ls lib/*.jar | tr '\n' ';')
/c/java/jdk-18.0.2.1/bin/javac -encoding UTF-8 -cp "$CP" -d out $(find src/main/java -name '*.java')
```

## 三件事 + 基础骨架的验证入口

| 演示类 | 验证内容 | 是否需要模型/Server |
|---|---|---|
| `com.walk_nie.bizgen.agent.Main` | record→Schema→guided_json→回填→闸门 + 负向自检 | 否（--live 才需要 vLLM） |
| `com.walk_nie.bizgen.agent.ast.AstDemo` | patchHunks→JavaParser AST 渲染→javac 编译闸门；语法错/语义错均被拦截 | 否 |
| `com.walk_nie.bizgen.agent.index.WhitelistDemo` | 修复后的 CodeIndexManager 建索引→动态白名单→越界 API 被拒 | 否 |
| `com.walk_nie.bizgen.agent.temporal.WorkflowDemo` | Temporal 内存环境跑完整 workflow + 审批 signal 挂起/唤醒 + query | 否 |
| `com.walk_nie.bizgen.agent.mdgen.CodegenFromMd` | 读方法概要 md → 生成 Java（三模式；共通 md 自动加载；大模型信息配置驱动） | 否（`llm.mode=live` 才需要 vLLM） |
| `com.walk_nie.bizgen.agent.mdgen.MdgenDemo` | md 链路自检：负向闸门 + 正向检查（含变量/分层/共通文件夹自动加载） | 否 |
| `com.walk_nie.bizgen.agent.sqlgen.SqlgenFromMd` | 读表结构 md + SQL 要求 md → 生成 SELECT/UPDATE/DELETE SQL（按列型出 3 种变量格式）并保存 .sql | 否（纯确定性） |
| `com.walk_nie.bizgen.agent.sqlgen.SqlDraftFromMd` | 自然语言概要 → 大模型生成表结构/SQL 草稿 → 闸门 → SQL 落盘 | 否（`llm.mode=live` 才需要 vLLM） |
| `com.walk_nie.bizgen.agent.sqlgen.SqlLlmFromMd` | **分种别 prompt（SQL 雏形）+ 表结构 → 大模型直接生成 SQL** → 草稿审核 → 闸门 → 落盘；日语名自动转英语物理名、SELECT 支持多表 JOIN | 否（`llm.mode=live` 才需要 vLLM） |
| `com.walk_nie.bizgen.agent.sqlgen.SqlgenDemo` | SQL 链路自检：3 格式变量、日语名映射、分种别模板、SQL 文本闸门、负向闸门 | 否 |

运行示例：

```bash
CP=$(ls lib/*.jar | tr '\n' ';')
/c/java/jdk-18.0.2.1/bin/java -cp "out;$CP" com.walk_nie.bizgen.agent.ast.AstDemo
/c/java/jdk-18.0.2.1/bin/java -cp "out;$CP" com.walk_nie.bizgen.agent.index.WhitelistDemo
/c/java/jdk-18.0.2.1/bin/java -cp "out;$CP" com.walk_nie.bizgen.agent.temporal.WorkflowDemo
```

> 中文控制台乱码是 Windows 代码页问题，执行 `chcp 65001` 可解，不影响 JSON/类型/断言。

## 三件事落地说明

### 1. AST 渲染 + 编译闸门（`ast/` 包）

- `AstPatcher` 把 `CodeDraft.patchHunks`（目标方法 / 语句 / 位置）用 JavaParser 落进 AST，import 也走 AST 操作，不拼字符串。
- 模型给的 statement 若不是合法 Java，JavaParser 当场拒绝；`CompileGate` 用 JDK 内置 `JavaCompiler` 在内存中编译，引用不存在的变量等语义错误也会被拦截。
- SpotBugs/PMD 是独立 CLI 工具（本机 PATH 无），留作 CI 步骤接入；`javac` 闸门已覆盖语法与类型安全这条硬底线。

### 2. 动态 API 白名单（`index/` 包）

- 修复了既存 `CodeIndexManager.java` 的缺陷：`BooleanQuery` 错包、JavaParser/JDBC 的 `Statement` 命名冲突、`List>` 泛型丢失、Lucene 9 的 `IndexWriterConfig` 非 AutoCloseable、`totalHits.value`。并加了 `setStorage()` 便于指向任意索引库。
- `ApiWhitelist` 从 `methods_index` 表读取已索引方法，登记「全限定名 / 类名.方法名 / 裸方法名」三种粒度。新增方法只要被索引器扫描进库就自动进白名单，模型无法干预。
- 日文注释检索本应在 `PgToSqliteJapaneseLuceneSync`（JapaneseAnalyzer），代码方法体索引用 StandardAnalyzer 即可。

### 3. Temporal Activity + Workflow（`temporal/` 包）

- `CodeGenActivities`：所有 LLM 调用与副作用（mapSpec / draftPatch / renderPatch / loadWhitelist / validateApis / compileCode / recordApproval）。**Activity 结果记入历史，replay 不重跑**，LLM 的非确定性因此不会破坏 Temporal 的确定性前提。
- `CodeGenWorkflow`：纯确定性编排（映射→白名单校验→草稿→渲染→编译→等待审批→记录）。不调 LLM、不读时间、不做 IO。编译失败由 Activity 抛异常触发 3 次重试策略；人工审批用 `approve()` signal 挂起，可等任意时长；`status()` query 供审核界面查状态。
- `WorkerMain` / `WorkflowStarter`：生产入口，连本地 Temporal Server（默认 127.0.0.1:7233）。`WorkflowDemo` 用 `TestWorkflowEnvironment` 离线验证全流程。

## 从方法概要 md 生成 Java 代码（`mdgen/` 包）

「需求写进 md → 生成代码」的主用例。

### 五级分层 md + 变量（本次新增）

md 按内容分五级，可**同时读入多个级别喂给大模型**。越靠上越是「必须遵守的共通约束」，越靠下越是「本次要造的东西」：

| 级别 | `--layer` 值 | 示例 | 用途 |
|---|---|---|---|
| java共通 | `java-common` | `specs/common/java-common/*.md`（多个文件） | 全 Java 通用规约（命名/禁用 API/日期处理） |
| 项目共通 | `project-common` | `specs/common/project-common/*.md`（多个文件） | 项目架构与业务约束（`{{applicationId}}`） |
| 功能 | `feature` | `specs/common/feature/*.md`（多个文件） | 功能级需求（匹配规则/顺序保证） |
| Java类 | `class` | `specs/common/class/*.md`（多个文件） | 目标类职责与边界 |
| 方法 | 位置参数 | `specs/layers/5-method.md` | 本次方法的签名/逻辑/受入基準 |

- **变量在喂给大模型之前全部替换完成**（共通 md 定义）：
  - 在共通 md（如 `specs/common/project-common/01-application.md`）写 `{{applicationId=APP001}}` 即完成**定义**；
    任意级别 md 里用 `{{applicationId}}` 引用。加载按「共通 → 功能 → 类 → 方法」顺序进行，
    定义先生效，最终 prompt 里只剩具体值，**不残留 `{{...}}`**——模型看不到占位符，无法猜值。
  - 也支持 `{{applicationId:APP001}}` 内联默认值；`--var applicationId=APP999` 可覆盖共通定义。
  - 优先级：`--var` ＞ 共通 md 定义 ＞ `{{key:default}}` 默认值 ＞ 报错。
  - 定义只允许写在**共通侧**（java共通/项目共通/功能/类文件夹）；方法级 md 里写 `{{key=value}}` 会被明确拒绝，避免下位覆盖共通契约。
  - **未解决的变量一律报错并列出全部缺失项**，绝不静默留空。所有级别共享一张变量表：上位级定义、下位级引用也能正确替换。
- **变量 6 种命名风格自动匹配（`CaseVars`，喂模型前完成）**：定义一个基础变量，不用另外输入就能引用它的派生形式：
  | 写法 | 含义 | 例（`org_tree`） |
  |---|---|---|
  | `{{keyUpper}}` | 首字母大写（只动第一个字符） | `Org_tree` |
  | `{{keyLower}}` | 首字母小写（只动第一个字符） | `org_tree` |
  | `{{keyPascal}}` | 大驼峰 PascalCase | `OrgTree` |
  | `{{keyCamel}}` | 小驼峰 camelCase | `orgTree` |
  | `{{keyUpperSnake}}` | 大蛇形 UPPER_SNAKE | `ORG_TREE` |
  | `{{keySnake}}` | 小蛇形 snake_case | `org_tree` |
  - 与 `template-studio/shared/casevars.js` 的既有语义完全一致；按「长后缀优先」判定（`UpperSnake` 先于 `Upper`），单独的后缀名不视为派生。
  - 表记差异自动归一：`{{application_id}}` 与 `{{applicationId}}` 按 camel 规范化互相匹配；多个定义规范化后撞在同一名字会**明确报暧昧并拒绝**，不静默挑一个。
  - 优先级：**显式定义 ＞ 后缀派生 ＞ camel 匹配 ＞ `{{key:default}}` ＞ 报错**。明示定义的 `{{keyCamel}}` 不会被派生值覆盖。
  - SQL 的绑定变量名一律按**小驼峰**输出：`{{application_id}}` → `/*applicationId*/'APP001'`（值的解析仍用原参照名，值不变）。
- **确定性**：只有方法级走 `SpecParser` 解析成 `MethodSpec`（签名只来自它）；其余四级作为上下文文档，
  按「共通 → 具体」顺序注入 prompt，并明确告诉模型**上位约束优先**。模型改不了任何签名。
- **共通 md 文件夹自动加载（不需要命令行指定）**：**一个级别一个文件夹，文件夹里放任意多个 md，全部自动读入并喂给模型**。
  ```
  specs/common/
  ├── java-common/      01-naming.md  02-exception.md  03-datetime.md
  ├── project-common/   01-application.md  02-money.md  03-external-api.md  04-logging.md
  ├── feature/          01-match-rule.md  02-unmatched.md
  └── class/            01-responsibility.md  02-boundary.md
  ```
  子目录名决定级别：`java-common/`、`project-common/`、`feature/`、`class/`；
  直接放在根目录的 `.md` 按「项目共通」处理；`_*.md` 作为说明文件跳过。
  文件夹内文件按相对路径排序（建议 `01-`、`02-` 前缀控制顺序），文脉顺序固定可复现。
  放 `method/` 子目录会被明确拒绝——方法级必须作为位置参数给出，避免悄悄替换签名真源。
  可用配置文件 `common.dir`、`--common-dir <dir>` 或环境变量 `BIZGEN_COMMON_MD_DIR` 改目录（优先级 CLI ＞ 配置 ＞ 环境变量），`--no-common` 完全关闭自动加载。
- 只传方法级 md（旧用法）完全兼容，等价于只有 METHOD 级。

```bash
CP=$(ls lib/*.jar | tr '\n' ';'); J=/c/java/jdk-18.0.2.1/bin/java
# 共通 md 已放进 specs/common/，变量也在其中定义；--layer / --var 都不必写
$J -cp "out;$CP" com.walk_nie.bizgen.agent.mdgen.CodegenFromMd \
  specs/layers/5-method.md \
  --out generated/layered

# 需要覆盖变量值时才用 --var；需要额外追加文件时才用 --layer
$J -cp "out;$CP" com.walk_nie.bizgen.agent.mdgen.CodegenFromMd \
  specs/layers/5-method.md --var applicationId=APP999 \
  --layer feature=specs/layers/3-feature.md \
  --out generated/layered
```

### 三种落盘模式

由 `## 対象` 里的 `モード` 决定：

| モード | 作用 | 示例 spec |
|---|---|---|
| `NEW_FILE`（默认，可省略） | 生成一个全新 Java 类 | `specs/newFile.md`、`specs/matchPayments.md` |
| `ADD_METHOD` | 往既存 `.java` 加方法（原方法全部保留） | `specs/addMethod.md` |
| `MODIFY_METHOD` | 替换既存 `.java` 里的方法（签名+方法体） | `specs/modifyMethod.md` |

追加/修正时 `## 対象` 必须写 `- 対象ファイル: <既存.java 路径>`；既存样例在 `specs/existing/`。
生成结果一律写到 `--out` 目录（默认 `generated/`），**不覆盖既存源文件**；若输出路径与源文件相同会自动改名 `_generated.java`。

**1. 写规格 md**：四个固定节——
`## 対象`（クラス/メソッド/戻り値/引数必填，可选 モード/対象ファイル/パッケージ）、
`## 処理概要`（自然语言概要）、`## 使用可能API`（白名单）、`## 受入基準`（可判定条件）。
节标题是解析器识别的固定标识，不要改。

**2. 离线跑通链路**（mock 方法体，不需要模型）：

```bash
CP=$(ls lib/*.jar | tr '\n' ';')
J=/c/java/jdk-18.0.2.1/bin/java
for m in newFile addMethod modifyMethod; do
  $J -cp "out;$CP" com.walk_nie.bizgen.agent.mdgen.CodegenFromMd specs/$m.md --out generated/$m
done
# 三个生成文件均已过编译闸门；可独立再编译验证：
/c/java/jdk-18.0.2.1/bin/javac -encoding UTF-8 -d /tmp/v generated/$m/ReceiptReconciliationService.java
```

**3. 连本地模型真实生成**：改配置文件 `config/agent.properties` 里的 `llm.mode=live`（**不再需要 `--live`**），先起 vLLM。
模型产出方法体 + **四层日语注释文字**（都写「做什么、为什么」）：
`packageComment`（包）、`classComment`（类）、`methodComment`（方法目的）、
`paramComments`（逐参数）、`returnComment`（返回值）、`lineComments`（逐行）。

**配置文件驱动的大模型设置**（`config/agent.properties`，大模型信息从这里取）：
```properties
llm.mode=mock                                  # mock=离线骨架 / live=调用本地 vLLM
llm.base-url=http://127.0.0.1:8000             # OpenAI 兼容端点（/v1/chat/completions 自动补）
llm.model=Qwen/Qwen2.5-Coder-14B-Instruct      # 与 vLLM --served-model-name 一致
common.dir=specs/common                        # 共通 md 文件夹
table.dir=specs/tables                         # 表结构库（一个表一个文件）
```

- `--config <file>` 可换配置文件；**未知键、非法 `llm.mode` 立即拒绝**，避免拼错后静默走默认值。
- 配置文件缺项时才回退到环境变量 `VLLM_BASE_URL` / `VLLM_MODEL` / `BIZGEN_COMMON_MD_DIR` / `BIZGEN_TABLE_MD_DIR`，再缺则内置默认值。
- 默认配置文件不存在时用内置默认值（mock）；显式 `--config` 指向不存在的文件则报错。
- `--live` 仅作为旧脚本兼容覆盖保留（非推荐），新写法一律由 `llm.mode` 决定。

**4. 三层标准 Javadoc 如何生成**（关键分工）：LLM 只写注释**文字**；**Javadoc 的形由 Java 确定性生成**。
`JavadocBuilder` 用 JavaParser 的 `setJavadocComment()` 把注释附加到 AST 节点（不拼字符串）：

| 注释 | 落盘位置 | 由谁定形 |
|---|---|---|
| 包注释 | `generated/<模式>/package-info.java` | Java（`PackageDeclaration.setComment`） |
| 类注释 | 类声明上方 `/** ... */` | Java（`setJavadocComment`） |
| 方法注释 | 方法上方 `/** ... */` + `@param`/`@return` | Java；**`@param` 名取自真实签名** |
| 方法体内 | 语句上方 `//` | Java（`setLineComment`） |

**为什么 `@param` 名不让模型写**：实测 JavaParser 不校验 `@param` 名与真实参数是否一致，
若采用模型写的 tag 名，错误会绕过编译闸门进入评审。所以模型写的 `@xxx` 行会被剥离，
tag 一律由 Java 按 `MethodSpec` 的实际参数名重建；参数数≠注释数也会被拒。
生成示例（`MODIFY_METHOD`）：
```java
/**
 * ReceiptReconciliationService は matchPayments を中心とした業務処理を提供する（責務を一箇所に集めて変更箇所を限定するため）
 */
public class ReceiptReconciliationService {
    /**
     * matchPayments は List<String> を返す（仕様: ...一致項目を返す実装にする。）
     * @param payments List<String> payments を受け取る（処理対象を明示して誤用を防ぐ）
     * @return List<String> を返す（呼び出し側が結果を判定できるようにするため）
     */
    public List<String> matchPayments(List<String> payments) { ... }
}
```
> `ADD_METHOD` 模式会**保留既存类原有的类注释**（不覆盖既有资产），只给新增方法加 Javadoc。

**5. 负向自检**（七道闸门：格式 / 対象ファイル欠落 / 越界 API / 非法 Java / 引数コメント数不一致 / 同名追加 / 目标方法不存在；
另含五道正向检查：伪 `@param` 被剥离、真实参数名生成、非 void 生成 `@return`、类 Javadoc、包说明保留）：

```bash
/c/java/jdk-18.0.2.1/bin/java -cp "out;$CP" com.walk_nie.bizgen.agent.mdgen.MdgenDemo
```

边界：**解析、签名、三层 Javadoc 的形、白名单校验、编译、落盘全由 Java 确定性完成**；
LLM 只产出 `MethodBody`（注释文字 + 方法体语句），经 `RecordSchema` 的 guided_json 约束结构。
模型无权改类名/方法名/参数/返回类型/参数 tag 名——这些只来自 md。源码注释统一用日语，写清「何をするか／なぜ」。

## 从表结构 md 生成 SQL（`sqlgen/` 包）

按 md 定义的表结构生成 `SELECT` / `UPDATE` / `DELETE` 并保存为 `.sql` 文件。包内提供**三条链路**，按「模型介入程度」递进，闸门始终是确定性的：

| 链路 | 入口 | 模型负责 | 最终 SQL 由谁组装 |
|---|---|---|---|
| ① 纯确定性 | `SqlgenFromMd` | 不参与 | Java（`SqlRenderer`） |
| ② 草稿 md | `SqlDraftFromMd` | 表结构 + SQL 要求的大概定义（md） | Java（`SqlRenderer`） |
| ③ **直接生成 SQL** | `SqlLlmFromMd` | **SQL 文本本身**（基于 prompt 里的 SQL 雏形 + 表结构） | 大模型，但必须先落草稿供人工审核、再过 `SqlTextGate` |

③ 是本轮新增能力：prompt 模板按种别拆分、SELECT 支持多表结合，表名/字段名可用日语并在喂模型前转成英语物理名。
无论哪条链路，**种别路由、变量值、表结构、注入校验、落盘判定都由 Java 决定**，模型不能改路由。

**md 格式**（示例 `specs/sql/select.md`、`update.md`、`delete.md`，表结构与要求写在同一文件；也可拆成两文件用 `--schema` 指定表结构）：
```markdown
## テーブル
- テーブル名: receipt_detail

## カラム
| カラム名 | 型 | NOT NULL | PK |
| --- | --- | --- | --- |
| id | bigint | YES | YES |
| application_id | varchar(32) | YES | NO |

## 対象
- テーブル: receipt_detail
- 種別: SELECT          # SELECT / UPDATE / DELETE

## 選択列               # SELECT 用，空或 * 表示全部
- id
- application_id

## 設定値               # UPDATE 用，"列 = 値"
- status = 'PAID'

## 条件                 # WHERE，多条以 AND 连接；DELETE 必须写
- application_id = {{applicationId}}   # → SQL: /*applicationId*/'APP001'
- status = 'PAID'

## 並び順               # 可选
- payment_date DESC
```

**表结构库：一个表一个文件**（`specs/tables/`）
- 模板 `specs/tables/_template.md` 复制后重命名为 `<表名>.md`；`_` 开头的文件不会被读取。
- 每文件只放一张表：`## テーブル` + `## カラム`，文件名与表名一致。示例：
  `specs/tables/receipt_detail.md`、`specs/tables/receipt_header.md`。
- 表结构 md 支持**日语逻辑名**：`## テーブル` 加 `- テーブル日本語名: 入金明細`，
  `## カラム` 表格加 `日本語名` 列（`| カラム名 | 日本語名 | 型 | NOT NULL | PK |`）。
  旧 4 列格式也兼容（日本語名视为空）。`NameMapping` 据此构建「日语名 → 英语物理名」对照表。
- 列的 `型` 还决定 SQL 变量的包装形式（字符/日期/数字三选一，由 Java 判定，见下）。
- 模板 `specs/tables/_template.md` 已按新格式写好，复制后重命名即可。
- `SqlgenFromMd` 按请求 md 的 `## 対象 - テーブル` 自动从库中取对应表结构（大小写不敏感）；
  优先级 `--schema` 显式指定 ＞ 表结构库 ＞ 共通 md 自动检测 ＞ 请求 md 自身。
- 目录可用 `config/agent.properties` 的 `table.dir` 或 `--tables-dir` 改。
- 加载闸门：重复表名拒绝、模板占位符 `<table_name>` 未改拒绝、表名/列名必须是标识符。
- `SqlDraftFromMd` 会把库中表结构作为上下文喂给模型，mock 模式直接用该 md 做草稿——模型不用猜列名。

**SQL 变量：按列型分三种格式**（`/*变量名*/` 是 SQL 注释，SQL 可直接执行，工具侧可换成绑定参数）

| 列的 SQL 型 | 形式 | 例 |
|---|---|---|
| 字符型 `varchar` / `char` / `text` | `/*变量*/'xx'` | `/*applicationId*/'APP001'` |
| 日期型 `date` / `timestamp` | `/*变量*/CURRENT_DATE` | `/*paymentDate*/CURRENT_DATE` |
| 数字型 `bigint` / `decimal` / `int` | `/*变量*/9` | `/*minAmount*/9` |

- md 里只需写 `{{key}}`；`SqlgenFromMd` 会**先按表结构查出该列的 SQL 型**，在变量替换之前
  包装成对应格式（`normalizeRequest(raw, schema)`），再解析变量值：
  `application_id = {{applicationId}}` → `/*applicationId*/'APP001'`，
  `payment_date = {{paymentDate}}` → `/*paymentDate*/CURRENT_DATE`，
  `amount >= {{minAmount}}` → `/*minAmount*/9`。
- 也可以直接在 md 写完整形式。变量定义仍放在共通 md（`specs/common/project-common/05-sql-vars.md`
  定义了 `{{receiptDate=CURRENT_DATE}}`、`{{minAmount=9}}` 等），`--var` 可覆盖。
- 闸门只放行这三种严格形式：变量名限 Java 标识符，字符值在单引号内、日期值是无引号的日期
  关键字/函数、数字值是无引号数值；ORDER BY 禁用变量，值里的 `;` `--` `DROP` 等一律拒绝。

**运行**（变量仍来自共通 md 的 `{{applicationId=APP001}}`，`--var` 可覆盖）：
```bash
CP=$(ls lib/*.jar | tr '\n' ';'); J=/c/java/jdk-18.0.2.1/bin/java
for m in select update delete; do
  $J -cp "out;$CP" com.walk_nie.bizgen.agent.sqlgen.SqlgenFromMd \
    specs/sql/$m.md --out generated/sql
done
# 生成：generated/sql/receipt_detail_{select,update,delete}.sql

# 表结构只放 specs/tables/、请求 md 不写表结构时，从库按表名自动取
$J -cp "out;$CP" com.walk_nie.bizgen.agent.sqlgen.SqlgenFromMd \
  specs/sql/select-from-library.md --out generated/sql-library
```

**确定性闸门**（`SqlRenderer` + 两个 Parser，全部在落盘前拦截）：
- 表名一致（要求 vs 表结构）、SELECT/UPDATE/SET/WHERE/ORDER BY 的列都必须在表结构中存在；
- 种别只认 SELECT/UPDATE/DELETE；SELECT 必须有选择列、UPDATE 必须有设置值、**DELETE 必须有条件**（防全表删除）；
- 注入防护：只放行 3 种严格形式的绑定变量，其余 `;` / `--` / `/*` / `DROP` / `UNION` 等片段一律拒绝；
  表结构行缺列或格式错误直接拒。

### 由大模型生成「表结构 + SQL 大概定义」（`SqlDraftFromMd`）

输入只用自然语言描述概要，LLM 产出**草稿 md**（不是最终 SQL）；草稿落盘供人工审核，
再经同一套确定性闸门渲染成 SQL。模型只做语义映射，无权决定种别路由或最终 SQL。

**概要 md 示例** `specs/sql/draft-input.md`：
```markdown
# 生成したい SQL の概要
テーブル: receipt_detail
対象アプリの明細を支払日の降順で取得したい。application_id はバインド変数にすること。
```

**运行**（大模型信息仍从 `config/agent.properties` 读取：`llm.mode=mock|live`）：
```bash
$J -cp "out;$CP" com.walk_nie.bizgen.agent.sqlgen.SqlDraftFromMd \
  specs/sql/draft-input.md --out generated/sql-draft
# 草稿：generated/sql-draft/draft-input_{table,request,note}.md
# SQL ：generated/sql-draft/receipt_detail_select.sql
# 只看草稿、不生成 SQL：追加 --no-render
```

**流程**
1. 从配置读 `llm.mode` / `llm.base-url` / `llm.model`，共通 md 的变量与约束一并注入 prompt。
2. `SqlDraft(tableMd, requestMd, note)` record 派生 JSON Schema，用 `guided_json` 约束输出结构。
3. 模型输出先做结构校验（两个 md 必须能被 parser 读），再落盘为草稿文件——**草稿是人审核对象**。
4. 草稿经正规化 → 变量替换 → 解析 → `SqlRenderer` 闸门 → 落盘 SQL。闸门不通过就报错并保留草稿。

`mock` 模式下不调用模型，用概要里的「テーブル: xxx」和「取得/更新/削除」关键词生成最小骨架，
便于离线验证全链路；真实调用把配置改为 `llm.mode=live` 并启动本地 vLLM，命令不变。

**prompt 模板也是 md 文件**（`specs/prompts/sql-draft.md`）
- 喂给大模型的 prompt 骨架存放在 md 里，改措辞/加约束不用改 Java。
- 占位符只有四个大写项，由 Java 确定性填入：`{{DESCRIPTION}}`（概要）、`{{VARS}}`（已解析变量）、
  `{{CONTEXTS}}`（共通 md 文脉）、`{{TABLES}}`（表结构库）。小写 `{{key}}` 是说明文字，不替换。
- 闸门：`<!-- ... -->` 注释（人向けメモ）发送前剥离；四个占位符缺一即拒；未知大写占位符（打错字）列出并拒；
  填充后若仍有未替换占位符也拒。
- 换模板用 `--prompt <file>`；默认文件不存在时回退内置骨架，`--prompt` 显式指向不存在的文件则报错。

### 由大模型直接生成 SQL（分种别 prompt 模板 + 日语名映射，`SqlLlmFromMd`）

三条链路里模型介入最深的一条：**prompt 里放「SQL 雏形」+ md 表结构，让大模型生成 SQL 文本并保存到文件**。
但不牺牲确定性——种别、模板选择、表结构、变量值、闸门、落盘判定都在 Java 侧，
模型只能产出 SQL 文本和判断メモ（`SqlTextDraft` record）。

**三个分种别 prompt 模板（都是 md 文件）**
```
specs/prompts/
├── sql-select.md    # SELECT 专用，含 INNER JOIN 雏形（支持多表结合查询）
├── sql-update.md    # UPDATE 专用，雏形强制 WHERE
├── sql-delete.md    # DELETE 专用，雏形强制 WHERE
└── sql-draft.md     # ② 链路（草稿 md）用，保持不变
```
- 每个模板内写有 **SQL 雏形**（可直接执行的骨架示例）+ 4 个大写占位符
  `{{CONTEXTS}}` / `{{VARS}}` / `{{TABLES}}` / `{{DESCRIPTION}}`，由 Java 确定性填入。
- 种别 → 模板的映射是 `SqlPromptTemplate.defaultKindPath(kind)`，100% 确定；`--prompt <file>` 可覆盖，
  指向不存在的文件立即报错（不静默回退）。模板仍过 `SqlPromptTemplate` 的注释剥离／占位符闸门。

**日语名 → 英语物理名（喂给大模型之前完成）**
- 表结构 md 里 `カラム名` 是英语物理名、`日本語名` 是仕様書上的日语名；`テーブル日本語名` 同理。
- 概要 md / prompt / 共通 md 里的表名、字段名**可以用日语写**，例如
  「入金明細と入金ヘッダをアプリケーションIDで結合し、入金額を取得する」。
- Java 在喂模型前按链 `表名 → 表構造ライブラリ → フィールド日本語名 → フィールド英語名` 构建
  `NameMapping`（长名优先替换；同一日语名指向不同英语名即拒绝），把文本全部替换成英语物理名。
  日志里的「翻訳後概要」可核对：`receipt_detail` / `receipt_header` / `application_id` / `amount` …。
- 因此模型只看到英语物理名，生成 SQL 里的表/列必然能在表结构库中查到，`SqlTextGate` 才有得校验。

**运行**（LLM 信息仍来自 `config/agent.properties`，不是 `--live`）
```bash
CP=$(ls lib/*.jar | tr '\n' ';'); J=/c/java/jdk-18.0.2.1/bin/java
for m in llm-select-join llm-update llm-delete; do
  $J -cp "out;$CP" com.walk_nie.bizgen.agent.sqlgen.SqlLlmFromMd \
    specs/sql/$m.md --out generated/sql-llm
done
# 草稿（人工审核）: generated/sql-llm/<name>_draft.sql / _note.md
# 正式 SQL       : generated/sql-llm/receipt_detail_{select,update,delete}.sql
```
概要 md 示例 `specs/sql/llm-select-join.md`（日语名、多表结合）：
```markdown
## 対象
- 種別: SELECT
- テーブル: 入金明細
## 概要
入金明細と入金ヘッダをアプリケーションIDで結合し、
明細ID・入金額・ステータス・入金日を取得する。
```

**实际生成（mock 离线骨架；`llm.mode=live` 时由本地 14B 模型产出，命令不变）**
```sql
SELECT d.id, d.amount, h.status
FROM receipt_detail AS d
INNER JOIN receipt_header AS h ON d.application_id = h.application_id
WHERE d.application_id = /*applicationId*/'APP001'
  AND h.receipt_date = /*receiptDate*/CURRENT_DATE
  AND d.amount >= /*minAmount*/9
ORDER BY d.id DESC;
```
`UPDATE` / `DELETE` 同样带字符/日期/数字三种变量格式，且强制 WHERE。

**只把 SQL 用到的表结构喂给大模型（`TableSelector`）**：表结构库可以有几十张表，但 prompt 里只放本次 SQL 用到的表及其日语→英语对应，避免 14B 模型把无关表/列混进 SQL。使用表由 Java 按三条确定性规则选出：① 概要 `## 対象 - テーブル`（可写日语名）→ ② 翻译后文本里出现的英语物理表名 → ③ 原文里出现的日语表名；一条都选不到时才回退全表并在日志标注，绝不静默给空上下文。**闸门仍用完整表结构库校验**（模型偷偷加的表照样被拦）。日志可见 `LLM へ渡す表構造: 1 / 2 テーブル → [receipt_detail]（使用表のみ）`；链路②`SqlDraftFromMd` 同样只喂概要指名的那张表。

**流程与闸门**（`SqlTextGate`，全部确定性，落盘前拦截）
1. 共通 md 收回 `{{key=value}}` 定义 → 变量解决；表结构库 → `NameMapping` → 日语名转英语名（**喂模型前**）；使用表筛选 → 只把该表的结构与名称对应放进 prompt。
2. 种别由 `--kind` ＞ 概要 `## 対象 - 種別` ＞ 关键词确定；据此选分种别 prompt 模板（含 SQL 雏形）。
3. `SqlTextDraft(sql, note)` record 派生 JSON Schema → `guided_json`；`mock` 用库中真实列拼骨架，不猜列名。
4. **草稿 `_draft.sql` 与 `_note.md` 先落盘供人工审核**（即使后续闸门拒绝也保留，便于排查）。
5. `SqlTextGate` 检查：单语句/末尾一个分号、首关键字与种别一致、禁止 DDL/UNION/注释断片、
   FROM/JOIN/UPDATE 的表必须都在库中、`别名.列` 的列必须在该表存在、未知标识符拒绝、
   DELETE/UPDATE 必须有 WHERE、变量三格式合法且值无注入。
6. 通过后落盘 `<主表>_<kind>.sql`。**模型写错列名、写错表、漏 WHERE、混入注入，一律被拦**。

配置驱动同前：改 `config/agent.properties` 的 `llm.mode=live` 并启动本地 vLLM 即真实生成；
`--var applicationId=APP999` 覆盖共通定义，`--tables-dir` / `--common-dir` 换目录。

## 设计原则（对应硬约束）

1. **单一真源**：Schema 与回填都读同一组 `RecordComponent`，结构不漂移。
2. **结构由解码器保证**：`guided_json` + XGrammar 在解码期屏蔽非法 token，分类用 enum。
3. **确定性闸门**：`additionalProperties:false` + `required` + 严格回填 + 动态白名单 + 编译校验，越界即抛异常打回。
4. **边界清晰**：LLM 只在 Activity 内；Workflow 只做编排；模型无权决定流程走向。
   SQL 链路同理——模型最多写 SQL 文本，种别/模板/表结构/变量/闸门/落盘全部由 Java 决定。

## 生产接入备忘

- `recordApproval` 现在是打印审计日志，生产应写入 PostgreSQL 审批表并关联人工作业单。
- 运行真实流程：先起 vLLM 与 Temporal Server，再跑 `WorkerMain`（worker 常驻）与 `WorkflowStarter`（发起 + 审批）。
- 下游可把生成结果写成 Git 分支/PR，人工 review 后再合并，与审批 signal 二选一或组合。
