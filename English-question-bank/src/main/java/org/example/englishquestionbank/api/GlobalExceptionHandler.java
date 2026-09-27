package org.example.englishquestionbank.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
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
     * 兜底 → 500。
     *
     * <p><b>日志里记完整堆栈，响应里只给一句笼统提示</b> ——
     * 未预期异常的消息可能包含 SQL 片段、类名、内部路径，不应泄漏给调用方。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未预期的服务端异常", e);
        return build(ErrorCode.INTERNAL_ERROR, "服务器内部错误，请稍后重试");
    }

    // =====================================================================

    private String describeFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code, message == null || message.isBlank() ? code.name() : message));
    }
}
