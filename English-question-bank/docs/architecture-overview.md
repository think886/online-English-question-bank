# 项目现状与代码阅读指南

> 范围：已实现/待实现清单、代码阅读路线、值得留意的设计点、当前技术债。
> 本文可独立阅读，不依赖聊天记录。

## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1 次 | 2026-09-26 | 初版：现状盘点、阅读路线、设计亮点、技术债 |

---

## 一、已实现

### 1.1 数据库（11 张表）

| 分组 | 表 |
|---|---|
| 用户 | `sys_user` |
| 题库 | `passage`（文章）、`question`（阅读题 + 翻译题同表）、`question_option`（选项） |
| 词典 | `word` —— 已导入 ECDICT **36884** 条 |
| 作答 | `practice_session`、`session_question`、`answer_record`、`translation_grading` |
| 生词 | `user_word_mark`（标记原始记录）、`user_vocabulary`（生词本聚合） |

配套脚本：`db/schema.sql`（建表）、`db/alter-01`~`alter-02`（增量变更）、`tools/ecdict_import.py`（词典导入）。

### 1.2 ORM 层

- **11 个实体类** + **11 个 Mapper 接口**（均继承 `BaseMapper`）
- **7 个 XML 映射文件、共 14 条自定义语句**（upsert / 批量插入 / 聚合 / LIMIT 查询）
- 启动时自动校验：实体映射与数据库真实列逐一比对（`EntityMappingChecker`）

### 1.3 Service 层（4 个服务、8 个文件）

| 服务 | 能力 |
|---|---|
| `SysUserService` | 建用户、查用户 |
| `PracticeSessionService` | 开始阅读练习、开始翻译练习、交卷、查会话 |
| `WordMarkService` | 划词标记（含词形归一化与生词本维护）、查标记、查生词本 |
| `AnswerService` | 提交答案（阅读题自动判分）、查看作答记录、错题本 |

支撑组件：
- `support/WordFormNormalizer` —— 词形归一化（三级兜底）
- `dto/` —— `MarkCommand`、`MarkResult`、`AnswerResult`、`SessionSummary`
- `bootstrap/SampleDataInitializer` —— 自编示例数据（幂等）

### 1.4 验证体系（47 项，全部实测通过）

| 阶段 | 验证器 | 项数 | 覆盖 |
|---|---|---|---|
| — | `DatabaseConnectionChecker` 等 4 个 | — | 连接、装配、查询链路、实体映射 |
| 1 | `WritePathVerifier`（前半） | 7 | 单表写入：自增回填、默认值、唯一约束、DELETE |
| 2 | `WritePathVerifier`（后半） | 4 | 跨表事务、XML 批量插入、外键级联 |
| 3 | `WordMarkVerifier` | 8 | 归一化、**生成列**、三条 upsert、**事务回滚** |
| 4 | `AnswerVerifier` | 11 | 自动判分、覆盖 upsert、**差值统计**、业务守卫 |
| 5 | `EndToEndVerifier` | 9 | 主流程串联、**聚合重算纠偏** |
| 6 | `TranslationVerifier` | 8 | 翻译题组卷、NULL 不污染统计 |

### 1.5 文档

`docs/` 下 6 篇：`README`（索引与修订约定）、`database-connection`、`orm-layer`、
`service-layer`、`backlog`、本文件；外加项目根 `AGENTS.md`（协作规范与易错点）。

---

## 二、待实现

### 2.1 阻塞使用的缺口（没有它系统跑不起来）

| 项 | 说明 | 登记处 |
|---|---|---|
| **REST 接口** | 目前所有能力只能在启动自检里跑，**前端一行都调不到** | 待讨论（方向 B） |
| **题库抽取** | `passage` / `question` 里只有自编示例数据，没有真题 | `backlog.md` B-06 |

### 2.2 功能待办（已登记，不阻塞）

| 编号 | 项 | 优先级 |
|---|---|---|
| B-03 | 取消标记（unmark） | 中 |
| B-04 | 查词的外部 API 回写（词典未收录的词） | 中 |
| B-05 | 翻译题 LLM 评分（目前只置 `PENDING`） | 中 |
| B-07 | 登录鉴权与密码加密 | 中 |
| B-02 | 词典美式音标（ECDICT 无此数据，当前全为 NULL） | 低 |

### 2.3 性能优化

| 编号 | 项 | 触发条件 |
|---|---|---|
| B-01 | 词形反查是全表扫描 | 划词 QPS 上升，或 `word` 表显著变大 |

### 2.4 上线前必须处理

| 编号 | 项 |
|---|---|
| B-08 | **数据库密码明文写在 git 跟踪的文件里**（最高优先级） |
| B-09 | SQL 日志需关闭（`logging.level...=debug`） |
| B-10 | 11 个开发期验证器需删除或加 `@Profile("dev")` |

### 2.5 尚未验证的技术点

| 项 | 状态 |
|---|---|
| 并发提交同一题 | 未验证（依赖唯一键兜底） |
| 跨表 JOIN 查询 | 目前没有任何 JOIN 语句 |
| Redis 接入 | 未开始（设计边界已确认，见 `service-layer.md` 第六节） |
| 数据量增长后的性能 | `word` 表 3.7 万行尚可，未做压测 |

---

## 三、代码阅读路线

### 第 0 步：先跑一次，看输出（建立整体印象）

```powershell
.\mvnw.cmd clean package -DskipTests
java -jar target\English-question-bank-0.0.1-SNAPSHOT.jar --server.port=18080
```

启动日志会依次打印连接自检、实体映射自检、六个阶段的验证结果。
**先看输出，再去看产生这些输出的代码**，比直接读代码容易建立"代码 → 运行结果"的对应。

### 第 1 步：环境是怎么来的

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `EnglishQuestionBankApplication.java` | 唯一的启动入口 |
| 2 | `src/main/resources/application.yml` | 数据源、MyBatis-Plus、日志级别。**这是理解"Spring 自动配了什么"的关键** |
| 3 | `pom.xml` | 依赖清单（注意 `mybatis-plus-spring-boot4-starter` 这个坐标） |

### 第 2 步：数据是怎么映射的

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `entity/Word.java` | 最完整的实体示例（19 字段、生成列的处理说明） |
| 2 | `entity/UserWordMark.java` | **刻意不映射两个生成列**的理由写在类注释里 |
| 3 | `mapper/WordMapper.java` | 什么该进 Mapper、什么不该（简单查询用构造器） |
| 4 | `resources/mapper/WordMapper.xml` | XML 写法与边界保护 |

### 第 3 步：一条最短的完整链路（建议从这里深入）

**`WordMarkService.markWord`** —— 项目最核心、也是写入最复杂的一个方法，一次调用触及三张表。
读懂它，其余 Service 都是同一个套路。

阅读顺序：
```
WordMarkService.java          接口：契约与"为什么"都写在 Javadoc 里
   ↓
WordMarkServiceImpl.java      实现：校验 → 归一化 → 查重 → 写标记 → upsert 生词本 → 计数
   ↓
support/WordFormNormalizer    归一化算法（三级兜底）
   ↓
mapper/UserWordMarkMapper.xml 幂等插入
mapper/UserVocabularyMapper.xml  累加 upsert
mapper/SessionQuestionMapper.xml 原子自增
```

### 第 4 步：主流程的四个环节

| 环节 | 入口 | 关键点 |
|---|---|---|
| 开始练习 | `PracticeSessionService.startSession` / `startTranslationSession` | 跨表事务 |
| 划词 | `WordMarkService.markWord` | 见上 |
| 作答 | `AnswerService.submitAnswer` | 自动判分 + **差值统计** |
| 交卷 | `PracticeSessionService.finishSession` | **聚合重算** |

### 第 5 步：这些是怎么被验证的

`config/` 下的验证器按 `@Order` 依次执行：
`SampleDataInitializer`(1) → `WritePathVerifier`(10) → `WordMarkVerifier`(20)
→ `InflectionProbeChecker`(30) → `AnswerVerifier`(40) → `EndToEndVerifier`(50) → `TranslationVerifier`(60)

**建议从 `EndToEndVerifier` 开始看** —— 它把主流程串了一遍，看完就知道整体怎么跑，
再回头看各阶段验证器补细节。

---

## 四、值得留意的设计点

### 4.1 分层验证：每层只引入一个新维度

七个验证器不是重复劳动，而是**逐层加一个新变量**：

```
单表写入  →  加「跨表」  →  加「生成列 + 回滚」  →  加「判分 + 差值」
          →  加「串联」  →  加「另一种题型」
```

**好处**：失败时能立刻定位到是哪一层的哪一类问题。如果一次性写完再一起测，
一个失败可能是上面任意一层的任意一个原因。

### 4.2 用「篡改测试」证明聚合重算真的有效

`finishSession` 会用聚合查询覆盖中途累加的统计。但**代码里有 `SELECT COUNT` 不等于它有效**。

因此 `EndToEndVerifier` 故意在交卷前把统计改成荒谬的值（已答 99、得分 999），
再交卷，看是否被纠正回来。实测：`2 / 1 / 1 / 2`，全部纠偏。

**这是"验证验证本身"的思路** —— 构造一个"如果不生效就会露馅"的场景，而不是看代码。

### 4.3 把去重交给数据库：VIRTUAL 生成列

`user_word_mark` 的去重键需要包含可空的 `session_id` / `question_id`，
但 **MySQL 的唯一索引允许多行 NULL**，直接放进去会导致重复标记写不进也拦不住。

解法是两个 **VIRTUAL 生成列**把 NULL 归一为 0。三个细节：

1. **必须 VIRTUAL 不能用 STORED** —— STORED 生成列的基础列上不允许带 `ON DELETE CASCADE` 外键，
   而这张表恰好有。用错会直接建表失败。
2. **不映射进实体** —— 它们只服务唯一键，业务用不到；映射了反而会让 MyBatis-Plus 插入时报错。
3. **应用层完全无感** —— 去重是数据库自动做的，Service 代码里看不到这段逻辑。

### 4.4 词形归一化的三级兜底（含一次真实的数据规律发现）

用户划 `running` 要归一到 `run` 才能与生词本合并。原方案是「查词典 + 用 `exchange` 的 `0:lemma` 段」。

但实测发现 **ECDICT 不为每个变形都建独立词条**：

| 输入 | 是否有独立词条 |
|---|---|
| `abandoned` / `running` / `children` | ✅ 有 |
| `abandoning` / `abandons` / `gave` / `apples` | ❌ **没有**（但原形词条的 `exchange` 里记着这些形式） |

只做正向查词会得到**行为不一致**的结果：`run`/`running` 能合并，`abandon`/`abandoning` 不能。

解法是加一级**反查 `exchange`**，一条 SQL 补齐了 4/11 的覆盖缺口，且**没有引入任何新数据**：

```sql
WHERE CONCAT(exchange, '/') LIKE CONCAT('%:', #{form}, '/%')
```

两端的 `:` 与 `/` 是词边界保护，避免 `:abandon` 误命中 `:abandoned`。

### 4.5 统计的「增量 + 重算」双机制

`practice_session` 的统计字段有**两套更新机制**，看似重复，实为分层：

| 时机 | 方式 | 目的 |
|---|---|---|
| 会话进行中 | SQL 原子增量 | **性能优化**：让前端实时显示进度 |
| 交卷时 | 聚合查询重算 | **正确性保证**：以事实数据为准 |

**而且增量是「差值」不是固定 +1**：用户把答对的题改成答错，`correct_count` 必须减回去。
否则会留下一个永远消不掉的数——**不报错、不崩、只让成绩虚高**，属最难发现的那类 bug。

### 4.6 三张表、三种 upsert 语义

都有唯一键，但重复写入的含义完全不同，不能一概而论：

| 表 | 语义 | 实现 |
|---|---|---|
| `user_vocabulary` | **累加**（标记次数 +1） | `mark_count = user_vocabulary.mark_count + 1` |
| `answer_record` | **覆盖**（改答案、重判分） | 逐字段赋新值 |
| `user_word_mark` | **忽略**（同位置重复标记无意义） | `ON DUPLICATE KEY UPDATE id = id`（显式空操作） |

**为什么不用 `INSERT IGNORE` 代替空操作**：它会连数据截断、类型错误这类**真实问题一起吞掉**，
排查时毫无痕迹。

### 4.7 事务回滚的验证方式

普通的 try-catch **测不出回滚**：如果"调用 Service"和"抛异常"都在一个没有
`@Transactional` 的方法里，Service 会自己开事务并**正常提交**，之后抛异常也回滚不了已提交的数据。
测试会假通过。

因此用了 `TransactionRollbackProbe`：**它自己的方法带 `@Transactional`**，
在里面先调 `markWord`（默认传播行为会加入本事务）再抛异常。
这个场景本身也真实——上层编排多个 Service 失败就该整体回滚。

### 4.8 SQL 书写规范用客观标准划线

「简单用构造器、复杂用 XML」如果只给两个词，不同人会有不同理解。
规范里给了**可判断的依据**：**能否用构造器表达**。

其中「取前 N 条」特意归到 XML，理由是构造器只能靠 `.last("LIMIT " + n)` 字符串拼接，
**有注入风险**——这是技术理由，不是风格偏好。

---

## 五、当前不够好的地方（技术债）

| 项 | 说明 |
|---|---|
| **没有对外接口** | 所有能力只能在启动自检里跑，前端无法调用 |
| **11 个验证器常驻** | 每次启动都执行，拖慢启动、污染日志；上线前必须清理 |
| **密码明文** | `application.yml` 里的数据库密码是明文且文件被 git 跟踪 |
| **示例数据是自编的** | 不是真题，题库抽取尚未开始 |
| **无鉴权** | 任何调用方都能传入任意 `userId` |
| **`listBySession` 未做权限校验** | 只按 `sessionId` 查，没有校验会话属于谁 |
| **翻译题评分链路空转** | 提交后置 `PENDING` 就再无下文，`translation_grading` 表还是空的 |

---

## 六、相关文档

| 文档 | 内容 |
|---|---|
| `README.md` | 文档索引与修订约定 |
| `database-connection.md` | 数据库连接实现（依赖 → 配置 → 装配 → 验证） |
| `orm-layer.md` | ORM 层设计、SQL 书写规范、日志实现 |
| `service-layer.md` | Service 职责、事务、upsert、词形归一化、实施进度与实测结果 |
| `backlog.md` | 待办与待优化清单 |
| `../AGENTS.md` | 协作规范、常用命令、已知易错点 |
