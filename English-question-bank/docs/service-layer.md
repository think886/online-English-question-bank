# Service 层设计说明

> 范围：Service 的职责划分、方法契约、事务与写入策略、词形归一化算法、分步实施顺序。
> 本文可独立阅读，不依赖聊天记录。

## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1 次 | 2026-09-21 | 初版：四个 Service 的职责与方法、事务与 upsert 策略、词形归一化（含三级兜底实测）、分步顺序、Redis 边界 |
| 第 2 次 | 2026-09-21 | 新增修订记录表；将反查性能问题登记为待办 **B-01**（见 `backlog.md`） |
| 第 3 次 | 2026-09-21 | `SysUserService` 实现完成；调整步骤 2/3 顺序并说明理由；新增 4.1 节记录写入链路第 1 步（INSERT/SELECT/DELETE）的 7 项实测结果 |
| 第 4 次 | 2026-09-22 | `PracticeSessionService.startSession` 实现完成（第一次真正用到事务）；新增 `bootstrap/SampleDataInitializer` 提供自编示例数据；新增 4.2 节记录跨表事务验证（阶段 2 的 4 项实测） |
| 第 5 次 | 2026-09-22 | `WordMarkService` 实现完成（含三条 XML upsert）；提取 `WordFormNormalizer` 为共用组件；新增 4.3 节记录阶段 3 的 8 项实测，**其中首次验证了事务回滚与数据库生成列** |
| 第 6 次 | 2026-09-22 | `AnswerService` 实现完成；新增 4.4 节记录阶段 4 的 11 项实测，**核心是「改答案时会话统计按差值调整」**；登记待办 B-11（翻译题组卷未设计，其判分分支因此未验证） |
| 第 7 次 | 2026-09-26 | `PracticeSessionService.finishSession` 实现完成；新增 4.5 节记录阶段 5 端到端实测（9/9），**用「篡改统计后交卷」验证了聚合重算确实纠偏** |
| 第 8 次 | 2026-09-26 | **解决待办 B-11**：新增 `startTranslationSession` 独立组卷入口，翻译题第一次可用；新增 4.6 节记录阶段 6 实测（8/8），含「NULL 的 `is_correct` 不污染统计」 |
| 第 9 次 | 2026-09-27 | API 层收尾所需的两个新增/变更：**`finishSession` 签名加 `userId`**（原先猜到 sessionId 就能交别人的卷）、新增 `loadResult(userId, sessionId)`（含归属与「未交卷不给答案」两条访问边界）；新增 `summarizeSessionMarks`；纠正 `summarizeSessionMarks` / `firstSentence` 的两处注释错误 |

---

## 一、技术选择与约定

| 决策 | 选择 | 理由 |
|---|---|---|
| 分层 | **Interface + Impl 两层** | 与国内 Spring 项目的主流习惯一致；且将来若要给查词加**手写缓存装饰器**（Redis），接口是必需的 |
| 包结构 | `service`（接口）+ `service/impl`（实现） | 惯例 |
| 入参对象 | 参数 ≥ 4 个时用 record 封装，放 `dto` 包 | 例如 `MarkCommand`，否则调用处无法阅读 |
| 事务注解位置 | **Impl 的方法上** | 接口上的注解不生效 |
| Javadoc 位置 | 接口写**契约**（做什么、参数、抛什么异常）；实现写**细节**（怎么做的、为什么） | 看接口即够用，不必翻实现 |
| 异常策略 | 业务约束失败直接抛异常（如 `IllegalStateException`）；**唯一键冲突不吞**，让 `DuplicateKeyException` 冒泡到 Controller 层再转 HTTP 状态码 | Service 层吞掉会丢失原始信息 |

---

## 二、四个 Service 的职责

### 2.1 `SysUserService`

> 范围：只做建用户与查用户。**登录鉴权、密码加密不在本轮**，`password_hash` 保持为空。

```java
SysUser register(String username, String nickname);
SysUser findByUsername(String username);
SysUser findById(Long id);
boolean existsByUsername(String username);
```

`register` 流程：校验用户名 → 构造实体（`role='USER'`、`userType=1`、`status=1`）→ `insert` → 回填自增 id。
重名靠唯一索引 `uk_user_username` 兜底，抛 `DuplicateKeyException`。

### 2.2 `PracticeSessionService`

> 范围：管理「开始 → 作答中 → 交卷」生命周期。**不含组卷算法**（随机抽题、按难度选题）。

```java
PracticeSession startSession(Long userId, Long passageId);   // 🔒 事务
PracticeSession startTranslationSession(Long userId, int count); // 🔒 事务（第 8 次修订新增）
void             finishSession(Long userId, Long sessionId); // 🔒 事务（第 9 次修订加 userId）
PracticeSession  findById(Long sessionId);
PracticeContent  loadContent(Long sessionId);
PracticeResult   loadResult(Long userId, Long sessionId);    // 第 9 次修订新增
```

> 🔖 **第 9 次修订修改：`finishSession` 增加 `userId` 参数**
>
> 原签名是 `finishSession(Long sessionId)`。这在 API 层就暴露成一个真实漏洞：
> 任何人猜到（或遍历）`sessionId` 就能**交掉别人的卷**，
> 而交卷会写死对方的 `finished_at` / `duration_ms` / 全部统计值，且不可撤销。
>
> 【为什么当初没发现】Service 层的 47 项自检全部用同一个用户跑，
> 从来没有「拿别人的 sessionId 来调」这个用例 —— **自检覆盖的是正确路径，不是越权路径**。
> 这也说明访问边界这类东西必须在 API 层单独测（现已加入 `tools/api-smoke.ps1`）。
>
> 变更影响：`EndToEndVerifier`、`TranslationVerifier` 共 3 处调用点同步更新。

`startSession`：校验用户 → 查该文章启用题目（为空则抛异常）→ 写 `practice_session`
→ **批量写 `session_question` 快照**（每题一行，`sort_order = seq`）。

> **为什么必须事务**：若会话写入成功、快照失败，会留下「有会话但无题目」的僵尸会话，
> 结果页空白且极难排查。
>
> **为什么要落快照**：文章事后被编辑（增删题、改题号）时，历史会话不会错乱。
> 这是 `session_question` 表存在的唯一理由。

### 2.3 `WordMarkService`（核心）

> 范围：记录划词 + 维护生词本。**不做**外部 API 查词回写；**不做**取消标记（后续项）。

```java
MarkResult markWord(MarkCommand cmd);                        // 🔒 事务
List<UserWordMark> listMarksByQuestion(Long userId, Long questionId);
List<UserWordMark> listMarksBySession(Long userId, Long sessionId);
List<UserVocabulary> listVocabulary(Long userId, int limit);
```

`MarkCommand`：`userId` / `sessionId`(可空) / `questionId`(可空) / `passageId` /
`sourceField` / `surfaceForm` / `charStart` / `charEnd`

`MarkResult`：`markId` / `normalizedForm` / `wordId` / `translation` / `alreadyMarked`

> 返回 `translation` 是为了让前端**一次调用就拿到释义**，不必再查一次词典。

### 2.4 `AnswerService`

> 范围：提交单题答案、自动判分、查询作答记录。**不做**翻译题 LLM 评分（只置 `PENDING`）。

```java
AnswerResult submitAnswer(Long userId, Long sessionId, Long questionId, String userAnswer); // 🔒 事务
List<AnswerRecord> listBySession(Long sessionId);
List<AnswerRecord> listWrongByUser(Long userId, int limit);   // 错题本
```

`listWrongByUser` 兑现 schema 里的一句设计声明：
**错题本不需要额外的表**，`answer_record WHERE is_correct = 0` 就是错题本。

---

## 三、关键实现机制

### 3.1 表归属总览

| Service | 写入 | 读取 |
|---|---|---|
| `SysUserService` | `sys_user` | `sys_user` |
| `PracticeSessionService` | `practice_session`、`session_question` | `question`、`answer_record` |
| `WordMarkService` | `user_word_mark`、`user_vocabulary`、`session_question` | `word` |
| `AnswerService` | `answer_record`、`practice_session`、`session_question` | `question`、`question_option` |

### 3.2 事务边界

只有「一次操作写多张表」的方法需要 `@Transactional`：

```
markWord()      → user_word_mark  +  user_vocabulary  +  session_question
submitAnswer()  → answer_record   +  practice_session +  session_question
startSession()  → practice_session +  session_question
```

> ⚠️ **自调用陷阱**：同一个类内部 `this.otherMethod()` 调用**不经过 Spring 代理，事务不生效**。
> 必须由外部 Bean 调用，或注入自身代理。

### 3.3 Upsert：三个表、三种语义

三个表都有唯一键，但**重复写入的语义完全不同**，不能一概而论：

| 表 | 唯一键 | 重复写入语义 | 实现 |
|---|---|---|---|
| `user_vocabulary` | `(user_id, normalized_form)` | **累加**：标记次数 +1 | `ON DUPLICATE KEY UPDATE mark_count = mark_count + 1, last_marked_at = NOW()` |
| `answer_record` | `(session_id, question_id)` | **覆盖**：改答案、重判分 | `ON DUPLICATE KEY UPDATE user_answer = ..., is_correct = ...` |
| `user_word_mark` | `uk_mark_pos`（6 列） | **忽略**：同位置重复标记无意义 | `ON DUPLICATE KEY UPDATE id = id`（显式空操作） |

**为什么用 SQL 层 upsert 而不是「先查后写」**：后者是两条语句，中间存在竞态；
且 `mark_count` 的累加会丢更新。SQL 层 upsert 是单条原子语句。

**为什么不用 `INSERT IGNORE`**：它会把数据截断、类型错误这类**真实问题一起吞掉**，排查时毫无痕迹。

### 3.4 词形归一化（已实测验证）

**目标**：用户划到 `running`，要归一到 `run`，才能与生词本里已有的 `run` 合并。

#### 发现：仅靠正向查词不够

`Word` 表的 `exchange` 字段格式为 `类型:形式/类型:形式/...`，
其中 `0:` 段给出 lemma。但实测发现 **ECDICT 只为一部分变形建了独立词条**：

| 输入 | 是否有独立词条 | 说明 |
|---|---|---|
| `abandoned` | ✅ 有 | `exchange = 0:abandon/1:dp/p:abandoned/d:abandoned` |
| `running` | ✅ 有 | `exchange = 0:run/1:i/i:running/...` |
| `abandoning` | ❌ **没有** | 但 `abandon` 的 exchange 里写着 `i:abandoning` |
| `abandons` | ❌ **没有** | 同上 |
| `gave` | ❌ **没有** | 但 `give` 的 exchange 里写着 `p:gave` |
| `apples` | ❌ **没有** | 但 `apple` 的 exchange 里写着 `s:apples` |

> 只做正向查词会得到一个**不一致**的结果：`run`/`running` 能合并，
> 而 `abandon`/`abandoning` 不能。生词本会出现重复条目。

#### 方案：三级兜底

| 级别 | 条件 | 结果 | 实测覆盖 |
|---|---|---|---|
| 1 | 正向查词命中，`exchange` 含 `0:xxx` | `normalized = xxx` | 6 / 11 |
| 2 | 正向查词命中，无 `0:` 段 | `normalized = 该词条本身`（它就是原形） | 1 / 11 |
| 3 | 正向未命中 → **反查 `exchange`** | `normalized = 记录该形式的原形词条` | 4 / 11 |
| 4 | 仍未命中 | `normalized = 小写原形`，`wordId = null` | 0 / 11 |

反查 SQL（`WordMapper.selectByExchangeForm`）：

```sql
SELECT * FROM word
 WHERE exchange IS NOT NULL
   AND CONCAT(exchange, '/') LIKE CONCAT('%:', #{form}, '/%')
 LIMIT 1
```

两端的 `:` 与 `/` 是**词边界保护**：`%:abandon/%` 不会误匹配到 `:abandoned`；
补末尾的 `/` 让 exchange 的最后一段也能被匹配。

#### 实测结果（2026-09-21）

```
abandon      → abandon      [正向] ✅   无 0: 段，自身即原形
abandoned    → abandon      [正向] ✅   exchange 含 0:abandon
perceived    → perceive     [正向] ✅
running      → run          [正向] ✅   exchange 含 0:run
taken        → take         [正向] ✅
children     → child        [正向] ✅
better       → good         [正向] ✅
abandoning   → abandon      [反查] ✅   由 abandon 的 exchange 定位
abandons     → abandon      [反查] ✅
gave         → give         [反查] ✅   由 give 的 exchange 定位
apples       → apple        [反查] ✅
共 11 项：正向命中 7 / 反查命中 4 / 完全未命中 0 / 归一出错 0
```

**性能备注**：反查带前置通配符、**无法走索引**，是全表扫描。`word` 表仅 36884 行，
且划词由用户主动触发（非高并发），当前可接受。**未单独做性能压测**。
若日后成为瓶颈，可在导入阶段额外生成一张「形式 → 原形」的反向索引表。

> 🔖 **第 2 次修订修改：已登记为待办 B-01**（见 `backlog.md`）。
> 触发条件：划词 QPS 上升，或 `word` 表显著变大（引入全量词典后可能到几十万行）。
> 注意解法是**建反向索引表**，而不是给这条 SQL 加函数索引。

### 3.5 阅读题自动判分

```
READING：
  查 question_option (question_id, option_key, is_correct = 1)
    命中 → is_correct = 1，score = question.score
    未命中 → is_correct = 0，score = 0
  grading_status = 'DONE'，graded_by = 'AUTO'，graded_at = now

TRANSLATION：
  is_correct = NULL，score = NULL，grading_status = 'PENDING'
```

### 3.6 统计字段的「双重更新」机制

`practice_session` 上的统计字段存在**两套更新机制**，这不是重复，是刻意设计：

| 时机 | 方式 | 目的 |
|---|---|---|
| 会话进行中 | `submitAnswer` 用 **SQL 原子自增** `+1` | 让前端实时显示「已答 3/5」，避免每次聚合 |
| 交卷时 | **聚合查询重算** | 保证最终数字一定正确，不怕中途漏更新或重复累加 |

```sql
SELECT COUNT(*)                    AS answered,
       SUM(is_correct = 1)         AS correct,
       SUM(COALESCE(score, 0))     AS score,
       SUM(COALESCE(max_score, 0)) AS max_score
  FROM answer_record WHERE session_id = ?
```

> 增量更新是**性能优化**，聚合重算是**正确性保证**。只做前者迟早会飘；只做后者则中途无法显示进度。

**同理**：`practice_session.marked_word_count` 是「去重单词数」，**无法用 `+1` 增量维护**
（同一词标两次只算一个），只在 `finishSession` 时用 `COUNT(DISTINCT normalized_form)` 算一次。

而 `session_question.marked_word_count` 是「该题标记的词次」，可以用 `+1` 增量维护。

---

## 四、分步实施顺序

> 🔖 **第 3 次修订修改：调整了步骤 2/3 的顺序并更新进度**

**顺序调整说明**：原计划第 2 步是「给 Mapper 补 upsert / 原子自增 SQL」。
但 `SysUserService` 只用到 `BaseMapper` 自带方法，**一条自定义 SQL 都不需要**，
因此把它提前——这样能得到一个「更小、且能立即独立验证」的增量：
**验证本项目的第一次 INSERT**。自定义 SQL 改为与使用它的 Service 一起写（步骤 5）。

| 步 | 内容 | 说明 | 状态 |
|---|---|---|---|
| 1 | 验证 ECDICT 词形归一化假设 | 发现正向查词只覆盖 7/11，补了「反查 exchange」一级 | ✅ 完成 |
| 2 | `SysUserService`（接口 + 实现） | 只依赖 `BaseMapper`，无需自定义 SQL | ✅ 完成 |
| 3 | 写入链路验证 · 第 1 步（单表写入） | INSERT / 自增回填 / 默认值 / 唯一约束 / DELETE | ✅ 完成（7/7） |
| 4 | `PracticeSessionService.startSession` + 写入链路验证 · 第 2 步（跨表事务） | 跨表 insert + **第一次真正用到事务** + XML 批量插入 + 外键级联 | ✅ 完成（4/4） |
| 5 | `WordMarkService.markWord` + 三条 XML upsert | **最高密度验证点**：insert + upsert + 事务回滚 + 生成列 + 原子自增 | ✅ 完成（8/8） |
| 6 | `AnswerService.submitAnswer` | 自动判分 + 覆盖式 upsert + **改答案时统计按差值调整** | ✅ 完成（11/11） |
| 7 | `PracticeSessionService.finishSession` + 端到端验证器 | 聚合重算纠偏 + 主流程串联（建会话 → 划词 → 作答 → 交卷） | ✅ 完成（9/9） |
| 8 | **解决 B-11**：`startTranslationSession` 独立组卷 | 翻译题无法进入会话导致功能不可用、判分分支未验证 | ✅ 完成（8/8） |

### 4.1 写入链路验证 · 第 1 步（已完成）

由 `config/WritePathVerifier` 在启动时自动执行，用带时间戳的用户名建用户、验证、再删除，
**不留残留数据**。实测输出（2026-09-21）：

```
========= 写入链路验证 · 第 1 步（INSERT / SELECT / DELETE）=========
✅ 注册用户  id=5 username=verify_1789995625340
✅ 按用户名查回且字段一致  role=USER userType=1 status=1 nickname=写入验证用户
✅ created_at 由数据库默认值填充  createdAt=2026-09-21T21:00:25
✅ 按主键查回  id=5
✅ 唯一索引拦住重复用户名  已拦截，抛 DuplicateKeyException
✅ existsByUsername 存在时返回 true  true
✅ 删除验证数据（顺带验证 DELETE）  affectedRows=1，删除后 existsByUsername=false
------------------------------------------------------------------
通过 7/7 项
================== ✅ 写入链路（第 1 步）正常 ==================
```

**这一步证实的能力**：

| 能力 | 证据 |
|---|---|
| `INSERT` 可执行 | 本项目第一次真正的写操作，成功 |
| 自增主键回填 | `IdType.AUTO` 生效，`insert` 后实体 `id` 被填充，无需再查一次 |
| 数据库默认值生效 | 未在 Java 里设置 `created_at`，值是数据库 `DEFAULT CURRENT_TIMESTAMP` 给的 |
| 中文写入无乱码 | `nickname=写入验证用户` 原样往返 |
| 唯一索引真的在拦截 | 重名抛 `DuplicateKeyException`，不是应用层提前查出来的 |
| `DELETE` 可用 | `affectedRows=1`，删除后 `existsByUsername` 变 false |

**一个观察**：删除后自增 id 不会回退复用（实测连续运行得到 id=1 → 5）。
这是 InnoDB 的 `AUTO_INCREMENT` 计数器的正常行为，不是缺陷。

**尚未验证**：`UPDATE`、事务回滚（步骤 4 才会碰到）、以及所有 upsert SQL。

---

### 4.2 写入链路验证 · 第 2 步：跨表事务（已完成）

> 🔖 **第 4 次修订新增**

同样是 `WritePathVerifier` 自动执行。为了让会话有数据可用，新增了
`bootstrap/SampleDataInitializer`（幂等）提供**自编的示例数据** ——
1 篇阅读文章 + 2 道选择题 + 8 个选项 + 1 个演示用户。

> 示例文章与题目是**为跑通流程自编的，不是考试真题**，不涉及版权；
> `passage.source` 标记为「示例数据（非真题）」以便识别与将来替换。

实测输出（2026-09-22）：

```
================= 写入链路验证 · 阶段 2（跨表事务）=================
✅ 空文章拒绝开会话且不留残留  已拒绝：IllegalStateException；会话数 0 → 0
✅ 开始会话（跨表写入成功）  sessionId=1 totalCount=2 maxScore=2 status=IN_PROGRESS
✅ XML 批量插入写入题目快照  快照 2 条 / 题目 2 道，首条 sortOrder=1 status=UNANSWERED
✅ 删除会话级联删除题目快照  删除会话 1 行，剩余快照 0 条（ON DELETE CASCADE）
通过 11/11 项   （阶段 1 的 7 项 + 阶段 2 的 4 项）
```

**这一步证实的能力**：

| 能力 | 证据 |
|---|---|
| **事务内的跨表写入** | 一次 `startSession` 同时写成 `practice_session` 与 `session_question` |
| **业务约束真的在拦** | 对不存在的文章开会话抛 `IllegalStateException`，且**会话数前后都是 0**（没有留下半截数据） |
| **XML `<foreach>` 批量插入可用** | 这是项目里第一次用 XML 批量插入，2 条快照一次写入，`sort_order` 顺序正确 |
| **外键 `ON DELETE CASCADE` 真的生效** | 删除会话后，其题目快照剩余 0 条 —— 这是 schema 里声明过、但从未被执行验证的行为 |
| 自增主键回填（跨表） | 会话 id 回填后才用于写快照，说明回填发生在同一事务内 |

**尚未验证**：

- **事务回滚**：目前只验证了「成功时一起提交」，还没有构造过「中途失败 → 全部回滚」的场景。
  下面的阶段 2 第 1 项验的是「异常在写任何数据之前抛出」，不等于回滚。
  真正的回滚验证需要一次「写了一张表之后失败」的场景，计划在 `WordMarkService`
  （写 `user_word_mark` + upsert `user_vocabulary`）阶段补上。
- `UPDATE` 语句 —— 至今一次都没执行过。
- 所有 upsert SQL。

---

### 4.3 写入链路验证 · 第 3 步：划词标记（已完成）

> 🔖 **第 5 次修订新增**

由 `config/WordMarkVerifier` 自动执行。这是全项目写入最复杂的一条路径 ——
一次 `markWord` 触及三张表。实测输出（2026-09-22）：

```
================= 写入链路验证 · 阶段 3（划词标记）=================
✅ 首次标记 + 词形归一化（running, → run）  normalized=run wordId=28313 alreadyMarked=false
✅ 生成列 session_key / question_key 计算正确  session_key=11（期望 11）question_key=1（期望 1）
✅ 生词本首次写入 mark_count=1  mark_count=1
✅ 同位置重复标记是幂等空操作  alreadyMarked=true 标记行数=1（期望 1）mark_count=1（期望 1）
✅ 不同位置标记同词：新增行 + 生词本累加  标记行数=2（期望 2）mark_count=2（期望 2）
✅ question_id 为空时 question_key 归零  session_key=11 question_key=0（期望 0）
✅ session_question.marked_word_count 自增正确  marked_word_count=2（期望 2）
✅ 事务回滚：写入后抛异常，标记被撤销  抛异常=true 标记行数 3 → 3（应不变）
通过 8/8 项
```

**这一步补上了之前所有的验证缺口**：

| 之前的状态 | 现在的证据 |
|---|---|
| 事务只验证过「成功时一起提交」 | **回滚真的发生了**：写入成功后抛异常，标记行数 3 → 3 不变 |
| `UPDATE` 语句一次都没执行过 | `session_question.marked_word_count` 原子自增正确（2 次落入该题的标记） |
| 生成列从未在真实 insert 中被验证 | `session_key=11` / `question_key=1` 由数据库正确计算 —— 证明 MyBatis-Plus 确实没碰它们 |
| alter-02 修的 NULL 归零问题未在写入路径验证 | `question_id` 为空时 `question_key=0` ✅ |
| 三条 upsert 语义未验证 | 累加（1→2）、幂等（重复标记不新增行）、均可正常工作 |

**关于事务回滚是怎么测的**：普通的 try-catch 测不出来。
如果「调用 markWord」和「抛异常」都在一个没有 `@Transactional` 的方法里，
`markWord` 会自己开事务并**正常提交**，之后抛异常也回滚不了已提交的数据 —— 测试就假了。
因此用了 `config/TransactionRollbackProbe`：它自己的方法带 `@Transactional`，
在里面先调用 `markWord`（默认传播行为 REQUIRED，会加入本事务）再抛异常。
这个场景本身也真实 —— 上层编排多个 Service 调用时失败，就应该整体回滚。

**归一化器改为共用组件**：算法从自检类里提取为 `support/WordFormNormalizer`，
`WordMarkService` 与 `InflectionProbeChecker` 共用同一份实现。
此前自检类内联了一份副本，那样一旦服务侧改动、验证就失去意义。
现在 `InflectionProbeChecker` 覆盖 13 个用例（含带标点输入），实测 13/13 全对。

---

### 4.4 写入链路验证 · 第 4 步：作答与判分（已完成）

> 🔖 **第 6 次修订新增**

由 `config/AnswerVerifier` 自动执行。实测输出（2026-09-22）：

```
================= 写入链路验证 · 阶段 4（作答与判分）=================
✅ 阅读题答对：自动判分正确  isCorrect=1 score=1/1 status=DONE firstSubmit=true
✅ 会话统计：已答 1 / 答对 1  answered=1 correct=1 score=1
✅ 阅读题答错：判 0 分  isCorrect=0 score=0
✅ 会话统计：已答 2 / 答对 1  answered=2 correct=1 score=1
✅ 重复提交覆盖而非新增  firstSubmit=false 作答记录行数=2（期望 2：两道题各一行）
✅ 改答案后统计按差值调整（答对 → 答错）  answered=2（应仍为 2）correct=0（应为 0）score=0（应为 0）
✅ session_question.status 已置为 ANSWERED  已作答快照 2 / 2
✅ 错题本可查到本次的两道错题  查到 2 条
✅ 不在会话中的题被拒绝  已拒绝：该题不在本次会话中: questionId=-999
✅ 非法选项标识被拒绝  已拒绝：阅读题的答案必须是 A/B/C/D 之一，收到: E
✅ 已结束的会话不能作答  已拒绝：会话已结束，不能再作答: status=FINISHED
通过 11/11 项
```

#### 最关键的一项是「差值调整」

第 6 项验证的是一个容易写错的场景：**用户先把某题答对，之后改成答错**。

如果统计只做 `+1` 而不会减回去，会话里就会留下一个永远消不掉的 `correct_count` ——
而且这个错误**不会报错、不会崩**，只让成绩虚高，极难发现。

因此 `practice_session` 的三个统计增量都是**差值**而非固定 1：

| 参数 | 首次作答 | 重复提交 |
|---|---|---|
| `answeredDelta` | 1 | **0**（不重复计数） |
| `correctDelta` | 0 或 1 | **newValue − oldValue，可能为负** |
| `scoreDelta` | 0 或满分 | **newValue − oldValue，可能为负** |

实测确认：把第 1 题从答对改成答错后，`answered` 仍为 2、`correct` 从 1 降到 0、`score` 从 1 降到 0。

#### ⚠️ 未覆盖：翻译题分支

`AnswerService.submitAnswer` 的翻译题分支（置 `grading_status = PENDING`）**已实现但未经验证**。

根因是**翻译题压根进不了会话**：示例数据里的题都是阅读题，而翻译题的 `passage_id` 为 NULL、
不属于任何文章，而 `startSession` 只按文章组卷。该缺口已登记为待办 **B-11**（见 `backlog.md`）。

---

### 4.5 写入链路验证 · 第 5 步：端到端主流程（已完成）

> 🔖 **第 7 次修订新增**

由 `config/EndToEndVerifier` 自动执行。前面几个验证器各自只测一段，
本类把用户真实会走的路径**串起来跑一遍**。实测输出（2026-09-26）：

```
================= 写入链路验证 · 阶段 5（端到端主流程）=================
✅ 开始练习会话  sessionId=18
✅ 划词标记：3 次标记 / 2 个不同的词  归一化: run / harvest
✅ 提交作答：一题对一题错  q1.isCorrect=1 q2.isCorrect=0
  （已把统计篡改为 已答=99 答对=99 得分=999 标记词数=99，用于验证交卷重算）
✅ 交卷：聚合重算纠正了被篡改的统计  已答=2（应 2）答对=1（应 1）得分=1（应 1）
✅ 交卷：状态与时间字段已填充  status=FINISHED durationMs=290
✅ 交卷：marked_word_count 取去重单词数（而非标记次数）  marked_word_count=2
✅ 交卷后不能再作答
✅ 不能重复交卷
✅ 结果页数据都能查出来  作答记录 2 条、标记记录 3 条
通过 9/9 项
```

#### 「篡改测试」验证了聚合重算确实有效

第 4 项是刻意设计的：交卷前把会话的四个统计字段改成**荒谬的值**
（已答 99、答对 99、得分 999、标记词数 99），再交卷。

如果 `finishSession` 真的以 `answer_record` 为准做聚合重算，这些假值就会被纠正回来 ——
实测确实被纠正成 `2 / 1 / 1 / 2`。

**为什么要这样测**：只看到代码里写了一句 `SELECT COUNT(*) ...` 不足以证明"重算有效"，
必须构造出「如果不重算就会错」的场景。这直接兑现了设计文档里的那句
「增量更新是性能优化，聚合重算是正确性保证」。

---

### 4.6 写入链路验证 · 第 6 步：翻译题链路（已完成）

> 🔖 **第 8 次修订新增**

由 `config/TranslationVerifier` 自动执行。本步解决待办 **B-11**。实测输出（2026-09-26）：

```
================= 写入链路验证 · 阶段 6（翻译题链路）=================
✅ 题库中存在翻译题（B-11 的前置条件）  查到 1 道翻译题
✅ 翻译题独立组卷  mode=TRANSLATION passageId=null totalCount=1 maxScore=15
✅ 会话快照包含翻译题  questionId=3
✅ 翻译题提交后置为待评分（不自动判分）  isCorrect=null score=null gradingStatus=PENDING
✅ 待评分的翻译题不污染会话统计  已答=1 答对=0（因为 is_correct 是 NULL）得分=0
✅ 用户译文被完整保存并可查回  译文长度 123 字符，与提交内容一致
✅ 交卷聚合重算后统计仍然正确  status=FINISHED 已答=1 答对=0
✅ 非法题量被拒绝  已拒绝：题量必须在 1~50 之间，收到: 0
通过 8/8 项
```

#### 修复方式

翻译题的 `passage_id` 为 NULL（不属于任何文章），而 `startSession` 只按文章组卷，
导致翻译题**无法进入任何会话**。新增独立入口：

```java
PracticeSession startTranslationSession(Long userId, int count);
```

| 项 | `startSession`（阅读） | `startTranslationSession`（翻译） |
|---|---|---|
| `mode` | `READING` | `TRANSLATION` |
| `passage_id` | 文章 id | **NULL** |
| 选题依据 | `passage_id = ? AND status = 1` | `question_type = 'TRANSLATION' AND status = 1` |

**随机抽题仍然不做** —— 按 `id` 升序取前 N 条，保证确定、可复现。

#### 两处值得留意的实现细节

**① 共用落库逻辑，但事务边界各自保留**

`startSession` 与 `startTranslationSession` 共用一个私有方法 `createSession(...)` 落库，
避免两套代码逐渐漂移。但**不能**让一个入口去调另一个 ——
同类内 `this.xxx()` 不走 Spring 代理，被调方法的 `@Transactional` 会失效。
因此共用的是「不含事务语义的纯写入逻辑」，事务边界仍留在各自的公开方法上。

**② 示例翻译题的幂等判断必须独立于文章**

示例数据初始化器原本只在「示例文章不存在」时才建数据。翻译题与文章没有关联
（`passage_id` 为 NULL），若挂在同一个判断下，**对于已经建好文章的库，翻译题永远补不上**。

实测日志印证了这个设计是必要的：

```
示例数据：示例文章已存在 id=1，跳过初始化
示例数据：已创建示例翻译题 id=3        ← 独立判断生效，翻译题被补上
```

#### 关键验证：NULL 不污染统计

第 5 项验的是：翻译题的 `is_correct` 是 NULL，提交后 `correct_count` 必须**保持 0**，
不能把"未判分"算成"答对"。

这依赖聚合 SQL 里 `SUM(is_correct = 1)` 的行为 —— `NULL = 1` 结果是 NULL，被 `SUM` 忽略。
实测确认：`已答=1、答对=0、得分=0`。

---

## 五、本轮不做的事

| 不写 | 原因 |
|---|---|
| Controller / REST 接口 | 下一步，先把 Service 验证扎实 |
| 登录鉴权、BCrypt | 方案未讨论 |
| 取消标记 | 需要额外的 `mark_count` 递减与删除逻辑 |
| 查词外部 API 回写 | 需要外部服务；`lookup_status` 字段已预留 |
| 翻译题 LLM 评分 | 同上 |
| 组卷算法 | 需要先有题库 |

---

## 六、Redis 的影响与边界

Redis 会用在两个地方，影响程度差别很大：

| 用途 | 影响 | 应对 |
|---|---|---|
| **查词缓存**（热点词） | 影响小 | 用 Spring Cache 的 `@Cacheable` 包一层，将来换 Redis 只需加依赖 + 配置，**Service 代码零改动** |
| **进行中的会话状态** | **影响大** | 若把中途状态（半成品的标记、临时答案）放 Redis，`PracticeSessionService` / `WordMarkService` 的写入时机与数据结构都要重设计 |

**当前决策（已确认）**：本轮**全部走 MySQL**，不做缓存。
理由是目标是验证 MySQL 写入链路，中间插缓存会让「数据到底写进去没有」难以判断。
查词缓存留 Spring Cache 抽象口子。

---

## 七、未验证项

| 项 | 状态 |
|---|---|
| 全部写入操作（insert / update / delete） | ⚠️ **完全未验证** —— 本轮的目的就是验证它 |
| 事务回滚行为 | ⚠️ 未验证 |
| upsert 三条 SQL | ⚠️ 未验证 |
| `user_word_mark` 生成列在插入时是否被正确避开 | ⚠️ 未验证（实体未映射它们，但从未真正执行过 insert） |
| 反查归一化的性能 | ⚠️ 未压测 |
| 并发提交同一题 | ⚠️ 未验证（依赖唯一键兜底） |

---

## 八、相关文件索引

| 文件 | 作用 |
|---|---|
| `src/main/java/.../mapper/WordMapper.java` | 已补 `selectByExchangeForm`（反查归一化） |
| `src/main/java/.../config/InflectionProbeChecker.java` | 归一化方案验证器（临时，可删） |
| `docs/orm-layer.md` | ORM 层设计说明（上一阶段） |
| `docs/database-connection.md` | 数据库连接实现说明 |
| `src/main/resources/db/schema.sql` | 11 张表建表脚本 |
