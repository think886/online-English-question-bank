package org.example.englishquestionbank.api;

/**
 * 统一响应体。
 *
 * <p>成功与失败**同构** —— 前端始终按同一个结构解析，不必为错误单独写一套。
 *
 * <pre>{@code
 * // 成功
 * {"code": "OK", "message": "success", "data": { ... }}
 *
 * // 失败（HTTP 状态码同时反映语义，如 409）
 * {"code": "CONFLICT", "message": "会话已结束，不能再作答", "data": null}
 * }</pre>
 *
 * @param code    业务码，取自 {@link ErrorCode}；成功时为 {@code "OK"}
 * @param message 人类可读的说明；成功时为 {@code "success"}
 * @param data    业务数据；失败时为 {@code null}
 * @param <T>     业务数据类型
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.name(), "success", data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.name(), message, null);
    }
}
