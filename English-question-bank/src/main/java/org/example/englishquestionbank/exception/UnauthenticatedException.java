package org.example.englishquestionbank.exception;

/**
 * 未认证异常 —— 缺少、无法解析、已失效的身份信息。
 *
 * <p><b>为什么要单独一个异常类</b>：本项目<b>刻意不为业务错误新建异常体系</b> ——
 * Service 现有抛的 {@code IllegalArgumentException}（入参错）与
 * {@code IllegalStateException}（状态错）已经足够表达业务语义，且正好对应 400 与 409。
 *
 * <p>但「未认证」不属于业务语义，它必须映射成 401 而不是 409，因此只有这一个例外。
 *
 * <h2>为什么放在 {@code exception} 包，而不是 {@code api} 包（第 N 次修订调整）</h2>
 * 【纠正】这个类原本位于 {@code api} 包。引入登录之后，{@code AuthService}（Service 层）
 * 也需要在「用户名或密码错误」时抛出它 —— 于是 {@code service} 包要 import {@code api} 包，
 * 形成<b>依赖方向倒置</b>（正常方向是 api → service）。
 * 本类本身不携带任何 HTTP 语义（映射成 401 是 {@code GlobalExceptionHandler} 的职责），
 * 所以它本来就不该住在 Web 层。挪到独立的 {@code exception} 包后，
 * api 与 service 都可以依赖它，方向恢复正确。
 *
 * <p>将来的业务异常体系（见 {@code docs/backlog.md} B-12）也应放进这个包。
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
