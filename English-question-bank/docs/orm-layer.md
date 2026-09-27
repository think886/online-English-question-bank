# ORM 数据访问层设计说明

> 范围：实体类映射约定、Mapper 编写规范、分步实现流程、已验证结论与遗留风险。
> 本文可独立阅读，不依赖聊天记录。

## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1 次 | 2026-09-21 | 初版：技术选型、映射约定、分步流程（步骤 1–3，仅 `word` 表） |
| 第 2 次 | 2026-09-21 | 步骤 4 完成：补齐其余 10 个实体与 Mapper；新增实体清单、映射自检结果 |
| 第 3 次 | 2026-09-21 | **纠正错误结论**：`<<BLOB>>` 与 `log-impl` 无关；补充字节码级证据与两种日志实现对比 |
| 第 4 次 | 2026-09-21 | `log-impl` 改为 `Slf4jImpl` 并实测验证：音标编码修复、`<<BLOB>>` 仍存在 |
| 第 5 次 | 2026-09-21 | 新增修订记录表，执行 `docs/README.md` 的修订约定 |
| 第 6 次 | 2026-09-21 | 强化八.1「SLF4J 占位符陷阱」：补充「参数被静默丢弃」这一更危险症状、记录实际复发 3 次、给出事后排查用的 grep 命令 |
| 第 7 次 | 2026-09-22 | **纠正本章结论**：废弃「自定义查询用 `@Select` 注解」，改为「简单用构造器 / 复杂用 XML」两套机制；`WordMapper` 的 4 个注解方法完成迁移并实测验证 |
| 第 8 次 | 2026-09-22 | 新增 4.4 节「XML 映射文件现状」：登记两个 XML 文件共 5 条语句，并以 `insertBatch` 作为 `<foreach>` 批量插入的用法范例 |
| 第 9 次 | 2026-09-22 | 4.4 节登记新增的 3 条 upsert 语句；新增 4.5 节记录 MySQL「行别名导致列引用歧义」这一实测踩到的语法陷阱 |
| 第 10 次 | 2026-09-22 | 4.4 节登记 `answer_record` 与 `practice_session` 的 3 条语句（含「统计增量可负」的差值语义） |
| 第 11 次 | 2026-09-26 | 4.4 节登记 2 条聚合语句；新增 4.6 节记录「`resultType` 短名只对 `entity` 包生效」这一实测踩到的坑 |
| 第 12 次 | 2026-09-26 | 4.4 节登记 `QuestionMapper.xml` 的 `selectByTypeWithLimit`（翻译题组卷用） |

---

## 一、技术选型：为什么是 MyBatis-Plus

| 方案 | 优点 | 代价 | 是否选用 |
|---|---|---|---|
| **MyBatis-Plus** | 单表 CRUD 零代码（继承 `BaseMapper` 即可）；SQL 可控、贴近原生；国内生态成熟、中文资料多 | 跨表复杂查询仍需手写 SQL；版本与 Spring Boot 有绑定关系 | ✅ **选用** |
| Spring Data JPA | 对象化程度最高，方法名即查询；跨数据库移植性好 | 复杂查询易产生 N+1；SQL 不可控，调优困难；本项目多表关联 + 批量导入场景不占优 | ❌ |
| 纯 JdbcTemplate | 零魔法，SQL 完全手写，行为最可预测 | 结果集到对象的映射要手写，11 张表工作量巨大 | ❌ |

**与项目需求的匹配度**：本项目以单表增删改查为主（词典查询、标记写入、作答记录），
恰好是 MyBatis-Plus 最擅长的场景；而「划词查释义」这类高频单表查询用 `BaseMapper` +
构造器即可覆盖，无需引入 JPA 的复杂度。

> 🔖 **第 7 次修订修改**：原文此处写的是「`@Select` 注解 SQL 即可覆盖」，
> 现按新规范改为「构造器」。注解方式已废弃，理由见第四节。

### 版本约束（重要）

- Spring Boot 4 使用 **`mybatis-plus-spring-boot4-starter`**，不是 `boot3` 那个坐标
- 版本必须 **≥ 3.5.16**。3.5.14 / 3.5.15 的 Boot 4 适配有缺陷
  （见 [issue #6970](https://github.com/baomidou/mybatis-plus/issues/6970)），3.5.16 随 `mybatis-spring 4.0.0` 一并修复
- 当前锁定：`mybatis-plus-spring-boot4-starter:3.5.16` + Spring Boot 4.1.1，实测装配正常

---

## 二、实体类映射约定

### 采用风格：`@TableName` + `@TableId` + 自动驼峰映射

```java
@Data
@TableName("word")
public class Word {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String headword;      // ← 列 headword
    private String displayForm;   // ← 列 display_form（靠自动映射）
}
```

| 规则 | 说明 |
|---|---|
| 类上必须有 `@TableName("表名")` | 显式声明类 ↔ 表的绑定，避免靠默认命名策略猜 |
| 主键必须有 `@TableId` | 并显式写出 `type = IdType.AUTO`（见下方说明） |
| 普通字段**不加** `@TableField` | 依赖 `map-underscore-to-camel-case=true` 自动完成 `normalized_form → normalizedForm` |
| 仅当列名对不上属性名时才补 `@TableField("列名")` | 本批 11 张表暂无此情况 |

**为什么主键要显式写 `IdType.AUTO`**：全局配置里已设 `id-type: auto`，这里重复一次是
**故意冗余**。所有表主键都是 `AUTO_INCREMENT`，一旦有人改动全局策略，显式声明能保证
这些表不受影响。

### 类型映射表

| MySQL 类型 | Java 类型 | 说明 |
|---|---|---|
| `BIGINT UNSIGNED` | `Long` | 自增 id 不会溢出 `Long` |
| `INT` | `Integer` | |
| `TINYINT` | **`Integer`** | **不用 `Boolean`**：部分表存在 0/1/2 三态语义（如 `mastery`） |
| `VARCHAR` / `TEXT` | `String` | |
| `JSON` | `String` | JDBC 驱动直接把 JSON 列读成字符串 |
| `DATETIME` | `LocalDateTime` | |
| `DECIMAL` | `BigDecimal` | 如 `translation_grading` 的各项得分 |

### 实体清单（11 个，全部完成）

| 实体 | 表 | 映射列数 / 实际列数 | 说明 |
|---|---|---|---|
| `Word` | `word` | 19 / 19 | 单词词典 |
| `SysUser` | `sys_user` | 10 / 10 | 用户 |
| `Passage` | `passage` | 11 / 11 | 阅读文章 |
| `Question` | `question` | 13 / 13 | 题目（阅读 + 翻译同表，按 `question_type` 区分） |
| `QuestionOption` | `question_option` | 6 / 6 | 选择题选项 |
| `PracticeSession` | `practice_session` | 14 / 14 | 练习会话 |
| `SessionQuestion` | `session_question` | 6 / 6 | 会话题目快照 |
| `AnswerRecord` | `answer_record` | 13 / 13 | 作答记录（错题本源） |
| `TranslationGrading` | `translation_grading` | 15 / 15 | 翻译题评分明细 |
| `UserWordMark` | `user_word_mark` | **13 / 15** | 单词标记；2 个生成列刻意不映射（见下方决定 1） |
| `UserVocabulary` | `user_vocabulary` | 13 / 13 | 生词本（聚合去重视图） |

> 「映射列数 / 实际列数」不是估算，是 `EntityMappingChecker` 启动时与
> `information_schema` 实际比对得出的结果。

---

## 三、两个刻意的设计决定

### 决定 1：`user_word_mark` 的生成列**不映射进实体**

该表有两个 VIRTUAL 生成列 `session_key`、`question_key`，是数据库自动计算的。

**不映射的理由**：
1. 它们只服务于唯一键 `uk_mark_pos`，**业务代码永远不需要读写**
2. 映射反而有风险——MyBatis-Plus 插入时会尝试写入这些字段，而 MySQL 对生成列
   只允许写 `DEFAULT`，会直接报错
3. 体现分层原则：**数据库的内部机制不应泄漏到应用实体层**

### 决定 2：时间字段**不使用** MyBatis-Plus 自动填充

数据库已定义 `DEFAULT CURRENT_TIMESTAMP` 与 `ON UPDATE CURRENT_TIMESTAMP`。

**不用的理由**：两套机制并存会导致「这个值到底是谁写的」无法排查。统一交给数据库负责。
因此实体里**不要**加 `@TableField(fill = FieldFill.INSERT)`。

---

## 四、SQL 书写规范

> 🔖 **第 7 次修订修改：本节被新规范取代。原规定「自定义查询用 `@Select` 注解」已废弃**
>
> **原结论错在**：注解写 SQL 会让项目出现**第三套机制**（BaseMapper / 构造器 / 注解），
> 后来者不知道 该用哪个。现按项目规范收敛为两套，并把注解方式明确禁止。

**按复杂度分两套机制，禁止使用 `@Select` / `@Update` / `@Insert` 等注解写 SQL。**

| 复杂度 | 机制 | 写在哪 |
|---|---|---|
| **简单** | MyBatis-Plus 构造器（`LambdaQueryWrapper` / `LambdaUpdateWrapper`） | Service 层内联 |
| **复杂** | MyBatis **XML** | `src/main/resources/mapper/XxxMapper.xml` |

> 规范的权威出处是**项目根目录 `AGENTS.md`** —— 该文件会被 DSH 自动加载，
> 因此每个会话都会看到，不依赖是否翻过本文件。本节只摘录要点。

| 场景 | 归哪类 |
|---|---|
| 单表等值 / 范围条件查询 | 简单 → 构造器 |
| 单表条件更新、删除 | 简单 → 构造器 |
| 排序 | 简单 → 构造器 |
| **取前 N 条 / 分页** | 复杂 → XML（构造器只能靠 `.last("LIMIT " + n)` 拼接，有注入风险） |
| 多表 JOIN | 复杂 → XML |
| 动态 SQL（`<if>` / `<foreach>` / `<choose>`） | 复杂 → XML |
| 批量插入 / 更新 | 复杂 → XML |
| `INSERT ... ON DUPLICATE KEY UPDATE`（upsert） | 复杂 → XML |
| 含函数或表达式的统计、聚合 | 复杂 → XML |

### 4.1 基础约定

- **继承 `BaseMapper<T>` 即获得全部单表 CRUD**：`insert` / `selectById` / `selectList` /
  `updateById` / `deleteById` / `selectCount` 等，**一行 SQL 都不用写**，
  MyBatis-Plus 通过动态代理生成 SQL
- 简单操作的方法**不写进 Mapper 接口**，直接在 Service 层用构造器表达。
  例如「按 headword 查词」就是单表等值查询，属于这一类
- 参数一律用 `#{}`（预编译占位符），**禁止 `${}`**（防 SQL 注入）
- 显式标注 `@Mapper`：MyBatis-Plus 本就会扫描主启动类所在包及其子包，
  显式标注是为了意图明确、包结构变动时不易漏扫

### 4.2 XML 文件约定

| 项 | 约定 |
|---|---|
| 位置 | `src/main/resources/mapper/`，文件名与 Mapper 接口同名 |
| `namespace` | Mapper 接口的**全限定名** |
| 语句 `id` | 必须与接口方法名一致 |
| `resultType` | 用实体类短名（依赖 `type-aliases-package`） |

配置（`application.yml`）：

```yaml
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml      # MyBatis-Plus 默认值，显式写出以让约定可见
  type-aliases-package: org.example.englishquestionbank.entity
```

### 4.3 已完成的迁移（2026-09-22 实测）

原 `WordMapper` 的 4 个 `@Select` 方法按新规范处理 ——
`WordMapper` 是当时**唯一**有自定义 SQL 的 Mapper，因此本次迁移即全量迁移：

| 原方法 | 去向 | 理由 |
|---|---|---|
| `selectByHeadword` | **删除**，调用方改用 `Wrappers.lambdaQuery().eq(Word::getHeadword, x)` | 单表等值查询 → 简单操作 |
| `selectTopByFrequency` | 迁至 `WordMapper.xml` | 需要 `LIMIT` |
| `countByExamTag` | 迁至 `WordMapper.xml` | 含 `CONCAT` / `LIKE` 表达式 |
| `selectByExchangeForm` | 迁至 `WordMapper.xml` | 含 `CONCAT` / `LIKE` 表达式 |

**验证证据**：迁移后重启应用，三条 XML 语句全部正常执行 ——
词频前 5 条顺序正确、考研词条数仍为 **4801**、反查归一化仍 **11/11 全对**。
这同时证明 `mapper-locations` 与 `type-aliases-package` 两项配置均已生效
（若任一未生效，XML 语句会报 `Invalid bound statement`）。

### 4.4 XML 映射文件现状

> 🔖 **第 8 次修订新增**

| XML 文件 | 语句 | 归类理由 | 加入时机 |
|---|---|---|---|
| `mapper/WordMapper.xml` | `selectTopByFrequency` | select，需要 `LIMIT` | 第 7 次修订 |
| | `countByExamTag` | select，含 `CONCAT` / `LIKE` | 第 7 次修订 |
| | `selectByExchangeForm` | select，含 `CONCAT` / `LIKE` | 第 7 次修订 |
| `mapper/SessionQuestionMapper.xml` | `insertBatch` | insert，`<foreach>` 批量 | 第 8 次修订 |
| | `incrementMarkedWordCount` | update，SQL 原子自增 | 第 8 次修订 |
| `mapper/UserWordMarkMapper.xml` | `insertIgnoreDuplicate` | insert，upsert 空操作（幂等） | 第 9 次修订 |
| | `countDistinctMarkedWords` | select，`COUNT(DISTINCT ...)` | 第 11 次修订 |
| `mapper/UserVocabularyMapper.xml` | `upsertMark` | insert，upsert 累加 | 第 9 次修订 |
| | `selectByUser` | select，需要 `LIMIT` | 第 9 次修订 |
| `mapper/AnswerRecordMapper.xml` | `upsertAnswer` | insert，upsert 覆盖 | 第 10 次修订 |
| | `selectWrongByUser` | select，需要 `LIMIT` | 第 10 次修订 |
| `mapper/PracticeSessionMapper.xml` | `incrementProgress` | update，按差值调整统计（增量可负） | 第 10 次修订 |
| | `selectAnswerSummary` | select，聚合重算（`COUNT` / `SUM`） | 第 11 次修订 |
| `mapper/QuestionMapper.xml` | `selectByTypeWithLimit` | select，按题型取前 N 条 | 第 12 次修订 |

`insertBatch` 是本项目**第一个 XML 批量插入**，也是 `<foreach>` 的用法范例：

```xml
<insert id="insertBatch">
    INSERT INTO session_question (session_id, question_id, sort_order, status, marked_word_count)
    VALUES
    <foreach collection="items" item="it" separator=",">
        (#{it.sessionId}, #{it.questionId}, #{it.sortOrder}, #{it.status}, #{it.markedWordCount})
    </foreach>
</insert>
```

接口侧用 `@Param("items")` 命名集合，XML 里的 `collection` 与之对应
（不命名则要写 `collection="list"`，可读性差且脆弱）。

实测已验证（2026-09-22）：2 条快照一次写入，`sort_order` 顺序正确。

### 4.5 MySQL 语法陷阱：行别名让列引用变歧义

> 🔖 **第 9 次修订新增**

用 MySQL 8.0.19+ 的行别名写法做 upsert 时，**`ON DUPLICATE KEY UPDATE` 里的列引用必须加表名限定**：

```sql
-- ❌ 实测报错：Column 'mark_count' in field list is ambiguous
INSERT INTO user_vocabulary (..., mark_count, ...)
VALUES (..., 1, ...) AS new
ON DUPLICATE KEY UPDATE
    mark_count = mark_count + 1,      -- ← 裸列名，MySQL 分不清是目标表还是 new
    ...

-- ✅ 加限定后通过
INSERT INTO user_vocabulary (..., mark_count, ...)
VALUES (..., 1, ...) AS new
ON DUPLICATE KEY UPDATE
    mark_count = user_vocabulary.mark_count + 1,
    ...
```

**原因**：`AS new` 引入的行别名带有**与目标表同名的列**，裸列名因此产生歧义。
加限定即可消除。

**两种引用的含义完全不同，不能混用**：

| 写法 | 含义 |
|---|---|
| `user_vocabulary.mark_count` | 【已存在那行】的当前值（累加要用这个） |
| `new.mark_count` | 【本次 VALUES 里】的值（在这里是字面量 1） |

**排查提示**：Spring 会把它包成 `DataIntegrityViolationException`，
而**该异常的 `getMessage()` 可能是空字符串**，真正的原因在 cause 链里。
遇到这种情况要把整条 cause 链打出来，不能只打 `getMessage()`。

### 4.6 `resultType` 短名只对 `entity` 包生效

> 🔖 **第 11 次修订新增**

`application.yml` 里配的是：

```yaml
mybatis-plus:
  type-aliases-package: org.example.englishquestionbank.entity
```

因此 XML 里 `resultType="Word"` 能解析，但 **`resultType="SessionSummary"` 不行** ——
`SessionSummary` 在 `dto` 包，不在别名包内。实测报错：

```
BuilderException: Error parsing Mapper XML ...
  Cause: TypeException: Could not resolve type alias 'SessionSummary'
  Cause: ClassNotFoundException: Cannot find class: SessionSummary
```

**处理方式**：非实体类型（`dto` 包里的类）在 XML 里写**全限定名**：

```xml
<select id="selectAnswerSummary"
        resultType="org.example.englishquestionbank.dto.SessionSummary">
```

**为什么不干脆把 `dto` 包也加进 `type-aliases-package`**：
那样虽然更短，但别名的作用域变大后，一旦两个包里出现同名类就会产生别名冲突，
而冲突报错发生在启动阶段、信息又绕。**显式写全限定名没有这个风险，代价只是长一点。**

> 另一个选择是把聚合结果直接映射成实体，但 {@code SessionSummary} 不是任何一张表的行，
> 硬套实体反而扭曲语义。

### 4.7 一个容易写错的查询：按空格分隔标签匹配

`word.tag` 存的是空格分隔标签串（如 `"cet4 cet6 ky"`）。

```sql
-- ❌ 错误：会误匹配任何位置含 ky 子串的值
WHERE tag LIKE '%ky%'

-- ✅ 正确：按词边界匹配
WHERE CONCAT(' ', tag, ' ') LIKE CONCAT('% ', #{tag}, ' %')
```

注意此查询有前置通配符、**无法走索引**；性能问题已登记为待办 **B-01**（见 `backlog.md`）。

---

## 五、分步实现流程

| 步骤 | 内容 | 为什么是这个顺序 | 状态 |
|---|---|---|---|
| **1** | `entity/Word.java` | Mapper 依赖实体字段定义，必须先行 | ✅ 完成 |
| **2** | `mapper/WordMapper.java` | 依赖步骤 1 的实体类型 | ✅ 完成 |
| **3** | `config/MyBatisPlusQueryChecker.java` | 依赖 1、2；用于真实验证查询链路 | ✅ 完成 |
| **4** | 其余 10 张表的实体 + Mapper | 模式已被步骤 1–3 验证，纯复制；放后面避免第 1 张表映射风格有误导致 11 倍返工 | ✅ 完成 |
| **5** | Service 层 | 需要跨表/事务时才有价值（如"提交答题"要同时写 `answer_record` 并更新 `practice_session`） | ⬜ 待办 |
| **6** | REST 接口 | 对外暴露出题、答题、划词 | ⬜ 待办 |

**为什么先单独做 `word` 表**：它是唯一已经有 36884 条真实数据的表，是当时唯一能
真实验证"ORM 能否查出数据"的地方。

---

## 六、实测验证结果

### 环境

| 项 | 值 |
|---|---|
| Spring Boot | 4.1.1 |
| MyBatis-Plus | `mybatis-plus-spring-boot4-starter:3.5.16` |
| mybatis-spring | 4.0.0 |
| MySQL | 8.0.40（本地 `D:\MySQL\MySQL Server 8.0`） |
| Java | 17.0.9 |

### 自检输出（2026-09-21 实测）

```
================ MyBatis-Plus 查询链路自检 ================
[1/4] BaseMapper.selectCount（白送的 CRUD） : 36884 条
[2/4] 自定义 SQL 查询词频前 5 条 :
        1. the          [ðә] art | art. 那
        2. be           [bi:] v | v. 是, 表示, 在
        3. and          [ænd] conj | conj. 和, 与
        4. of           [ɒv] prep | prep. 的, 属于
        5. a            [ei] art | 第一个字母 A; 一个; 第一的
[3/4] 精确查询 'abandon' :
        音标 : ә'bændәn
        词性 : vt/n
        标签 : gk cet4 cet6 ky toefl gre
        词频 : frq=2182 bnc=2057
        释义 : vt. 放弃, 抛弃, 遗弃, 使屈从, 沉溺, 放纵; n. 放任, 无拘束, 狂热
[4/4] 带有 'ky' 标签（考研）的词条数 : 4801
================ ✅ ORM 查询链路正常 ================
```

### 实体映射自检输出（11 个实体）

```
================ 实体 ↔ 表 映射自检 ================
  ✓ Word               -> word               已映射 19 / 实际 19 列
  ✓ Question           -> question           已映射 13 / 实际 13 列
  ✓ SessionQuestion    -> session_question   已映射  6 / 实际  6 列
  ✓ TranslationGrading -> translation_grading 已映射 15 / 实际 15 列
  ✓ UserVocabulary     -> user_vocabulary    已映射 13 / 实际 13 列
  ✓ SysUser            -> sys_user           已映射 10 / 实际 10 列
  ✓ UserWordMark       -> user_word_mark     已映射 13 / 实际 15 列   [未映射: question_key, session_key]
  ✓ PracticeSession    -> practice_session   已映射 14 / 实际 14 列
  ✓ QuestionOption     -> question_option    已映射  6 / 实际  6 列
  ✓ AnswerRecord       -> answer_record      已映射 13 / 实际 13 列
  ✓ Passage            -> passage            已映射 11 / 实际 11 列
--------------------------------------------------
共 11 个实体，全部映射正确
================ ✅ 实体映射无误 ================
```

> `UserWordMark` 显示「13 / 15」并列出两个未映射列，**这是预期结果而非缺陷** ——
> 正是「决定 1：生成列不映射进实体」生效的证据。

### 已验证的能力

| 能力 | 证据 |
|---|---|
| 实体 → 表映射正确 | 19 个字段全部映射成功，含 `display_form → displayForm` 等自动驼峰映射 |
| `BaseMapper` 自带 CRUD 可用 | `selectCount` 自动生成 `SELECT COUNT( * ) AS total FROM word` |
| Mapper 被自动注册 | `已注册 Mapper 数量 : 30`（此前为 0；30 = `BaseMapper` 注册的语句数） |
| 自定义 SQL 可用 | 集合、单对象、标量三种返回形态均正常。**第 7 次修订前用的是 `@Select` 注解，现已全部迁至 XML** |
| 参数绑定安全 | 日志显示 `WHERE headword = ?` + `Parameters: abandon(String)`，走的是预编译 |
| `TEXT` 列读取正常 | `translation` 完整读出，未被截断 |
| 启动时的 Mapper 扫描警告消失 | 之前那条 `No MyBatis mapper was found` 已不再出现 |
| 11 个 Mapper 接口全部注册 | `已注册 Mapper 接口数 : 11`（`已注册 SQL 语句数 : 150`） |
| 11 个实体的列映射全部正确 | `EntityMappingChecker` 与 `information_schema` 实际比对，0 处错误 |

---

## 七、已知风险与未验证项

| 项 | 状态 | 说明 |
|---|---|---|
| 生成列写入行为 | ⚠️ **未验证** | 实体未映射 `session_key`/`question_key`，但"插入时 MyBatis-Plus 是否真的不碰它们"要等真正写入标记数据时才能确认 |
| 批量插入性能 | ⚠️ 未验证 | 目前只有单条查询。后续导入题目可能需要批量 insert |
| 跨表关联查询 | ⚠️ 未验证 | 目前只有单表查询；"我的生词本"等需要 join |
| 事务行为 | ⚠️ 未验证 | Service 层尚未引入 |
| `JSON` 列读写 | ⚠️ 未验证 | `raw_json` 当前全为 NULL，只验证了"能读出不报错" |

---

## 八、踩过的坑

### 1. SLF4J 的 `{}` 不支持 `String.format` 宽度语法

> 🔖 **第 6 次修订修改：补充了更危险的症状，并记录了实际复发次数**

```java
// ❌ 写法一：输出 "1. {:<12} [the] ðә | art"
log.info("        {}. {:<12} [{}] {} | {}", i + 1, w.getHeadword(), ...);

// ❌ 写法二（更危险）：输出 "✅ {:<2} " —— label 与 detail 完全消失
log.info("  {} {:<2} {}", mark, "", label + "  " + detail);

// ✅ 正确：先 format 成整行，再作为「一个」参数传入
String line = String.format("%d. %-12s [%s] %s | %s", i + 1, w.getHeadword(), ...);
log.info("        {}", line);

// ✅ 也可以：只用裸 {}，不要在对齐上做文章
log.info("  {} {}", mark, label + "  " + detail);
```

**机制**：SLF4J 只识别**恰好是 `{}`** 的占位符。`{:<12}`、`{:<2}` 中间有别的字符，
因此**不被当作占位符，而是原样输出**。

**两种症状，第二种更危险**：

| 写法 | 症状 | 危险度 |
|---|---|---|
| `{}. {:<12} [{}]` | 占位符错位，格式串被原样打印出来 | 明显，一眼能看出 |
| `{} {:<2} {}` | **参数被静默丢弃**，日志里内容凭空消失 | **高** —— 不报错、不抛异常、行还在，只是少了内容 |

**实际复发次数：3 次。**
说明「写进文档」并不等于「不再犯」。因此本项目的硬性规则是：

> **`log.*()` 里只允许出现裸 `{}`。任何带格式说明的写法一律先 `String.format` 拼好整行，
> 再作为单个参数传入。**

事后排查用的 grep（在 `src/main/java` 下执行）：

```
log\.(info|debug|warn|error|trace)\([^)]*\{:
```

命中即违规。**本项目当前 0 命中。**

### 2. `<<BLOB>>` 与日志实现无关 —— 是 MyBatis 按 JDBC 列类型做的替换

**现象**：打印结果集行值时，`TEXT` / `JSON` 列显示为 `<<BLOB>>`。

```
<==  Row: 12, abandon, abandon, ?'b?nd?n, null, vt/n, <<BLOB>>, d:abandoned/...
                                                        ↑ translation (TEXT 列)
```

**成因**（从 `mybatis-3.5.19.jar` 的 `ResultSetLogger.class` 常量池直接确认）：
该类的常量池包含 `BLOB_TYPES`、`getColumnType`、`contains`、`<<BLOB>>`、`getString`，
并引用 `java/sql/Types`。即：打印每一列前先取 `rs.getMetaData().getColumnType(i)`，
**若该 JDBC 类型命中 `BLOB_TYPES` 集合，就不调用 `getString()`，直接输出字面量 `<<BLOB>>`**；
取值抛异常时输出 `<<Cannot Display>>`。

**关键点：它判断的是 JDBC 列类型，不是 Java 值类型。**
MySQL 的 `TEXT` / `MEDIUMTEXT` / `JSON` 在 JDBC 元数据中报告为 `LONGVARCHAR`，因而命中。
本项目实测印证：`VARCHAR` 列（如 `exchange`）正常显示取值，
而 `TEXT`（`translation`）与 `JSON`（`raw_json`）都被替换成 `<<BLOB>>`。

> **⚠ 纠正一个曾经的错误结论**：换 `log-impl` **解决不了**这个问题。
> `ResultSetLogger` 决定「值格式化成什么文本」，`Log` 实现只决定「这些文本发到哪、是否输出」，
> 是两层。`<<BLOB>>` 在任何 log-impl 下都会出现。

**要核对长文本的真实取值**，应当：
- 在代码里显式打印实体字段 —— 自检类就是这么做的，`translation` 完整读出，
  证明**实体映射完全正常**，只是 JDBC 层日志做了截断显示
- 或直接在 Navicat 里查库比对

### 3. `StdOutImpl` 与 `Slf4jImpl` 的取舍（已实施并验证）

两者都是 MyBatis 自己的 `Log` 适配器，区别在**级别判断**与**输出去向**：

| | `StdOutImpl`（当前） | `Slf4jImpl` |
|---|---|---|
| 级别判断 | `isDebugEnabled()` / `isTraceEnabled()` **恒返回 true** | 委托真实 SLF4J logger，**真实反映级别** |
| 输出 | 直接 `System.out.println` | 交给日志框架（受编码、appender、级别控制） |
| 默认可见性 | 无条件全打印 | **Spring Boot 默认 INFO ⇒ 什么都看不到** |
| 上线前关闭 | 只能改配置重启 | 调 `logging.level` 即可 |

换成 `Slf4jImpl` 后**必须额外配置**才看得到 SQL：

```yaml
logging:
  level:
    org.example.englishquestionbank.mapper: debug   # 只有 SQL 与参数
    # ...mapper: trace                              # 才包含结果集行值
```

注意 MyBatis 把 **SQL 语句与参数放在 `debug`，结果集行值放在 `trace`**，
因此 `debug` 级别下只会看到 `==>  Preparing:` 与 `==> Parameters:`，看不到 `<== Row:`。

> 该项**已实施**，并于 2026-09-21 实测验证。

**实测对照**（同一个 `abandon` 词条的音标字段）：

| 配置 | 日志输出 |
|---|---|
| `StdOutImpl`（改前） | `<== Row: 12, abandon, abandon, ?'b?nd?n, ...` ← 音标被毁 |
| `Slf4jImpl` + `trace`（改后） | `<== Row: 12, abandon, abandon, ә'bændәn, ...` ← 正确 |

`ðә`（the）/ `ænd`（and）/ `ɒv`（of）等国际音标同样恢复正常。

**同时确认**：`<<BLOB>>` 在两种配置下**都存在** —— 印证了本节第 2 点
「`<<BLOB>>` 与 log-impl 无关」的结论。换 log-impl 只修编码，不修 BLOB 显示。

**级别行为实测**：
- `debug` → 只有 `==>  Preparing:` / `==> Parameters:`，**没有 `<== Row:`**
- `trace` → 才出现 `<== Row: ...`

日志的 logger 名形如 `o.e.e.mapper.WordMapper.selectCount`
（`Mapper接口全限定名.方法名`），因此按包名配置级别即可覆盖该 Mapper 的全部方法。

**当前配置**（`src/main/resources/application.yml`）：

```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
logging:
  level:
    org.example.englishquestionbank.mapper: debug   # 上线前删掉或改成 info
```

---

## 九、相关文件索引

| 文件 | 作用 |
|---|---|
| `src/main/java/.../entity/` | **11 个实体类**（`Word` / `SysUser` / `Passage` / `Question` / `QuestionOption` / `PracticeSession` / `SessionQuestion` / `AnswerRecord` / `TranslationGrading` / `UserWordMark` / `UserVocabulary`） |
| `src/main/java/.../mapper/` | **11 个 Mapper 接口**，均继承 `BaseMapper`，暂无自定义查询 |
| `src/main/java/.../config/EntityMappingChecker.java` | 实体 ↔ 表 映射自检（开发期辅助，可删） |
| `src/main/java/.../config/MyBatisPlusQueryChecker.java` | 查询链路自检（开发期辅助，可删） |
| `src/main/java/.../config/DatabaseConnectionChecker.java` | 数据库连接自检（开发期辅助，可删） |
| `src/main/java/.../config/MyBatisPlusCompatibilityChecker.java` | MP 装配自检（开发期辅助，可删） |
| `src/main/resources/application.yml` | 数据源 / MyBatis-Plus / 日志编码配置 |
| `docs/database-connection.md` | 数据库连接实现说明（上一阶段） |
| `src/main/resources/db/schema.sql` | 11 张表建表脚本 |
