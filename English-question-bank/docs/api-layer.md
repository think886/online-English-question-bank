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
| 第 5 次 | 2026-09-28 | **新增登录鉴权与查询接口**：接口 7 → **11 个**。新增 `POST /auth/login`、`POST /auth/logout`（**不透明令牌存库**，库里只存 SHA-256）、`GET /me/vocabulary`、`GET /me/wrong-questions`、`GET /practices`；**分页方案定为 P1（MyBatis-Plus `Page` 插件，offset 分页）**；重写第四节（认证不再是占位）；新增 5.4 / 5.5 节；`UnauthenticatedException` 从 `api` 包移到新 `exception` 包（修依赖方向倒置）；实测新增 15 项认证断言，冒烟测试 53 → **86 项** |
| 第 6 次 | 2026-09-28 | **修掉 B-15**：不存在的 URL 由 500 改回 404、动词用错 → 405、Content-Type 不对 → 415。**关键纠正**：原先按 Spring 6.x 的印象以为这些异常都继承 `ErrorResponseException`，用 `javap` 查 Spring **7.0.9** 发现 `NoResourceFoundException` 只 `implements ErrorResponse`，于是改成 `instanceof ErrorResponse` 统一尊重异常自带状态码（不再维护类型清单）；3.2 节异常映射表重写；新增 `ErrorCode.METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE`；冒烟测试 86 → **90 项** |

---

## 一、已确认的设计决策

| # | 决策 | 选择 | 理由摘要 |
|---|---|---|---|
| 1 | 响应体风格 | **HTTP 状态码表语义 + 响应体带业务码** | 兼顾前端处理与网关/监控可见性 |
| 2 | 鉴权 | **URL 按最终形态设计 + 临时占位** | 避免以后改鉴权时重写所有 URL |
| 3 | 本轮范围 | **核心链路 + 结果页** | 先把主流程打通，查询类接口次之 |
| 4 | 接口文档 | **引入 springdoc-openapi** | 可在浏览器直接试接口，调试成本低 |
| 5 | 令牌形态（第 5 次修订） | **不透明随机令牌存库**，不用 JWT | 本项目每请求本就要查库，JWT 的「免查库」无意义；而 JWT **不可撤销**（登出/改密/封号后仍有效）。不透明令牌可撤销、可审计，且**零新依赖** |
| 6 | 分页方案（第 5 次修订） | **P1 offset 分页**（MyBatis-Plus `Page` 插件） | 生词本/错题本是「个人几千条」量级，offset 够用；且诊断类列表**需要总数**，这正是 offset 相对 cursor 的优势。真到深翻页出问题再换 cursor 也只动一个 Mapper 方法 |
| 7 | 令牌是否落库明文（第 5 次修订） | **只存 SHA-256** | 令牌等价于密码。明文入库则一次拖库就等于所有在线用户被冒充。刻意不用 BCrypt —— 令牌是高熵随机串，不存在爆破风险，而每请求都要校验，BCrypt 会把每次请求拖慢几十毫秒 |

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

> 🔖 **第 6 次修订重写：补上 Spring MVC 异常与 405/415，并纠正「404 会变 500」的缺陷**

| 抛出者 | 异常 | HTTP | 业务码 | 说明 |
|---|---|---|---|---|
| Service | `IllegalArgumentException` | 400 | `INVALID_PARAM` | 入参非法 |
| Service | `UnauthenticatedException` | 401 | `UNAUTHENTICATED` | 令牌无效 / 缺失身份 |
| Service | `IllegalStateException` | 409 | `CONFLICT` | 状态冲突（会话已结束、重复交卷、越权） |
| Service | `DuplicateKeyException` | 409 | `DUPLICATE` | 唯一键冲突（用户名已存在） |
| **Spring MVC** | `MethodArgumentNotValidException` | 400 | `INVALID_PARAM` | Bean Validation 校验失败 |
| **Spring MVC** | `NoResourceFoundException` | **404** | `NOT_FOUND` | **未知 URL / 静态资源缺失**（第 6 次修订前是 500） |
| **Spring MVC** | `HttpRequestMethodNotSupportedException` | **405** | `METHOD_NOT_ALLOWED` | 动词用错（如 GET 打 POST 接口） |
| **Spring MVC** | `HttpMediaTypeNotSupportedException` | **415** | `UNSUPPORTED_MEDIA_TYPE` | 漏写 `Content-Type: application/json` |
| 其他 | 未预期异常 | 500 | `INTERNAL_ERROR` | **不把堆栈返回给前端**，只记日志 |

**这张表怎么落地的**（第 6 次修订的关键设计）：

前 6 行各有专属的 `@ExceptionHandler`。**第 6~8 行不写专属处理器**，而是由
`handleFallback` 里的一句 `instanceof ErrorResponse` 统一兜住：

```java
if (e instanceof ErrorResponse errorResponse) {          // Spring 自带的异常都实现了它
    HttpStatusCode status = errorResponse.getStatusCode(); // 尊重异常自己的状态码
    ...
}
```

**为什么用 `instanceof` 而不是「列出异常类型」**：这些 Spring 异常**没有共同父类**。
用 `javap` 查 Spring Framework **7.0.9**（Boot 4.1.1 携带的版本）实测：

```
NoResourceFoundException
  extends jakarta.servlet.ServletException
  implements org.springframework.web.ErrorResponse      ← 不继承 ErrorResponseException！

HttpRequestMethodNotSupportedException
  extends jakarta.servlet.ServletException
  implements org.springframework.web.ErrorResponse
```

【纠正】我最初按 Spring 6.x 的印象以为它们都继承 `ErrorResponseException`，
只列了那一个类，结果 405 修好了、**404 仍然 500** —— 因为
`NoResourceFoundException` 在 Spring 7 里根本不继承它。
改成 `instanceof ErrorResponse` 后，**任何自带状态码的异常都被自动尊重，不需要维护清单**。

> **为什么保留 Service 现有的异常类型**：它们是「入参错误 vs 状态错误」的天然区分，
> 正好对应 400 与 409。若为此新建一套异常体系，Service 层就会被迫感知 HTTP 语义，
> 反而耦合。
>
> **`@ExceptionHandler` 不能直接接 `ErrorResponse` 接口**：方法参数类型必须是
> `Throwable` 的子类（Spring 靠它推断要接哪个异常），写接口会在启动时报
> `No exception type is specified`。所以判断只能放在方法体里。

---

## 四、认证上下文

> 🔖 **第 5 次修订修改：本节原为「认证上下文占位」，现已换成真实的令牌鉴权**

### 4.1 URL 按最终形态设计（未变）

```
✅ /api/auth/login               换取令牌（自身不需要认证）
✅ /api/me/vocabulary            用户自己的数据
✅ /api/practices                我的练习历史
✅ /api/practices/{sessionId}    资源本身带 id，但归属由上下文校验
❌ /api/users/{userId}/vocabulary
❌ /api/practices?userId=123
```

### 4.2 身份解析链（两条，令牌优先）

```java
@Component
public class CurrentUserProvider {
    public Long currentUserId();      // Controller 只依赖这一个方法
    public String currentRawToken();  // 仅登出接口需要（撤销「哪一个」令牌）
}
```

解析顺序：

| 顺序 | 来源 | 说明 |
|---|---|---|
| 1 | `Authorization: Bearer <token>` | 真实鉴权。查 `user_token` 表校验「存在 + 未撤销 + 未过期」 |
| 2 | `X-Debug-User-Id: <userId>` | **开发期回退，临时且不安全** |

Controller 依然只依赖这个抽象，**不知道**身份从哪来 —— 这正是第 1 次修订留下这个接口的价值：
本次从「请求头」换成「令牌」时，**7 个既有接口的 Controller、URL、DTO 一行都没改**。

### 4.3 为什么这个设计经得起换实现

第 1 次修订时的判断是「URL 按有鉴权的样子设计，实现先占位，以后换实现不必重写接口」。
本次验证了这个判断成立：新增令牌鉴权只做了一件事 ——
**在 `CurrentUserProvider` 里加一个分支**，其余全部复用。

同一次改动还顺手修了一处**依赖方向倒置**：`UnauthenticatedException` 原本放在 `api` 包，
而 `AuthService`（Service 层）也需要抛它，于是 `service` 要 import `api`。
已挪到新的 `exception` 包 —— 它本身不携带 HTTP 语义（映射成 401 是
`GlobalExceptionHandler` 的职责），本来就不该住在 Web 层。

### 4.4 令牌的技术细节

| 项 | 取值 | 为什么 |
|---|---|---|
| 随机源 | `SecureRandom`，32 字节 | **不能用 `java.util.Random`** —— 它是线性同余发生器，观察到若干输出就能推算后续序列 |
| 编码 | Base64 **URL 变体**，无填充 → 43 字符 | 标准 Base64 的 `+ / =` 在 URL、Cookie、日志里会被转义或截断 |
| 库中存储 | **SHA-256 十六进制**（64 字符） | 令牌等价于密码；明文入库则一次拖库即所有在线用户被冒充 |
| 有效期 | 7 天 | 开发期折中 |
| 撤销 | `revoked_at` 置当前时间 | 登出、改密码、封号都能立刻失效 |
| `last_used_at` | **节流更新**（距上次记录超过 5 分钟才写） | 不节流的话，每个 API 请求都会多一次 UPDATE —— 让「读」放大成「写」得不偿失 |
| 防账号枚举 | 用户不存在时**照样做一次等量 BCrypt 计算**再失败 | 否则「用户名不存在」响应明显更快，可用来枚举有效账号（时序攻击） |
| 状态检查顺序 | 先校验密码，**再**校验 `status` | 反过来的话，攻击者无需正确密码就能靠「账号已被禁用」判断该用户名存在 |

### 4.5 ⚠️ 仍然遗留的不安全项

`X-Debug-User-Id` 回退**必须在上线前删除** —— 它仍然是「谁都能自称是任意用户」。
保留它只是为了不破坏 `tools/api-smoke.ps1` 的 86 项断言与手工调试的便利。
删掉它时要同步改冒烟脚本。见 `docs/backlog.md` **B-07**。

演示账号 `demo` / `demo123` 是**写在源码里的弱口令**（`SampleDataInitializer.DEMO_PASSWORD`），
对外部署前必须删除该用户或强制改密。同样登记在 B-07。

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

### 5.4 认证（第 5 次修订新增）

#### `POST /api/auth/login`

请求：`{"username": "demo", "password": "demo123"}`

响应 `200`：

```json
{
  "code": "OK",
  "data": {
    "token": "3q2-7yXb...（43 字符）",
    "expiresAt": "2026-10-05T20:05:45",
    "expiresInSeconds": 604800,
    "user": { "id": 9, "username": "demo", "nickname": "演示用户", "role": "USER" }
  }
}
```

> **为什么用 `POST` 而不是 `GET`**：密码不能出现在 URL 里（会进浏览器历史、代理日志、Referer 头）。
> 这是硬性要求，不是风格偏好。
>
> **同时给绝对时间与相对秒数**：`expiresAt` 便于显示「有效期至 …」；
> `expiresInSeconds` 便于做倒计时，且**不受客户端与服务器时钟偏差影响**，前端应优先用它。
>
> **用户名不存在与密码错误返回同一句 `用户名或密码错误`** —— 分开提示等于免费告诉攻击者
> 哪些用户名已注册。真实原因记在服务端 debug 日志里。
>
> 响应里的 `user` **不含 `passwordHash`**（红线 1）。

#### `POST /api/auth/logout`

请求：带 `Authorization: Bearer <token>`，无请求体。

响应 `200`：`{"code":"OK","message":"success","data":null}`

> **幂等**：令牌不存在、已撤销、或压根没带，都返回成功。登出失败没有重试的意义，
> 报错只会让前端多写一段无用的错误处理。

### 5.5 查询接口与分页（第 5 次修订新增）

三个清单接口共用同一套分页约定与响应外壳。

#### 分页约定（P1 offset 分页）

| 项 | 说明 |
|---|---|
| 请求参数 | `?page=1&size=20` |
| 归一化 | `page < 1` → 1；`size < 1` → 20；`size > 100` → 100。**非法值被静默修正，不报错** |
| 响应 `size` | **以它为准**，可能小于你请求的值（被上限截断） |
| `hasNext` | 直接用它判断「有没有下一页」，不必自己算 `page < pages`（边界容易算错） |
| 页码超范围 | 返回**空列表**，不是绕回第一页（`overflow=false`） |

> **⚠️ 为什么必须归一化 `size`**：MyBatis-Plus 的 `Page` 在 `size <= 0` 时
> **不做分页、直接返回全表**，而且不报错。`?size=0` 就能把整张表拉出来。
> 归一化集中在 `support.Paging`，任何接口都绕不过去。
>
> **为什么 offset 而不是 cursor**：生词本/错题本是「个人几千条」量级，offset 完全够用；
> 而且诊断类列表**需要显示总数**（「我一共积累了多少个词」），这正是 offset 相对 cursor 的优势。
> 若将来深翻页真的成为瓶颈，换 cursor 只影响一个 Mapper 方法。

#### `GET /api/me/vocabulary` —— 我的生词本

```json
{
  "code": "OK",
  "data": {
    "records": [
      { "normalizedForm": "abandon", "displayForm": "abandon",
        "translation": "a. 被抛弃的, 无约束的", "phoneticUk": "ә'bændәn",
        "phoneticUs": null, "partOfSpeech": "a.", "tag": "cet4 cet6 ky",
        "markCount": 1, "mastery": 0,
        "firstMarkedAt": "...", "lastMarkedAt": "..." }
    ],
    "total": 3, "page": 1, "size": 20, "pages": 1, "hasNext": false
  }
}
```

> 按 `last_marked_at` 倒序 —— 用户最近标的词排最前，这是他打开生词本最想看的东西。
>
> 用 `LEFT JOIN word`：`user_vocabulary.word_id` 可能为 `NULL`（划词时词典未收录），
> 此时仍要返回这一行（只是 `translation` 为 `null`），**不能因为 JOIN 不上就把生词丢掉**。

#### `GET /api/me/wrong-questions` —— 我的错题本

```json
{
  "code": "OK",
  "data": {
    "records": [
      { "answerRecordId": 42, "sessionId": 61, "questionId": 1,
        "questionType": "READING", "stem": "What is the main idea...",
        "sourceText": null, "userAnswer": "D",
        "correctOptionKey": "B", "referenceAnswer": null, "analysis": null,
        "passageId": 1, "passageTitle": "Community Gardens",
        "answeredAt": "..." }
    ],
    "total": 3, "page": 1, "size": 20, "pages": 1, "hasNext": false
  }
}
```

> 🔒 **本接口只返回 `FINISHED` 会话里的错题。** 这不是「顺手加的」条件 ——
> 去掉它，练习进行中就能通过这个接口拿到正确答案与解析，等于绕开结果页提前看答案，
> 直接违反红线 2。过滤条件写在 `AnswerRecordMapper.xml` 的 SQL 里，
> **不放在 Service 或 Controller**，这样任何调用路径都绕不过。
> 冒烟测试用「未交卷时总数不变、交卷后 +1」的前后差值专门验证了这条。
>
> **只取 `is_correct = 0`**，刻意不写 `is_correct != 1`：翻译题在评分完成前
> `is_correct` 是 `NULL`，「还没判分」和「做错了」是两回事。
>
> 这里出现正确答案是**安全的**（只含已交卷会话），详见 5.3 节红线 2 的说明。

#### `GET /api/practices` —— 我的练习历史

```json
{
  "code": "OK",
  "data": {
    "records": [
      { "sessionId": 61, "mode": "READING", "status": "FINISHED",
        "passageTitle": "Community Gardens",
        "totalCount": 2, "answeredCount": 2, "correctCount": 0,
        "score": 0, "maxScore": 2, "markedWordCount": 2,
        "startedAt": "...", "finishedAt": "...", "durationMs": 1234 }
    ],
    "total": 3, "page": 1, "size": 20, "pages": 1, "hasNext": false
  }
}
```

> **包含未交卷的会话**（`IN_PROGRESS`）。这是刻意的：用户中途关掉浏览器是常态，
> 历史列表里应能看到它并「继续做」，而不是让它凭空消失。
> 前端凭 `status` 决定显示「继续」还是「查看结果」。
>
> **必须用 `LEFT JOIN passage`**：翻译题练习的 `passage_id` 是 `NULL`，
> 写成 `INNER JOIN` 会让翻译练习在历史里**整条消失** —— 而且不报错，只是「记录没了」，
> 属于最难发现的一类 bug。冒烟测试专门断言了「翻译会话仍在列表里」。
>
> **排序键是 `started_at DESC, id DESC`**：只按时间排序在同秒创建多条会话时顺序不确定，
> 加 `id` 作第二排序键才能保证分页时同一条记录不会在第 1 页和第 2 页各出现一次 ——
> 这是 offset 分页的经典坑。

---

## 六、DTO 分层

> 🔖 **第 5 次修订修改：补齐认证与查询接口的 DTO**

```
api/dto/
├── request/    请求体（自带 Bean Validation 注解）
│   ├── LoginRequest              username / password
│   ├── StartReadingRequest       passageId
│   ├── StartTranslationRequest   count
│   ├── MarkWordRequest           questionId? / sourceField / surfaceForm
│   │                             / sentence? / charStart / charEnd
│   └── SubmitAnswerRequest       questionId / userAnswer
└── response/   响应体（只含可以给前端的字段）
    ├── ApiResponse<T>            统一外壳（注意：在 api/ 包下，不在 response/ 下）
    ├── PageResponse<T>           分页外壳（records/total/page/size/pages/hasNext）
    ├── UserResponse              用户信息（**刻意没有 passwordHash**）
    ├── LoginResponse             token / expiresAt / expiresInSeconds / user
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
    ├── PracticeResultResponse    结果页整体
    ├── VocabItemResponse         生词本条目
    ├── WrongQuestionResponse     错题本条目（仅已交卷会话）
    └── PracticeHistoryResponse   练习历史条目
```

**Service 层的 DTO 也分了三类**（第 5 次修订）：

| 类型 | 风格 | 在哪 | 为什么 |
|---|---|---|---|
| 入参 / 出参 | `record` | `dto/` | 值语义，不可变，简洁 |
| 分页结果 | `record` | `dto/PageResult<T>` | **只用 JDK 类型** —— 不把 MyBatis-Plus 的 `IPage` 泄漏到 API 层 |
| **行映射类型** | **`@Data` 类** | `dto/query/` | MyBatis 靠 **setter** 注入列值，record 没有 setter。用 record 就得按构造器参数顺序映射，SQL 列顺序一变就**静默错位**。放独立子包让这个区别在结构上就看得见 |

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

> 🔖 **第 2 次修订新增；第 5 次修订更新（新增第 10~14 步）**

| 步 | 内容 | 状态 |
|---|---|---|
| 1 | 验证 springdoc 3.x 能在 Boot 4.1.1 启动 | ✅ 完成，采用 3.1.1 |
| 2 | 统一响应体 + 全局异常映射 | ✅ 完成 |
| 3 | `CurrentUserProvider` 占位 | ✅ 完成 |
| 4 | API DTO（响应模型 + 转换） | ✅ 完成 |
| 5 | 开始练习两个接口（reading / translation） | ✅ 完成 |
| 6 | 划词标记接口（POST + GET） | ✅ 完成 |
| 7 | 提交答案 + 交卷接口 | ✅ 完成 |
| 8 | 结果页接口 | ✅ 完成 |
| 9 | 全链路实测（53 项） | ✅ 完成 |
| 10 | **T0：OpenAPI 安全方案，让 Swagger 出现 Authorize 按钮** | ✅ 完成（第 5 次修订） |
| 11 | **`user_token` 表 + `AuthService`（BCrypt + 不透明令牌）** | ✅ 完成 |
| 12 | **`POST /auth/login`、`POST /auth/logout` + `CurrentUserProvider` 升级** | ✅ 完成 |
| 13 | **接 MyBatis-Plus `Page` 插件（P1 分页）** | ✅ 完成 |
| 14 | **三个查询接口（生词本 / 错题本 / 历史）** | ✅ 完成 |
| 15 | 全链路实测（86 项，含第 10~14 步） | ✅ 完成 |

### 已实现文件

```
api/
├── ErrorCode.java                业务码 + 绑定的 HTTP 状态码
├── ApiResponse.java              统一响应体（成功/失败同构）
├── GlobalExceptionHandler.java   Service 异常 → HTTP 的集中映射
├── CurrentUserProvider.java      身份解析（**令牌优先，X-Debug-User-Id 回退**）
├── controller/
│   ├── AuthController.java       登录 / 登出
│   ├── PracticeController.java   开始阅读/翻译、练习历史、提交答案、交卷、结果页
│   ├── WordMarkController.java   划词标记（POST + GET，挂在 /api/practices/{id}/marks）
│   └── MeController.java         生词本 / 错题本
└── dto/                          见第六节

exception/
└── UnauthenticatedException.java 第 5 次修订从 api 包移来（修依赖方向倒置）

config/
├── OpenApiConfig.java            OpenAPI 安全方案（永久配置）
└── MybatisPlusConfig.java        分页插件（永久配置）
```

> ⚠️ `config` 包此前只有开发期自检类；**第 5 次修订起它里面多了两个永久配置类**
> （`OpenApiConfig`、`MybatisPlusConfig`）。`docs/README.md` 第三节的临时类清单已注明这一点，
> 清理自检类时**不要连它们一起删掉**。

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

### 9.8 登录鉴权实测（第 5 次修订新增）

实测日期 **2026-09-28**，独立 15 项断言全通过：

| 场景 | 期望 | 实测 |
|---|---|---|
| Swagger 安全方案 | 两个 scheme | `schemes = X-Debug-User-Id, bearerAuth` ✅ |
| `demo` / `demo123` 登录 | 200 + 令牌 | 200，token 43 字符 ✅ |
| 登录响应不含密码哈希 | 无 `passwordHash` / `$2a$` | 确认不含 ✅ |
| 密码错误 | 401 | 401 `用户名或密码错误` ✅ |
| **用户名不存在** | 401，**提示与密码错误完全一致** | 401，消息逐字相同 ✅ |
| Bearer 令牌访问受保护接口 | 不再是 401 | 身份通过 ✅ |
| 伪造令牌 | 401 | 401 `登录令牌无效、已过期或已登出` ✅ |
| `X-Debug-User-Id` 回退 | 仍可用 | 仍可用 ✅ |
| 完全不带身份 | 401 | 401 ✅ |
| **明文令牌是否在库里** | 不在 | `明文命中 = 0` ✅ |
| **库里存的是否为 SHA-256** | 是 | 用 MySQL 的 `SHA2(token,256)` **独立计算**比对，命中 ✅ |
| 登出 | 200 | 200 ✅ |
| 登出后同一令牌 | 401 | 401 ✅ |
| 重复登出 | 200（幂等） | 200 ✅ |
| 令牌长度 | 64 字符十六进制 | 64 ✅ |

> 其中「用 MySQL 的 `SHA2()` 独立算一遍再比对」这一条是有意设计的：
> 它验证的不是「我们存了点什么」，而是**Java 侧的 SHA-256 十六进制实现与标准算法一致**。
> 如果 Java 代码里少写了补零（`0x0A` 输出成 `a` 而不是 `0a`），这条断言就会失败。

### 9.9 查询接口与分页实测（第 5 次修订新增）

`tools/api-smoke.ps1` 第三部分共 **30 项断言**全通过。几条值得单独说的：

| 断言 | 为什么重要 |
|---|---|
| **「未交卷的错题不进错题本」** | 用**真实数据的前后差值**验证（先记下 total，新开会话答错、不交卷，再查 total **不变**；交卷后 total **+1**）。这是红线 2 的直接验证，只靠代码审阅不算数 |
| **翻译会话仍在历史列表里** | 验证 `LEFT JOIN` 没写错。写成 `INNER JOIN` 会让翻译练习整条消失，且**不报错** |
| `size=0` → 实际生效 20 | 验证 `Paging` 挡住了 MyBatis-Plus「`size<=0` 不做分页、返回全表」的坑 |
| `size=99999` → 实际生效 100 | 验证插件单页上限生效 |
| `page=0` → 实际生效 1 | 验证页码归一化 |
| 页码超范围 → 空列表 | 验证 `overflow=false`（不是绕回第一页） |
| `hasNext` 与 `pages` 自洽 | 第一页 true、最后一页 false |
| 另一个用户的生词本为空 | 验证身份隔离，数据不串 |
| 错题本按作答时间倒序 | 最新做错的那条排第一（用 `userAnswer = 'D'` 识别） |

**踩到的两个自身错误**（都是测试脚本的问题，不是产品代码）：

1. 断言 `records.PSObject.Properties.Name -contains 'phoneticUk'` —— `records` 是**数组**，
   数组的属性列表里没有业务字段。必须先取 `records[0]` 再问它有哪些属性。
2. 断言详情里用 `(... | Where-Object {...}).Count` 打印条数，单条时输出**空值**
   （PS 5.1 的 `.Count` 坑，见 9.5）。改用 `Count-Of` 包装。

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

> 🔖 **第 5 次修订更新**

| 项 | 说明 |
|---|---|
| **注册 / 改密码接口缺失（B-17）** | 登录有了，但新用户无法自助注册；也没有改密码接口。后者还牵出一个安全问题：改密码必须**同时撤销全部既有令牌**，属跨表操作 |
| **`X-Debug-User-Id` 回退（B-07）** | **仍然临时且不安全**，上线前必须删除（会连带改 86 项冒烟断言）。演示账号 `demo/demo123` 是写在源码里的弱口令，同样必须处理 |
| **令牌表缺清理（B-18）** | 过期令牌行永久留在 `user_token`；同一用户可无限登录，没有并发登录上限 |
| **错误码精度（B-12）** | 「资源不存在」与「越权」仍都是 409（不存在的文章、不属于自己的会话）。语义上应分别是 404 与 403 |
| **404 变 500（B-15）** | 任何不存在的 URL 都返回 500 而非 404 —— `NoResourceFoundException` 被 `Exception` 兜底吞掉。**本次未修** |
| **翻译题评分流程** | 接口按**异步**设计（提交返回 `PENDING`）。LLM 未接入，`grading_status` 的 `GRADING` / `DONE` / `FAILED` 三态尚无产生者。注意错题本**只取 `is_correct = 0`**，所以待评分的翻译题不会误入 |
| **前端跨域** | 前端分离时需配 CORS，方案未定（允许哪些来源） |
| **开发期自检类与 smoke 脚本（B-10）** | `config` 包下的自检类与 `tools/api-smoke.ps1` 属开发工具，上线前需删除或加 `@Profile("dev")`。⚠️ 但 `config/OpenApiConfig.java` 与 `config/MybatisPlusConfig.java` 是**永久配置**，别一起删 |
| **令牌放在哪（前端）** | 后端只负责签发。前端该放 HttpOnly Cookie 还是内存，**尚未讨论** —— 放 localStorage 会扩大 XSS 的影响面 |

---

## 十二、相关文件索引

| 文件 | 作用 |
|---|---|
| `docs/service-layer.md` | Service 层设计（本层的直接依赖） |
| `docs/orm-layer.md` | ORM 层设计：SQL 书写规范、XML 现状 |
| `docs/backlog.md` | 待办清单（B-07 鉴权收尾、B-17 注册、B-05 翻译评分等） |
| `docs/README.md` | 文档索引与修订约定 |
| `docs/idea-classpath-troubleshooting.md` | IDEA 报「程序包不存在」的排查手册 |
| `tools/api-smoke.ps1` | 全链路接口冒烟测试（**86 项**断言，覆盖三条链路） |
