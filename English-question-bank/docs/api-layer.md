# API 层设计说明

> 范围：REST 接口清单与契约、统一响应体、异常映射、认证上下文占位、DTO 分层、实现顺序。
> 本文可独立阅读，不依赖聊天记录。

## 修订记录

| 修订 | 日期 | 变更内容 |
|---|---|---|
| 第 1 次 | 2026-09-26 | 初版：确立四项设计决策、完整接口清单与契约、实现顺序、技术栈增补与风险 |
| 第 2 次 | 2026-09-27 | **验证并确定 springdoc 版本为 3.1.1**（原第 1 次的版本风险已消除）；完成基础件与「开始练习」两个接口；新增第三节记录实测结果与安全红线检查；发现并登记「404 未区分」问题 |
| 第 3 次 | 2026-09-27 | **接口全部完成并实测**：划词标记 / 提交答案 / 交卷 / 结果页 4 个接口；新增 `WordMarkController`，`PracticeController` 补齐 3 个方法；**修掉 `MarkedWordResponse` 缺 `questionId`/`sourceField` 导致标记无法回显定位的缺陷**；交卷与结果页加归属校验（Service 签名变更）；新增 `tools/api-smoke.ps1`（53 项全通过）；**修正本文档两个「## 八」重号的章节**；补齐第 5 节契约的真实字段名 |
| 第 4 次 | 2026-09-27 | 【纠正】第 9.5 节把接口数写作「8 个」，**实际为 7 个**（数错原因：把类级 `@RequestMapping` 也当成了接口）。同时发现一个未登记的真实缺陷：**任何不存在的 URL 都返回 500 而非 404**，已登记为 `backlog.md` 的 **B-15** |

---

## 一、已确认的设计决策

| # | 决策 | 选择 | 理由摘要 |
|---|---|---|---|
| 1 | 响应体风格 | **HTTP 状态码表语义 + 响应体带业务码** | 兼顾前端处理与网关/监控可见性 |
| 2 | 鉴权 | **URL 按最终形态设计 + 临时占位** | 避免以后改鉴权时重写所有 URL |
| 3 | 本轮范围 | **核心链路 + 结果页** | 先把主流程打通，查询类接口次之 |
| 4 | 接口文档 | **引入 springdoc-openapi** | 可在浏览器直接试接口，调试成本低 |

---

## 二、三条不可违反的红线

这三条都会造成安全问题，**不是风格问题**：

### 红线 1 · 绝不直接返回实体

`QuestionOption` 带 `isCorrect`、`SysUser` 带 `passwordHash`、`AnswerRecord` 带判分结果。
接口层必须使用独立的响应 DTO，把不该给的字段挡在外面。

### 红线 2 · 交卷前不返回答案

- 开始练习的响应里**不能有** `question_option.is_correct`
- 翻译题开始练习时**不能有** `question.reference_answer`
- `analysis`（解析）同样只在**交卷后**的结果页返回

否则用户打开浏览器 DevTools 就能看到答案。

### 红线 3 · `userId` 绝不作为 URL 或请求参数

一律从**请求上下文**取。否则改一个 id 就能看别人的生词本，而且将来接入鉴权时要重写所有 URL。

---

## 三、统一响应体与异常映射

### 3.1 响应体结构（成功与失败同构）

```json
{
  "code": "INVALID_PARAM",
  "message": "题量必须在 1~50 之间，收到: 0",
  "data": null
}
```

- 成功时 `code = "OK"`、`message = "success"`、`data` 为业务数据
- 失败时 `data` 为 `null`，`code` 给出可编程判断的业务码
- **HTTP 状态码同时反映语义**，不是恒 200

### 3.2 异常映射表

由 `@RestControllerAdvice` 集中处理，Service 层不需要感知 HTTP：

| Service 抛出的异常 | HTTP | 业务码 | 说明 |
|---|---|---|---|
| `IllegalArgumentException` | 400 | `INVALID_PARAM` | 入参非法 |
| （未登录，将来） | 401 | `UNAUTHENTICATED` | 认证失败 |
| 数据不属于当前用户 | 403 | `FORBIDDEN` | 越权访问 |
| 会话/题目/文章不存在 | 404 | `NOT_FOUND` | 资源不存在 |
| `IllegalStateException` | 409 | `CONFLICT` | 状态冲突（会话已结束、重复交卷） |
| `DuplicateKeyException` | 409 | `DUPLICATE` | 唯一键冲突（用户名已存在） |
| 其他未预期异常 | 500 | `INTERNAL_ERROR` | **不把堆栈返回给前端**，只记日志 |

> **为什么保留 Service 现有的异常类型**：它们是「入参错误 vs 状态错误」的天然区分，
> 正好对应 400 与 409。若为此新建一套异常体系，Service 层就会被迫感知 HTTP 语义，
> 反而耦合。

---

## 四、认证上下文占位

### 4.1 URL 按最终形态设计

```
✅ /api/me/vocabulary            用户自己的数据
✅ /api/practices/{sessionId}    资源本身带 id，但归属由上下文校验
❌ /api/users/{userId}/vocabulary
❌ /api/practices?userId=123
```

### 4.2 占位实现

```java
@Component
public class CurrentUserProvider {
    /** 本轮从请求头取；将来换成从 JWT 解析，调用方代码不变。 */
    public Long currentUserId();
}
```

Controller 只依赖这个抽象，**不知道** userId 从哪来。将来换成 JWT 时：

- 只需替换 `CurrentUserProvider` 的实现
- Controller、URL、DTO 全部不动

> ⚠️ 占位实现必须在代码与文档里明确标注为**临时且不安全**，
> 避免误以为它是可上线的鉴权。相关缺口见 `docs/backlog.md` 的 **B-07**。

---

## 五、接口清单

### 5.1 开始练习

#### `POST /api/practices/reading`

请求：`{"passageId": 1}`

响应 `201 Created`：

```json
{
  "code": "OK",
  "data": {
    "sessionId": 123,
    "mode": "READING",
    "totalCount": 2,
    "maxScore": 2,
    "passage": {
      "id": 1, "title": "Community Gardens",
      "content": "Many cities are turning...",
      "source": "示例数据（非真题）", "category": "社会"
    },
    "questions": [
      {
        "id": 1, "seq": 1, "score": 1,
        "stem": "What is the main idea of the passage?",
        "options": [
          {"key": "A", "content": "Community gardens are only a short-lived fashion."},
          {"key": "B", "content": "Community gardens are increasingly treated as part of city planning."}
        ]
      }
    ]
  }
}
```

> 注意 `options` 里**没有 `isCorrect`**。

#### `POST /api/practices/translation`

请求：`{"count": 1}`

响应同上结构，但 `mode = "TRANSLATION"`、无 `passage`；
每题的 `sourceText` 是待翻译的中文原文，**不含 `referenceAnswer`**。

### 5.2 练习过程

> 🔖 **第 3 次修订修改：以下契约均已实测，字段名以实测为准**（第 1 次修订时是按设计写的）

#### `POST /api/practices/{sessionId}/marks` —— 划词标记

请求：

```json
{
  "questionId": 1,
  "sourceField": "STEM",
  "surfaceForm": "running",
  "sentence": "He is running fast.",
  "charStart": 5,
  "charEnd": 12
}
```

响应 `200`：

```json
{
  "code": "OK",
  "data": {
    "markId": 10, "normalizedForm": "run", "wordId": 28313,
    "translation": "n. 赛跑, 流出, 运转; a. 流动的",
    "alreadyMarked": false
  }
}
```

> 返回 `translation` 让前端**一次调用就拿到释义**，不必再查一次词典 ——
> 这是划词体验的关键。
>
> `charStart` / `charEnd` **由前端计算**（前端手里有原文，能精确知道用户划的是哪一处；服务端算不准，因为同一个词可能出现多次）。服务端负责校验偏移量落在文章长度范围内。
>
> **请求体里没有 `passageId`**：它由服务端从会话推导。前端在文章正文上划词时并不知道文章在库里的主键，也不该知道（第 3 次修订起如此）。
>
> 三种结果都是 `200`，靠 `alreadyMarked` 区分：`false` = 新建；`true` = 该位置之前标过，本次是空操作（不新增行、不累加计数）。

#### `GET /api/practices/{sessionId}/marks` —— 本次会话已标记的词（**未去重，带位置**）

响应 `200`，按 `charStart` 升序：

```json
{
  "code": "OK",
  "data": [
    { "questionId": 1, "sourceField": "STEM", "surfaceForm": "harvest",
      "normalizedForm": "harvest", "wordId": 14763,
      "sentence": "They harvest vegetables.", "charStart": 5, "charEnd": 12 },
    { "questionId": null, "sourceField": "PASSAGE", "surfaceForm": "running",
      "normalizedForm": "run", "wordId": 28313,
      "sentence": "He is running fast.", "charStart": 10, "charEnd": 17 }
  ]
}
```

> **`questionId` 与 `sourceField` 是必需的，不是可选的装饰**（第 3 次修订补上）：
> 偏移量本身不自带锚点 —— `charStart=5` 究竟是「第 1 题题干的第 5 个字符」
> 还是「文章正文的第 5 个字符」？只给偏移量，前端无法把高亮画回正确的那段文本上，
> 而这个接口存在的理由恰恰就是「把标记渲染回原文」。详见 8.6。
>
> 练习**进行中**就要调这个接口把已有标记画出来，因此它不能等到交卷。它不返回答案，随时可调。

#### `POST /api/practices/{sessionId}/answers` —— 提交一题答案

请求（阅读题）：`{"questionId": 1, "userAnswer": "B"}`
请求（翻译题）：`{"questionId": 3, "userAnswer": "With the growth of cities..."}`

响应 `200`：

```json
// 阅读题
{"questionId": 1, "isCorrect": true, "score": 1, "maxScore": 1,
 "gradingStatus": "DONE", "firstSubmit": true}

// 翻译题
{"questionId": 3, "isCorrect": null, "score": null, "maxScore": 15,
 "gradingStatus": "PENDING", "firstSubmit": true}
```

> **重复提交同一题是「覆盖」语义**（用户改答案），因此天然幂等，不需要额外幂等键；
> `firstSubmit=false` 表示这次覆盖了上一次。
>
> 用 `POST` 而不是 `PUT`：客户端并不知道这次作答在服务端的资源标识
> （唯一键是 `session_id + question_id`，由服务端决定），与 `PUT` 的「客户端指定 URI」语义不符。
>
> 会话已交卷后再提交 → **409**。

#### `POST /api/practices/{sessionId}/finish` —— 交卷

响应 `200`（`SessionSummaryResponse`，与结果页里的 `session` 同构）：

```json
{
  "code": "OK",
  "data": {
    "id": 123, "mode": "READING", "status": "FINISHED",
    "totalCount": 2, "answeredCount": 2, "correctCount": 1,
    "score": 1, "maxScore": 2, "markedWordCount": 2,
    "startedAt": "2026-09-27T20:37:01", "finishedAt": "2026-09-27T20:37:02",
    "durationMs": 55
  }
}
```

> 交卷**不是幂等**操作：重复交卷返回 **409**，而不是静默成功。
> 第二次交卷会重算 `duration_ms`，继续改写一个已结束的会话没有意义。
>
> `maxScore` 在开始练习时就固定为会话内全部题目分值之和，**交卷时重算的是
> `answeredCount` / `correctCount` / `score` / `markedWordCount`**，不含 `maxScore`。

### 5.3 结果页（本轮收尾接口，也是核心需求的兑现）

> 🔖 **第 3 次修订修改：补齐真实字段名，并说明「为什么顶层标记词没有锚点」**

#### `GET /api/practices/{sessionId}/result`

```json
{
  "code": "OK",
  "data": {
    "session": { "id": 123, "mode": "READING", "status": "FINISHED",
                 "totalCount": 2, "answeredCount": 2, "correctCount": 1,
                 "score": 1, "maxScore": 2, "markedWordCount": 2,
                 "startedAt": "...", "finishedAt": "...", "durationMs": 55 },
    "passage": { "id": 1, "title": "Community Gardens", "content": "...",
                 "source": "示例数据（非真题）", "category": "社会" },
    "answers": [
      {
        "questionId": 1, "seq": 1, "questionType": "READING",
        "stem": "What is the main idea...",
        "userAnswer": "B", "isCorrect": true, "score": 1, "maxScore": 1,
        "gradingStatus": "DONE",
        "correctAnswer": "B", "referenceAnswer": null, "analysis": null,
        "markedWords": [
          {"questionId": 1, "sourceField": "STEM", "surfaceForm": "harvest",
           "normalizedForm": "harvest", "wordId": 14763,
           "sentence": "They harvest vegetables.", "charStart": 5, "charEnd": 12}
        ]
      }
    ],
    "markedWords": [
      {"normalizedForm": "run", "displayForm": "run",
       "translation": "n. 赛跑…", "wordId": 28313,
       "markCount": 2, "firstSentence": "He is running fast."}
    ]
  }
}
```

> **两层 `markedWords` 语义不同，不要混用：**
>
> | 位置 | 结构 | 是否去重 | 是否带锚点（`questionId`/`sourceField`/偏移量） |
> |---|---|---|---|
> | `data.answers[].markedWords` | `MarkedWordResponse` | ❌ 每次划词一行 | ✅ 有 |
> | `data.markedWords` | `MarkedWordSummaryResponse` | ✅ 按原形去重 | ❌ **刻意没有** |
>
> 顶层那份之所以不带锚点：同一个词可能在多处（不同题、正文、选项）被标，
> 「这个词的位置」并不唯一。要定位就往下钻到单题明细。
>
> **只有 `FINISHED` 的会话能拿到这个响应**，否则返回 409。
>
> **`correctAnswer` / `analysis` / `referenceAnswer` 只在这里出现** —— 交卷之后才给。
>
> 这个接口一次兑现了项目最初的需求：
> 「完成题目后有已标记的单词及其翻译，以及记录用户做过的题目，以及其在题目中标记过的单词」。

#### 两层 `markedWords` 与「在文章正文上划的词」

在文章正文上划的词 `questionId` 为 `null`（正文不属于任何一题），
因此**不会出现在任何 `answers[].markedWords` 里**，但一定会出现在顶层 `data.markedWords` 里。
两者都不是「全量」，需要全量带位置的请用 `GET /api/practices/{id}/marks`。

---

## 六、DTO 分层

> 🔖 **第 3 次修订修改：补齐实际文件清单**

```
api/dto/
├── request/    请求体（自带 Bean Validation 注解）
│   ├── StartReadingRequest       passageId
│   ├── StartTranslationRequest   count
│   ├── MarkWordRequest           questionId? / sourceField / surfaceForm
│   │                             / sentence? / charStart / charEnd
│   └── SubmitAnswerRequest       questionId / userAnswer
└── response/   响应体（只含可以给前端的字段）
    ├── ApiResponse<T>            统一外壳（注意：在 api/ 包下，不在 response/ 下）
    ├── PracticeStartResponse     开始练习的响应
    ├── PassageResponse
    ├── QuestionResponse
    ├── OptionResponse            ← 刻意没有 isCorrect
    ├── MarkResultResponse        markId / normalizedForm / wordId / translation / alreadyMarked
    ├── MarkedWordResponse        单次划词（带 questionId + sourceField + 偏移量）
    ├── MarkedWordSummaryResponse 去重后的词 + 释义 + markCount（刻意不带锚点）
    ├── AnswerResultResponse      questionId / isCorrect / score / maxScore / gradingStatus / firstSubmit
    ├── SessionSummaryResponse    会话汇总（交卷与结果页共用）
    ├── AnswerDetailResponse      结果页单题明细（**全项目唯一返回正确答案之处**）
    └── PracticeResultResponse    结果页整体
```

**为什么不复用 Service 的 `dto` 包**：

| | Service DTO | API DTO |
|---|---|---|
| 定位 | 内部契约 | **对外契约** |
| 变更影响 | 只影响本项目调用方 | **会影响所有前端** |
| 字段控制 | 无所谓 | 必须逐字段审查 |

两者变更节奏不同，混用迟早会把内部字段泄漏出去。

---

## 七、技术栈增补

| 依赖 | 版本要求 | 状态 |
|---|---|---|
| `spring-boot-starter-validation` | 由 Boot BOM 托管（4.1.1 已托管） | 待加 |
| `springdoc-openapi-starter-webmvc-ui` | **必须 3.x** | 待加，**有兼容性风险** |

### ⚠️ springdoc 的版本陷阱 —— ✅ 已解决

> 🔖 **第 2 次修订修改：风险已消除，版本确定为 3.1.1**

- 本地 m2 缓存的最高版本是 **2.8.9**，那是 **Spring Boot 3.x 线，不能用**
- Boot 4 需要 **3.x**：v3.0.0 发布说明写着 `Upgrade to Spring Boot 4.0.0!`
- **更匹配的是 3.1.x**：v3.1.0 发布说明写着 `Upgrade Spring Boot to version 4.1.0`，
  正好对应本项目的 **Boot 4.1.1**

**最终采用 `springdoc-openapi-starter-webmvc-ui:3.1.1`，已实测可用**（见第三节）。

---

## 八、实现进度

> 🔖 **第 2 次修订新增；第 3 次修订更新为全部完成**

| 步 | 内容 | 状态 |
|---|---|---|
| 1 | 验证 springdoc 3.x 能在 Boot 4.1.1 启动 | ✅ 完成，采用 3.1.1 |
| 2 | 统一响应体 + 全局异常映射 | ✅ 完成 |
| 3 | `CurrentUserProvider` 占位 | ✅ 完成 |
| 4 | API DTO（响应模型 + 转换） | ✅ 完成（请求 4 个 + 响应 12 个） |
| 5 | 开始练习两个接口（reading / translation） | ✅ 完成 |
| 6 | 划词标记接口（POST + GET） | ✅ 完成 |
| 7 | 提交答案 + 交卷接口 | ✅ 完成 |
| 8 | 结果页接口 | ✅ 完成 |
| 9 | 全链路实测 | ✅ 完成：`tools/api-smoke.ps1` **53 项全通过** |

### 已实现文件

```
api/
├── ErrorCode.java                业务码 + 绑定的 HTTP 状态码
├── ApiResponse.java              统一响应体（成功/失败同构）
├── GlobalExceptionHandler.java   Service 异常 → HTTP 的集中映射
├── UnauthenticatedException.java
├── CurrentUserProvider.java      身份占位（从 X-Debug-User-Id 取）
├── controller/
│   ├── PracticeController.java   开始阅读/翻译、提交答案、交卷、结果页
│   └── WordMarkController.java   划词标记（POST + GET，挂在 /api/practices/{id}/marks）
└── dto/                          见第六节
```

**Controller 与 Service 的职责边界（第 3 次修订明确）**：

| | 职责 | 不该做的事 |
|---|---|---|
| Controller | 取身份、调 Service、把 Service DTO 转成响应 DTO | 用 Mapper、写访问规则 |
| Service | 业务规则、**访问边界** | 感知 HTTP |

因此「只能看自己的会话」「没交卷不给答案」这两条**放在 Service 而不是 Controller**。

配套的 Service 侧新增/变更：

- `PracticeSessionService.loadContent(sessionId)` —— 开始练习与结果页共用同一条读取路径
- `PracticeSessionService.loadResult(userId, sessionId)` —— **第 3 次修订新增**，含归属与交卷状态校验
- `PracticeSessionService.finishSession(userId, sessionId)` —— **第 3 次修订变更签名**（原先只有 `sessionId`，
  意味着猜到 id 就能交别人的卷；现在带 `userId` 做归属校验）
- `WordMarkService.summarizeSessionMarks(userId, sessionId)` —— 结果页所需的去重标记词

---

## 九、实测结果

> 🔖 **第 2 次修订新增；第 3 次修订补 9.5 / 9.6**
>
> 【纠正】原本次节编号为「八」，与后面的「分步实现顺序」重号；第 3 次修订起
> 实测结果为第九节，分步实现顺序为第十节。

实测日期：**2026-09-27**。应用启动后逐个 `curl`。

### 9.1 基础件

```
GET /v3/api-docs            → HTTP 200，openapi 3.1.0，paths 已登记全部接口
GET /swagger-ui/index.html  → HTTP 200
GET /actuator/health        → HTTP 200（未受影响）
```

### 9.2 四个场景

| 场景 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 带身份头开始阅读练习 | 201 + 内容 | `201 {"code":"OK","data":{"sessionId":39,"mode":"READING","totalCount":2,"maxScore":2,...}}` | ✅ |
| 不带身份头 | 401 | `401 {"code":"UNAUTHENTICATED","message":"缺少身份信息：请带上请求头 X-Debug-User-Id…"}` | ✅ |
| 请求体缺 `passageId` | 400 | `400 {"code":"INVALID_PARAM","message":"passageId: passageId 不能为空"}` | ✅ |
| **不存在的文章** | 404 | `409 {"code":"CONFLICT","message":"该文章下没有可用题目: passageId=99999"}` | ⚠️ **不精确，见 8.4** |
| 翻译练习 `count=1` | 201 | `201 mode=TRANSLATION passage=null maxScore=15`，返回待翻译中文原文 | ✅ |
| 翻译练习 `count=0` | 400 | `400 {"code":"INVALID_PARAM","message":"count: 题量必须大于或等于 1"}` | ✅ |

### 9.3 安全红线检查（逐字段）

对「开始练习」的完整响应（1820 字符）做逐字段检查：

```
✅ 未出现: isCorrect        ✅ 未出现: referenceAnswer
✅ 未出现: analysis         ✅ 未出现: translation（文章全文译文）
✅ 未出现: passwordHash     ✅ 未出现: is_correct
```

选项的实际内容：

```json
{"key":"A","content":"Community gardens are only a short-lived fashion."}
{"key":"B","content":"Community gardens are increasingly treated as part of city planning."}
```

题目字段清单：`id, questionType, seq, stem, score, sourceText, options` ——
**没有** `analysis`、`referenceAnswer`。

**红线 1 与红线 2 均已实测守住。**

### 9.4 ⚠️ 新发现的问题：404 未区分

**现象**：请求一个不存在的文章，返回的是 **409 CONFLICT**，而不是语义正确的 **404 NOT_FOUND**。

**根因**：`startSession` 对「文章下没有题目」抛 `IllegalStateException`，
而全局映射把该异常统一映射成 409。它无法区分「文章不存在」与「文章存在但没题」。

**影响**：前端无法据此提示「文章不存在，请换一篇」；监控里也分不清是资源缺失还是状态冲突。

**为什么不现在修**：要精确区分，需要让 Service 抛出能表达「资源不存在」的异常类型，
这等于引入业务异常体系。第 1 次修订时已论证过**不引入**（避免 Service 感知 HTTP 语义），
因此现在为了这一个场景破例并不划算。

**已登记为待办 B-12**（见 `docs/backlog.md`），与 B-07 鉴权一起在接口补齐后统一处理。

**同类问题**：「会话不属于该用户」目前也返回 409 而非 403，同样登记在 B-12。

### 9.5 完整链路的冒烟测试（第 3 次修订新增）

`curl` 逐条手敲在接口只有两个时还可行，到 7 个接口、需要「开练习 → 划 3 次词 → 答 2 题 →
交卷 → 看结果」，手工已经不可复现。因此固化成脚本：

```
powershell -ExecutionPolicy Bypass -File tools\api-smoke.ps1
```

它覆盖两条链路、**53 项断言全部通过**（实测 2026-09-27）：

| 分组 | 覆盖内容 |
|---|---|
| 开始练习 | 201 / 题目数与顺序 / **响应里不含 `isCorrect`·`analysis`·`referenceAnswer`·`translation`** |
| 划词标记 | 归一化（`running,`→`run`）/ 释义 / `alreadyMarked` 三态 / 锚点字段 / 非法 `sourceField` → 400 |
| 作答 | 阅读题判分 / 非法选项 → 400 / 结果页在交卷前 → 409 |
| 交卷 | 统计重算 / 重复交卷 → 409 / 越权交卷被拒 |
| 结果页 | 去重标记词与释义 / `markCount` 累计 / 判分与 `correctAnswer` 自洽 / 单题标记带锚点 |
| 翻译题 | 独立组卷 / `PENDING` / 交卷后才有 `referenceAnswer` / `correctAnswer` 为 `null` |
| 安全 | 别人的会话看结果 → 409（B-12）/ 不带身份头 → 401 / 不存在的会话 → 400 |

**两个写脚本时踩到的 PowerShell 5.1 坑**（与业务无关，但会让测试结果假阴性）：

1. **脚本必须存成 UTF-8 with BOM**。无 BOM 时 PS 5.1 按 GBK 读 `.ps1`，
   中文字符串里的引号被吃掉，报 `Missing ')' in function parameter list`。
2. **`(单个 PSCustomObject).Count` 返回空，不是 1**。
   `ConvertFrom-Json` 出来的对象经过 `Where-Object` 只匹配到一条时，
   直接取 `.Count` 得到 `$null`，断言会莫名其妙失败。必须写 `@(...).Count`。

### 9.6 ✅ 修掉一个真实缺陷：标记偏移量没有锚点（第 3 次修订）

**现象**：`GET /api/practices/{id}/marks` 返回的每条标记只有
`surfaceForm / normalizedForm / wordId / sentence / charStart / charEnd`，
**没有 `questionId`，也没有 `sourceField`**。

**为什么这是缺陷而不是「少个可选字段」**：偏移量本身不自带坐标系。
前端拿到 `charStart=5`，无从判断这是「第 1 题题干的第 5 个字符」还是「文章正文的第 5 个字符」，
于是无法把高亮画回正确的文本上 —— 而「在原文上回显标记」正是这个接口存在的唯一理由。
数据库里 `question_id` / `source_field` 是有的，只是没被响应 DTO 带出来。

**修复**：`MarkedWordResponse` 增加 `questionId` 与 `sourceField` 两个字段。
Smoke 测试新增 3 项断言（题干标记带 `questionId`、带 `sourceField`、正文标记的 `questionId` 为 `null`）。

**同时澄清另一个容易误判的点**：结果页**顶层**的 `data.markedWords` 是
`MarkedWordSummaryResponse`（按原形去重），它**刻意不带**锚点 ——
同一个词可能在多处被标，锚点不唯一。带锚点的是 `data.answers[].markedWords`。
写 smoke 断言时我一开始就在这上面判断错了，已修正断言并在 5.3 节写明两者区别。

**顺带纠正的两处注释错误**（代码行为本身没错，是注释把机制说反了）：

- `WordMarkService.summarizeSessionMarks` 原注释写「保持**首次标记**的顺序」，
  实际底层是按 `char_start` 升序，即**阅读顺序**，与用户点击先后无关
- `MarkedWordSummary.firstSentence` 原注释写「**第一次标记**时的句子」，
  实际是「文本中**最靠前**的那次标记的句子」

### 9.7 自检类被测试数据污染的问题（第 3 次修订）

**现象**：smoke 测试最初复用演示用户 `demo(id=9)`，跑完之后再重启应用，
两个原本通过的自检项变成 ❌：

```
❌ 生词本首次写入 mark_count=1  mark_count=3
❌ 错题本可查到本次的两道错题  查到 4 条
```

**根因**：这两项断言的是**全局绝对值**（该用户 `run` 的 `mark_count` 必须是 1、
该用户的错题必须正好 2 条），隐含假设「除自己以外没有别人写这个用户的数据」。
这个假设只在数据库纯净时成立。

**修复（两侧都改）**：

1. **自检类改为断言自己造成的增量**
   - `WordMarkVerifier`：先读 `run` 的基线计数，断言 `基线 + 1` / `基线 + 2`
   - `AnswerVerifier`：把 `listWrongByUser` 的结果按 `sessionId` 过滤，只看本次会话产生的错题
2. **smoke 测试改用自己创建的用户**
   - 开头 `INSERT` 两个测试用户（主用户 + 越权对照），结束时按
     `practice_session → user_word_mark → user_vocabulary → sys_user` 的顺序删干净
   - ⚠️ 顺序不能反：`fk_sess_user` / `fk_mark_user` / `fk_vocab_user` 都是
     **不带 `ON DELETE CASCADE`** 的普通外键，直接删 `sys_user` 会被子表挡住
   - 脚本最后一项断言就是「残留会话 / 标记 / 用户均为 0」

这个坑对将来加自检类的人同样适用，已同步记进根目录 `AGENTS.md`。

---

## 十、分步实现顺序

| 步 | 内容 | 为什么这个顺序 |
|---|---|---|
| 1 | **验证 springdoc 3.x 能否在 Boot 4.1.1 下启动** | 有兼容性风险，先探明；跑不通就不加，避免阻塞 |
| 2 | 统一响应体 `ApiResponse<T>` + `@RestControllerAdvice` 异常映射 | 所有接口都依赖它，先立规矩 |
| 3 | `CurrentUserProvider` 占位实现 | 所有接口的入参都依赖它 |
| 4 | API DTO（响应模型）+ 实体→DTO 转换器 | 第 5 步依赖它；并强制"不返回实体" |
| 5 | 开始练习两个接口（reading / translation） | 主流程入口 |
| 6 | 划词标记接口 | 本项目最核心的功能 |
| 7 | 提交答案 + 交卷接口 | |
| 8 | **结果页接口** | 核心需求的兑现；同时用到作答与标记两种数据 |
| 9 | 实测整条链路（`tools/api-smoke.ps1`） | **接口必须实测**，不能只看编译通过 |

**为什么把结果页排在查询类接口之前**：它是核心需求而非附属查询，
且它同时聚合作答记录与标记记录，跑通它就等于复核了前面所有写入。

**⚠️ 实测这一步不能省，也不能只测「正常路径」**。第 3 次修订就是靠实测才发现
`MarkedWordResponse` 少了锚点字段（见 9.6）—— 那个缺陷编译通过、Service 层自检也通过，
只有真的从 HTTP 取一次数据、并追问「前端拿这些字段能不能干活」才会暴露。

---

## 十一、未决事项与风险

> 🔖 **第 3 次修订更新**

| 项 | 说明 |
|---|---|
| **鉴权（B-07）** | 仍是临时占位。Session vs JWT 未定，但 URL 形态已按有鉴权的样子设计，将来不必重写 |
| **翻译题评分流程** | 接口按**异步**设计（提交返回 `PENDING`，前端轮询/或交卷后触发）。LLM 未接入，`grading_status` 的 `GRADING` / `DONE` / `FAILED` 三态尚无产生者 |
| **错误码精度（B-12）** | 「资源不存在」与「越权」目前都是 409：不存在的文章、不属于自己的会话都落到 `IllegalStateException` → 409。语义上应分别是 404 与 403 |
| **查询类接口** | `GET /api/me/vocabulary`（生词本）、错题本、历史记录**尚未实现**。底层服务方法都已就绪（`WordMarkService.listVocabulary`、`AnswerService.listWrongByUser`），只差 Controller 与分页 |
| **分页** | 清单类接口一旦出现就必须定分页方案（MyBatis-Plus `Page` 插件），目前尚未引入 |
| **前端跨域** | 前端分离时需配 CORS，方案未定（允许哪些来源） |
| **`X-Debug-User-Id` 占位** | **临时且不安全**，接入鉴权前不得对外部署 |
| **开发期自检类与 smoke 脚本** | `config` 包下的自检类与 `tools/api-smoke.ps1` 都属开发工具，上线前需删除或加 `@Profile("dev")`（见 `docs/README.md` 第三节、backlog B-10） |

---

## 十二、相关文件索引

| 文件 | 作用 |
|---|---|
| `docs/service-layer.md` | Service 层设计（本层的直接依赖） |
| `docs/orm-layer.md` | ORM 层设计：SQL 书写规范、XML 现状 |
| `docs/backlog.md` | 待办清单（B-07 鉴权、B-05 翻译评分等） |
| `docs/README.md` | 文档索引与修订约定 |
| `tools/api-smoke.ps1` | 全链路接口冒烟测试（53 项断言） |
