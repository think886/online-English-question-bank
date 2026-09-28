package org.example.englishquestionbank.api;

import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.exception.UnauthenticatedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常映射 —— 把 Service 抛出的异常翻译成 HTTP 响应。
 *
 * <p><b>核心设计</b>：Service 层完全不知道 HTTP 的存在，照常抛
 * {@code IllegalArgumentException} / {@code IllegalStateException}；
 * 由本类集中决定它们对应哪个状态码与业务码。这样业务代码不必污染，
 * 而映射规则只在一个地方维护。
 *
 * <p><b>为什么不把异常消息原样返回</b>：业务异常的消息是我们自己写的、可以安全展示
 * （如「会话已结束，不能再作答」）；但未预期异常的消息可能包含 SQL、类名、堆栈，
 * 因此 {@link #handleUnexpected} 对外只给一句笼统提示，细节写进日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 未认证 → 401。 */
    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthenticated(UnauthenticatedException e) {
        log.debug("未认证请求：{}", e.getMessage());
        return build(ErrorCode.UNAUTHENTICATED, e.getMessage());
    }

    /** 入参非法 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidParam(IllegalArgumentException e) {
        log.debug("入参非法：{}", e.getMessage());
        return build(ErrorCode.INVALID_PARAM, e.getMessage());
    }

    /**
     * Bean Validation 校验失败 → 400。
     *
     * <p>把字段级错误拼成一句可读的提示，例如：
     * {@code count: 必须大于或等于 1}。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBeanValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describeFieldError)
                .collect(Collectors.joining("; "));
        log.debug("请求参数校验失败：{}", detail);
        return build(ErrorCode.INVALID_PARAM, detail.isEmpty() ? "请求参数不合法" : detail);
    }

    /** 状态冲突 → 409（会话已结束、重复交卷、题目不在会话中等）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IllegalStateException e) {
        log.debug("状态冲突：{}", e.getMessage());
        return build(ErrorCode.CONFLICT, e.getMessage());
    }

    /** 唯一键冲突 → 409（如用户名已存在）。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateKeyException e) {
        log.debug("唯一键冲突：{}", e.getMessage());
        // 不把底层 SQL 消息返回给前端 —— 它会暴露表名与列名
        return build(ErrorCode.DUPLICATE, "数据已存在，请勿重复提交");
    }

    /**
     * 兜底处理器 —— 但<b>先尊重异常自带的 HTTP 状态码</b>，只在真无状态码时才 500。
     *
     * <h2>这个缺陷是怎么被发现和修掉的（第 6 次修订）</h2>
     * 【纠正】此前本方法无条件返回 500，于是 Spring MVC 抛出的「请求有问题」类异常
     * 全被改成了 500。实测现象（2026-09-27）：
     * <pre>
     *   /no-such-page          → 500   （应为 404）
     *   /api/no-such-endpoint  → 500   （应为 404）
     *   /swagger-ui/           → 500   （应为 404）
     * </pre>
     * 后果：① 前端无法区分「我地址写错了」与「服务端挂了」；② 日志堆满假告警。
     *
     * <h2>⚠️ 为什么不能靠「列出具体异常类型」来修</h2>
     * <b>「Spring 的这类异常都继承某个共同父类」是个错误印象，实测被推翻。</b>
     * 用 {@code javap} 查 Spring Framework <b>7.0.9</b>（Boot 4.1.1 带的版本）：
     * <pre>
     *   NoResourceFoundException
     *     extends jakarta.servlet.ServletException
     *     implements org.springframework.web.ErrorResponse      ← 不继承 ErrorResponseException！
     *
     *   HttpRequestMethodNotSupportedException
     *     extends jakarta.servlet.ServletException
     *     implements org.springframework.web.ErrorResponse
     * </pre>
     * 也就是说这些异常<b>没有共同父类</b>，只是都实现了 {@link ErrorResponse} 接口。
     * 而 {@code @ExceptionHandler} 要求在注解里写具体异常类（方法参数类型必须是
     * {@code Throwable} 子类，写接口会在启动时报 {@code No exception type is specified}），
     * 于是「列清单」成了唯一出路 —— 但那样每遇到一个新的 Spring 异常就得改一次代码，
     * 天生脆弱。
     *
     * <p><b>所以改成在这里用 {@code instanceof ErrorResponse} 判断</b>：
     * 网络层只要一个 {@code Exception} 处理器，任何自带状态码的异常都会被尊重，
     * <b>不需要维护任何清单</b>。实测：404 与 405 现在都正确返回。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleFallback(Exception e) {
        // ---- ① 异常自带 HTTP 状态码 → 用它自己的，不篡改成 500 ----
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            ErrorCode code = bodyCodeFor(status);

            if (status.is5xxServerError()) {
                // 5xx 仍然是服务端故障，堆栈要留全
                log.error("Spring 抛出的 {} 异常（状态码 {}）", e.getClass().getName(), status.value(), e);
            } else {
                // 4xx 是「调用方请求有问题」，不是服务端故障 —— 用 debug，别污染告警
                log.debug("Spring MVC 已带状态码的异常：{} → {} {}",
                        e.getClass().getSimpleName(), status.value(), code);
            }

            return ResponseEntity.status(status)
                    .body(ApiResponse.fail(code, messageFor(status)));
        }

        // ---- ② 真正未预期的异常 → 500 ----
        // 日志里记完整堆栈，响应里只给一句笼统提示：
        // 未预期异常的消息可能包含 SQL 片段、类名、内部路径，不应泄漏给调用方。
        log.error("未预期的服务端异常", e);
        return build(ErrorCode.INTERNAL_ERROR, "服务器内部错误，请稍后重试");
    }

    // =====================================================================

    /**
     * HTTP 状态码 → 业务码。
     *
     * <p>业务码比状态码粗一档：状态码是<strong>精确</strong>的（405 就是 405），
     * 业务码是给前端做分支用的分组。遇到没有专属业务码的 4xx，统一归到
     * {@link ErrorCode#INVALID_PARAM}（语义是「你的请求有问题」）。
     */
    private static ErrorCode bodyCodeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.INVALID_PARAM;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404, 410 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.CONFLICT;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_PARAM;
        };
    }

    /**
     * 给调用方看的中文提示。
     *
     * <p><b>刻意不用 Spring 异常自带的消息</b>：那是英文的，而且对
     * {@code NoResourceFoundException} 会拼出 {@code No static resource for request '/xxx'.}
     * 这类内部细节。这里按状态码给一句稳定、可展示的话。
     */
    private static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404, 410 -> "请求的资源不存在，请检查地址是否正确";
            case 405 -> "请求方法不被支持，请检查 HTTP 方法（GET/POST…）";
            case 415 -> "请求的 Content-Type 不被支持，请使用 application/json";
            case 400 -> "请求不合法";
            case 401 -> "未认证";
            case 403 -> "无权访问";
            case 409 -> "请求与当前状态冲突";
            default -> status.is5xxServerError() ? "服务器内部错误，请稍后重试" : "请求不合法";
        };
    }

    private String describeFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code, message == null || message.isBlank() ? code.name() : message));
    }
}
