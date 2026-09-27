# 项目协作约定（英语答题系统）

> 本文件由 DSH 自动加载为项目级指令，**优先级高于用户全局 AGENTS.md** 的宽泛层级。

## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1~3 次 | 2026-09-21 ~ 2026-09-26 | 未逐次记录（本表自第 4 次起维护；`docs/README.md` 的索引列保留了历次编号） |
| 第 4 次 | 2026-09-27 | **API 层接口全部完成**并同步进度表；「常用命令」新增接口冒烟测试；「已知易错点」新增 PowerShell 5.1 的两个坑、「自检必须断言增量」两条 |
| 第 5 次 | 2026-09-27 | 「已知易错点」新增两条：**IDEA 报「程序包 XXX 不存在」= 模块 classpath 空了**（解法 Reload Maven，别改代码）、**IDEA 打开错目录**（必须打开含 pom.xml 的内层）；指向新增的 `docs/idea-classpath-troubleshooting.md` |
| 第 6 次 | 2026-09-27 | 【纠正】进度表里 API 接口数写的「8 个」**是数错的**，实际 **7 个**（已在表内列出全部路径）。数错原因：把类级 `@RequestMapping` 也当成了接口 |

---

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Spring Boot | 4.1.1 |
| Java | 17 |
| 构建 | Maven Wrapper（`.\mvnw.cmd`） |
| 数据库 | MySQL 8.0.40，本地 `D:\MySQL\MySQL Server 8.0`，库名 `english_question_bank` |
| ORM | MyBatis-Plus 3.5.16 |
| 配置文件 | 统一放 `src/main/resources/application.yml`（**不使用** `.properties`） |
| 计划引入 | Redis（用于查词缓存等，见 `docs/service-layer.md` 第六节） |

> ⚠️ MyBatis-Plus 的坐标必须是 `mybatis-plus-spring-boot4-starter`（不是 boot3），
> 且版本 ≥ 3.5.16。原因见 `docs/orm-layer.md`。

---

## 数据库 SQL 书写规范（重要）

**按复杂度分两套机制。禁止使用 `@Select` / `@Update` / `@Insert` 等注解写 SQL** ——
否则会出现第三套机制，谁也不知道该用哪个。

| 复杂度 | 机制 | 写在哪 |
|---|---|---|
| **简单** | MyBatis-Plus 构造器（`LambdaQueryWrapper` / `LambdaUpdateWrapper`） | Service 层内联 |
| **复杂** | MyBatis **XML** | `src/main/resources/mapper/XxxMapper.xml` |

### 判断标准

**能用构造器表达的一律用构造器。** 具体地：

| 场景 | 归哪类 | 说明 |
|---|---|---|
| 单表等值 / 范围条件查询 | 简单 → 构造器 | `Wrappers.lambdaQuery().eq(Word::getHeadword, x)` |
| 单表条件更新、删除 | 简单 → 构造器 | `Wrappers.lambdaUpdate().set(...).eq(...)` |
| 排序 | 简单 → 构造器 | `orderByAsc` / `orderByDesc` |
| **取前 N 条 / 分页** | **复杂 → XML** | 构造器只能靠 `.last("LIMIT " + n)` 字符串拼接，有注入风险。正式分页可另配 MyBatis-Plus 的 `Page` 插件，届时应登记到 `docs/backlog.md` |
| 多表 JOIN | 复杂 → XML | |
| 动态 SQL（`<if>` / `<foreach>` / `<choose>`） | 复杂 → XML | |
| 批量插入 / 更新 | 复杂 → XML | |
| `INSERT ... ON DUPLICATE KEY UPDATE`（upsert） | 复杂 → XML | 构造器表达不了 |
| 含函数或表达式的统计、聚合 | 复杂 → XML | 如 `COUNT(DISTINCT ...)`、`SUM(is_correct = 1)` |

### XML 文件约定

- 位置：`src/main/resources/mapper/`，文件名与 Mapper 接口同名
- `namespace` 必须是 Mapper 接口的**全限定名**
- 语句 `id` 必须与接口方法名一致
- `resultType`：**实体类可用短名**（依赖 `mybatis-plus.type-aliases-package` 指向 `entity` 包）；
  **非实体类型（如 `dto` 包里的类）必须写全限定名** —— 短名会报
  `ClassNotFoundException: Cannot find class: XXX`（实测踩过）

---

## 文档规范

见 `docs/README.md`。三条要点：

1. 每篇文档正文前必须有「**修订记录**」表，**每次改动新增一行**，写清具体改了什么
2. 本次新增/修改的章节加行内标记 `> 🔖 第 N 次修订新增`，**只保留最近 2 次**
3. **纠错必须显式写明"纠正"**，保留原结论错在哪的痕迹

---

## 常用命令

```powershell
# 构建（clean 不是可选的：不 clean 会留下陈旧的 target 资源）
.\mvnw.cmd clean package -DskipTests

# 运行（用 18080 避免与 IDEA 里的 8080 冲突）
java -jar target\English-question-bank-0.0.1-SNAPSHOT.jar --server.port=18080

# 接口冒烟测试（需应用已在 18080 运行；53 项断言，自带测试用户并自动清理）
powershell -ExecutionPolicy Bypass -File tools\api-smoke.ps1
```

> ⚠️ **构建前必须先停掉正在运行的应用**，否则 jar 被占用，
> `clean` 会报 `Failed to delete ... .jar`。

---

## 已知易错点

| 坑 | 说明 |
|---|---|
| **SLF4J 占位符** | `log.*()` 里只允许裸 `{}`。写 `{:<12}` 会导致参数错位甚至**静默丢弃**，不报错。排查：grep `log\.(info\|debug\|warn\|error)\([^)]*\{:` |
| **YAML** | 缩进只能用空格；冒号后必须有空格；`.properties` 优先级高于 `.yml`（本项目已删除 properties） |
| **生成列** | `user_word_mark` 的 `session_key` / `question_key` 是 VIRTUAL 生成列，实体**刻意不映射**，切勿手工写 |
| **事务自调用** | 同类内 `this.method()` 不走 Spring 代理，`@Transactional` 不生效 |
| **MySQL 行别名** | 写 `INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE` 时，UPDATE 里的**列引用必须加表名限定**（`user_vocabulary.mark_count + 1`），否则报 `Column 'x' in field list is ambiguous`。详见 `docs/orm-layer.md` 4.5 |
| **Spring 异常吞消息** | `DataIntegrityViolationException` 的 `getMessage()` 可能是**空字符串**，真正原因在 cause 链里。排查数据库错误时要把整条 cause 链打出来 |
| **PowerShell 脚本编码** | `.ps1` 里只要有中文，就**必须存为 UTF-8 with BOM**。PS 5.1 读无 BOM 的脚本会按 GBK 解码，中文字符串里的引号被吃掉，报 `Missing ')' in function parameter list`。改成 BOM：`[IO.File]::WriteAllText($p, [IO.File]::ReadAllText($p, (New-Object Text.UTF8Encoding($false))), (New-Object Text.UTF8Encoding($true)))` |
| **PowerShell 的 `.Count`** | PS 5.1 里 `(单个 PSCustomObject).Count` 返回**空值**而不是 `1`。`ConvertFrom-Json` 的结果经 `Where-Object` 只匹配到一条时，直接取 `.Count` 会得到 `$null`，断言莫名其妙失败。一律写 `@(...).Count` |
| **自检类断言全局绝对值** | 自检不要断言「该用户的 `mark_count` 必须是 1」这类**全局绝对值** —— 那等于假设没有别人写同一个用户的数据，手工 curl 测一次就会误报失败。应断言**自己造成的增量**，或把结果过滤到自己创建的 session/用户上，并在结束时清理。详见 `docs/api-layer.md` 9.7 |
| **写测试数据污染演示用户** | 手工测接口 / 冒烟脚本**不要用 `demo` 用户**（id=9），否则会改掉它的生词本计数与错题本。`tools/api-smoke.ps1` 的做法是自己建两个临时用户、结束时按 `practice_session → user_word_mark → user_vocabulary → sys_user` 的顺序删干净（⚠️ 顺序不能反：`fk_*_user` 都是**不带 CASCADE** 的普通外键） |
| **IDEA 报「程序包 XXX 不存在」** | 命令行 `mvnw` 能过、IDEA 却成批报**跨多个不相关库**的「程序包不存在」→ IDEA 的**模块 classpath 空了**，不是代码问题、不是依赖版本问题。解法：右键 `pom.xml` → **Maven → Reload project**。**别去改代码、别去换依赖版本。** 完整排查手册见 `docs/idea-classpath-troubleshooting.md` |
| **IDEA 打开错目录** | 本项目是「仓库根 / Maven 工程」两层：`E:\github\online-English-question-bank\`（**无 pom.xml**）→ `English-question-bank\`（**有 pom.xml**）。IDEA 必须打开**里面那一层**，打开仓库根会被当普通文件夹，依赖挂不上，症状同上一条 |

---

## 项目当前进度

| 阶段 | 状态 |
|---|---|
| 数据库（11 张表 + 词典 36884 条） | ✅ 完成 |
| ORM 层（11 实体 + 11 Mapper） | ✅ 完成 |
| Service 层 | ✅ 四个服务全部完成并验证：`SysUserService`、`PracticeSessionService`、`WordMarkService`、`AnswerService` |
| API 层 | ✅ **7 个接口全部完成并实测**（`POST /reading`、`POST /translation`、`POST·GET /{id}/marks`、`POST /{id}/answers`、`POST /{id}/finish`、`GET /{id}/result`）。见 `docs/api-layer.md` |
| 查询类接口（生词本 / 错题本 / 历史） | ⬜ **未实现**（Service 方法已就绪，缺 Controller 与分页方案）。见 backlog **B-13** |
| 题库抽取 | ⬜ 排在最后（当前用 `SampleDataInitializer` 的自编示例数据） |

**关键路径与进度详见 `docs/service-layer.md` 第四节。**
写入链路六个阶段共 **47 项**全部通过（7 + 4 + 8 + 11 + 9 + 8），含**事务回滚**、**数据库生成列**、
**按差值调整统计**、**聚合重算纠偏**等容易写错的点。
API 层另有 `tools/api-smoke.ps1` 的 **53 项**接口断言全部通过（覆盖「开始 → 划词 → 作答 → 交卷 → 结果」两条链路）。

**阅读题与翻译题两条链路都可用**：阅读题按文章组卷（`startSession`），
翻译题按题型独立组卷（`startTranslationSession`）。

**三条 API 安全红线**（详见 `docs/api-layer.md` 第二节）：
① 绝不直接返回实体；② 交卷前不返回正确答案 / 解析 / 全文译文；③ `userId` 绝不作为 URL 或请求参数。
**访问边界（归属校验、未交卷不给答案）写在 Service 层，不写在 Controller。**

**开发期自检类**（`config` 包下）属于临时工具，上线前需删除或加 `@Profile("dev")`，
清单见 `docs/README.md` 第三节。
