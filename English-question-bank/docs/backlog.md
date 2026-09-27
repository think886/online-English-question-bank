# 待办与待优化清单

> 记录已知问题、待优化项、技术债。完成一项就移到「已完成」并注明日期。

## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1 次 | 2026-09-21 | 初版：录入词形反查性能优化项及若干功能待办 |
| 第 2 次 | 2026-09-22 | 新增 B-11：翻译题的组卷方式未设计（导致翻译题无法作答、其判分分支未验证） |
| 第 3 次 | 2026-09-26 | **B-11 已解决**：新增 `startTranslationSession` 独立组卷入口，`TranslationVerifier` 8/8 验证通过 |
| 第 4 次 | 2026-09-27 | 新增 B-12：错误码不够精确（404 未区分、403 未使用），附 API 层实测证据 |
| 第 5 次 | 2026-09-27 | 新增 **B-13**：查询类接口（生词本 / 错题本 / 历史）尚未实现；新增 **B-14**：`MarkedWordSummary` 只保留一个句子，无法满足「按词看全部语境」；B-10 补充 smoke 脚本；B-12 补充「越权已实测为 409」的证据 |
| 第 6 次 | 2026-09-27 | 新增 **B-15**：任何不存在的 URL 都返回 500 而非 404（`NoResourceFoundException` 被 `Exception` 兜底吞掉，已附实测证据）；新增 **B-16**：端口不统一（IDEA 8080 / 文档 18080）已实际造成一次困惑 |

---

## 一、性能优化

### B-01 · 词形反查是全表扫描

> 🔖 **第 1 次修订新增**

| 项 | 内容 |
|---|---|
| **来源** | `docs/service-layer.md` 3.4 节（词形归一化） |
| **现状** | `WordMapper.selectByExchangeForm` 使用 `CONCAT(exchange, '/') LIKE CONCAT('%:', #{form}, '/%')`。前置通配符 `%` 导致**无法走索引**，每次划词触发一次全表扫描 |
| **当前影响** | 可接受。`word` 表 36884 行，划词由用户主动触发（非高并发） |
| **待补** | ⚠️ **未做性能压测** —— 只有定性判断，没有实测 QPS/耗时数据 |
| **正确的解法** | 在词典**导入阶段**额外生成一张「形式 → 原形」的反向索引表（如 `word_form(form, word_id)`），归一时直接按 `form` 索引查询。**不要去优化这条 SQL**（如加函数索引），那是治标 |
| **触发条件** | 划词 QPS 上升，或 `word` 表显著变大（引入全量词典后可能到几十万行） |
| **优先级** | 低 |

### B-02 · 词典 `word` 表的美式音标列全为空

> 🔖 **第 1 次修订新增**

| 项 | 内容 |
|---|---|
| **现状** | `word.phonetic_us` 全部为 NULL —— ECDICT 只提供一套音标（偏英式），没有美式 |
| **影响** | 前端若同时展示英/美音标，美式一列会空白 |
| **可选解法** | ① 接受现状，前端只展示一个音标 ② 从其他词库补充（如 Merriam-Webster 数据，需注意授权） ③ 接入外部词典 API 按需补 |
| **优先级** | 低（不影响核心功能） |

---

## 二、功能待办

### B-03 · 取消标记（unmark）

> 🔖 **第 1 次修订新增**

- **现状**：只能标记，不能取消。用户误点后无法撤销
- **为什么本轮不做**：需要额外的 `user_vocabulary.mark_count` 递减逻辑，且计数归零时是否删除生词本条目需要单独定义语义
- **优先级**：中（体验相关）

### B-04 · 查词的外部 API 回写

> 🔖 **第 1 次修订新增**

- **现状**：`word` 表未收录的词，`user_word_mark.word_id` 写 NULL，不查释义
- **已预留**：`word.lookup_status` 的 `API` / `PENDING` / `NOT_FOUND` 三态就是为此设计的
- **注意**：`NOT_FOUND` 必须写入一条空记录，否则每次划到同一个生僻词都会重复请求外部接口（缓存穿透）
- **优先级**：中

### B-05 · 翻译题的 LLM 评分

> 🔖 **第 1 次修订新增**

- **现状**：`AnswerService.submitAnswer` 对翻译题只把 `grading_status` 置为 `PENDING`，不做评分
- **已预留**：`translation_grading` 表已建好（多维得分、点评、原始返回、`is_current` 支持重评）
- **优先级**：中

### B-06 · 题库抽取（真题入库）

> 🔖 **第 1 次修订新增**

- **现状**：`passage` / `question` / `question_option` 三张表是空的
- **已确定**：排在所有技术验证之后做
- **已调研**：CET-4/6 阅读有 3 种题型，其中只有「仔细阅读」是标准 A/B/C/D，能直接用现有 schema；
  选词填空与段落匹配装不进 `question_option`，若要做需扩展题型枚举
- **优先级**：中（阻塞实际使用，但不阻塞开发）

### B-07 · 登录鉴权与密码加密

> 🔖 **第 1 次修订新增**

- **现状**：`sys_user.password_hash` 保持为空；`SysUserService` 只做建用户与查用户
- **未讨论**：Session vs JWT、是否需要游客模式等方案尚未定
- **优先级**：中

### ~~B-11 · 翻译题的组卷方式未设计~~ ✅ 已解决（2026-09-26）

> 🔖 **第 3 次修订修改：本项已完成，从「功能待办」移入「已完成」**

**问题**：翻译题的 `passage_id` 为 NULL（不属于任何文章），而 `startSession` 只按文章组卷，
因此翻译题无法进入任何会话、无法被作答；其判分分支也因此一直未验证。

**采用方案**：新增独立组卷入口 `PracticeSessionService.startTranslationSession(userId, count)`，
按 `question_type = 'TRANSLATION' AND status = 1` 取题（按 `id` 升序，不做随机抽题）。

**验证结果**：`TranslationVerifier` 8/8 通过（见 `docs/service-layer.md` 4.6 节），
其中确认了「`is_correct` 为 NULL 的待评分翻译题不会污染会话的对题数」。

### B-12 · 错误码不够精确（404 未区分、403 未使用）

> 🔖 **第 4 次修订新增**

| 项 | 内容 |
|---|---|
| **现象 1** | 请求不存在的文章返回 **409 CONFLICT**，而非语义正确的 **404 NOT_FOUND** |
| **现象 2** | 「会话不属于该用户」也返回 409，而非 **403 FORBIDDEN**（第 5 次修订补充：已在 `tools/api-smoke.ps1` 中实测确认，见 `docs/api-layer.md` 9.5） |
| **根因** | 全局异常映射把 `IllegalStateException` 统一映射成 409，无法区分「资源不存在 / 无权访问 / 状态冲突」。而 Service 只抛 `IllegalArgumentException` 与 `IllegalStateException` 两种异常 |
| **影响** | 前端无法做差异化提示（「换一篇文章」vs「无权访问」）；监控告警分不清错误性质 |
| **为什么不立刻修** | 精确区分需要在 Service 层引入能表达「资源不存在 / 无权访问」的异常类型，即引入业务异常体系。第 1 次设计时已论证**不引入**（避免 Service 感知 HTTP 语义），为一个场景破例不划算。等接口补齐、真实前端需求明确后再统一处理更合适 |
| **可选方案** | ① 引入极薄的业务异常（携带错误码，不感知 HTTP）；② Service 返回结果对象而非抛异常；③ 保持现状，仅在需要的接口由 Controller 做前置校验 |
| **触发条件** | 接口补齐后、前端提出差异化错误提示需求时 |
| **参考** | `docs/api-layer.md` 第 9.4 节（实测证据） |
| **优先级** | 中 |

### B-13 · 查询类接口尚未实现（生词本 / 错题本 / 历史记录）

> 🔖 **第 5 次修订新增**

| 项 | 内容 |
|---|---|
| **现状** | 本轮 API 只覆盖「做题主链路」（开始 → 划词 → 作答 → 交卷 → 结果页）。`GET /api/me/vocabulary`（生词本）、错题本、练习历史**都还没有接口** |
| **哪些已就绪** | Service 方法全部写好并验证过：`WordMarkService.listVocabulary(userId, limit)`、`AnswerService.listWrongByUser(userId, limit)`、`AnswerService.listBySession(sessionId)`。只差 Controller 与响应 DTO |
| **必须先定的问题** | **分页方案**。一旦有清单类接口，`limit` 参数就不够了，需要引入 MyBatis-Plus 的 `Page` 插件（属「复杂操作」，按项目规范走 XML）。分页参数形态（`page`/`size` vs `cursor`）也需先定 |
| **为什么本轮不做** | 第 3 次设计决策明确「核心链路 + 结果页」优先。结果页已兑现最初的需求，清单页是增量 |
| **参考** | `docs/api-layer.md` 第十一节 |
| **优先级** | 中 |

### B-14 · 结果页的标记词只保留一个句子快照

> 🔖 **第 5 次修订新增**

| 项 | 内容 |
|---|---|
| **现状** | `MarkedWordSummary` 的 `firstSentence` 只取「文本中最靠前的那次标记」的句子。同一个词若在文中出现 3 次、用户标了 3 次，结果页只能看到其中 1 个语境 |
| **影响** | 复习时看不到该词的其他用法。对「一词多义」的词损失明显 |
| **可选解法** | ① `MarkedWordSummary` 改为带一个句子列表（去重后，限制条数）；② 结果页点开某个词时再单独请求该词的全部标记（`GET /api/me/marks?form=run`） |
| **附带** | 顶层 `markedWords` 刻意不带锚点（`questionId`/`sourceField`）是正确的 —— 同一个词锚点不唯一。但这也意味着「想从某个词跳到原文位置」必须有方案 ② |
| **优先级** | 低（当前每会话标记量小，实际影响有限） |

### B-15 · 任何不存在的 URL 都返回 500，而不是 404

> 🔖 **第 6 次修订新增**

| 项 | 内容 |
|---|---|
| **现象** | 访问任何不存在的地址都返回 **500 INTERNAL_ERROR**，而不是 404。实测（2026-09-27，`--server.port=18080`）：<br>`/swagger-ui/` → 500<br>`/no-such-page` → 500<br>`/api/no-such-endpoint` → 500<br>响应体统一为 `{"code":"INTERNAL_ERROR","message":"服务器内部错误，请稍后重试","data":null}` |
| **原始堆栈** | `org.springframework.web.servlet.resource.NoResourceFoundException: No static resource for request '/swagger-ui/'.`<br>`at ...ResourceHttpRequestHandler.handleRequest(ResourceHttpRequestHandler.java:527)`<br>`at org.example.englishquestionbank.api.GlobalExceptionHandler : 未预期的服务端异常` |
| **根因** | `NoResourceFoundException` **自带 HTTP 404 状态**（它是 `ErrorResponseException` 的子类），但 `GlobalExceptionHandler` 里的 `@ExceptionHandler(Exception.class)` 兜底把它**覆盖成了 500**，并把整条堆栈当"未预期的服务端异常"打进日志 |
| **影响** | ① 前端无法区分「我把地址写错了」（404）与「服务端挂了」（500）；② 日志里会堆积大量**假告警**，掩盖真正的 500；③ 用户自己就被误导过一次（以为接口文档坏了） |
| **修法（建议）** | 在 `GlobalExceptionHandler` 增加 `@ExceptionHandler(ErrorResponseException.class)`，**尊重异常自带的状态码**（此处 404），而不是改成 500。优先级高于 `Exception` 兜底，改动约 10 行 |
| **与 B-12 的关系** | 同属「错误码不精确」，但机制不同：B-12 是 **Service 抛的异常无法表达 HTTP 语义**（要引入业务异常体系）；B-15 是 **Spring MVC 自己抛的、已带状态码的异常被兜底盖掉**（只需一个精确 handler）。因此分列两项 |
| **未验证** | 「去掉兜底后 Spring Boot 默认会返回 404」这一条**尚未单独实测**，是在修复后需要验证的第一件事 |
| **触发条件** | 随时可做，成本极低 |
| **优先级** | 中高（改动小、收益明确，且是加监控告警前必须清的噪音） |

### B-16 · 端口不统一：IDEA 跑 8080，文档写 18080

> 🔖 **第 6 次修订新增**

| 项 | 内容 |
|---|---|
| **现状** | `src/main/resources/application.yml` 里**没有 `server.port`** → Spring Boot 用默认 **8080**。<br>而 `AGENTS.md` 的「常用命令」与 `docs/api-layer.md` 第 9 节的实测记录都写 **18080**（命令行加 `--server.port=18080`）。<br>结果：**IDEA 里 Run 是 8080，命令行那条命令是 18080** |
| **已造成的实际问题** | 2026-09-27：在 IDEA 里跑起来后，按文档里的 `http://127.0.0.1:18080/swagger-ui/index.html` 打开接口文档 → 连接被拒，误以为文档坏了。实际应用在 8080，文档地址本身是好的 |
| **为什么当初这么设计** | 18080 是为了「避免与 IDEA 里的 8080 冲突」，让命令行实例与 IDEA 实例可以并存 |
| **可选方案** | ① 在 `application.yml` 写死 `server.port: 8080`，两边统一，文档只写一个地址（放弃并存能力）；② 写死 18080 并让 IDEA 的 Run Configuration 也用 18080；③ 保持现状，但在所有文档的地址旁**显式标注端口随启动方式而变** |
| **优先级** | 低（不影响功能，但已造成过一次真实困惑，值得收敛） |

---

## 三、技术债 / 上线前必须处理

### B-08 · 数据库密码明文写在 git 跟踪的文件里

> 🔖 **第 1 次修订新增**

- **现状**：`src/main/resources/application.yml` 里的 `spring.datasource.password` 是明文，且该文件**被 git 跟踪**（`.gitignore` 未忽略）
- **风险**：推到公开仓库即泄露
- **解法**：改成 `spring.datasource.password: ${DB_PASSWORD:}`，密码由环境变量提供
- **优先级**：**高**（在任何对外发布之前必须处理）

### B-09 · SQL 日志上线前需关闭

> 🔖 **第 1 次修订新增**

- **现状**：`application.yml` 里 `logging.level.org.example.englishquestionbank.mapper: debug`，会打印全部 SQL
- **解法**：改成 `info` 或删除该行
- **优先级**：高（上线前）

### B-10 · 开发期自检类需要清理

> 🔖 **第 1 次修订新增；第 5 次修订补充**

- **现状**：`config` 包下有 6 个 `CommandLineRunner` 自检类，每次启动都执行
- **解法**：删除，或加 `@Profile("dev")` 限制只在开发环境生效
- **第 5 次修订补充**：开发期工具现在还包括 `tools/api-smoke.ps1`（接口冒烟测试，
  会临时建测试用户再删除）。它不在应用进程里、不会在启动时执行，
  但发布时也应一并归档或移出交付物
- **清单见** `docs/README.md` 第三节
- **优先级**：中（上线前）

> ⚠️ **写自检类时的注意**（第 5 次修订新增）：
> 自检**不要断言全局绝对值**（「该用户的 `mark_count` 必须是 1」「该用户的错题必须正好 2 条」），
> 那等于假设「除我以外没人写这个用户的数据」，手工测一次就会误报。
> 应该断言**自己造成的增量**，或把查询结果过滤到自己创建的那个 session/用户上。
> 详见 `docs/api-layer.md` 9.7。

---

## 四、已完成

（暂无）
