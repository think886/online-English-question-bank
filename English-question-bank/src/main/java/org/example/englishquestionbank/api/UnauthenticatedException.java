package org.example.englishquestionbank.api;

/**
 * 未认证异常 —— 缺少或无法解析身份信息。
 *
 * <p><b>为什么要单独一个异常类</b>：本项目<b>刻意不为业务错误新建异常体系</b> ——
 * Service 现有抛的 {@code IllegalArgumentException}（入参错）与
 * {@code IllegalStateException}（状态错）已经足够表达业务语义，且正好对应 400 与 409。
 *
 * <p>但「未认证」不属于业务语义，它是 Web 层的关注点（Service 根本不知道 HTTP 存在），
 * 且必须映射成 401 而不是 409。因此只有这一个例外。
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
