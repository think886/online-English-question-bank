package org.example.englishquestionbank.api;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 *
 * <p><b>与 HTTP 状态码的关系</b>：每个错误码绑定一个 HTTP 状态码，
 * 响应里两者同时给出 ——
 * <ul>
 *   <li>HTTP 状态码：让网关、监控、浏览器 DevTools 一眼看出请求成败</li>
 *   <li>业务码：让前端能用 {@code switch} 精确判断该提示什么、该跳转哪里</li>
 * </ul>
 * 只给业务码会让所有响应都是 200，只给状态码则无法区分「会话已结束」与「题目不在会话中」
 * 这类需要不同前端处理的情况。
 *
 * <p><b>为什么绑定 HttpStatus 而不是一个自定义数字</b>：直接用 Spring 的枚举，
 * 不引入需要维护的编号体系；且本枚举只被 Web 层使用，没有把 HTTP 语义泄漏进 Service 层。
 */
public enum ErrorCode {

    /** 成功。 */
    OK(HttpStatus.OK),

    /** 入参非法 —— 字段缺失、格式错误、取值越界。 */
    INVALID_PARAM(HttpStatus.BAD_REQUEST),

    /** 未认证 —— 缺少或无法解析身份信息。 */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    /** 已认证但无权访问 —— 数据不属于当前用户。 */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** 资源不存在。 */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 请求方法不被支持 —— 例如对只接受 POST 的接口发了 GET。 */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

    /** 请求的 Content-Type 不被支持 —— 例如漏写 {@code application/json}。 */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    /** 状态冲突 —— 如会话已结束、重复交卷。 */
    CONFLICT(HttpStatus.CONFLICT),

    /** 唯一键冲突 —— 如用户名已存在。 */
    DUPLICATE(HttpStatus.CONFLICT),

    /** 未预期的服务端错误。对外只给一句笼统提示，细节进日志。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
