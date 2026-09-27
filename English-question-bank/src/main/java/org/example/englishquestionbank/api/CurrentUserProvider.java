package org.example.englishquestionbank.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 当前用户身份提供者。
 *
 * <p><b>存在的意义</b>：URL 里绝不出现 {@code userId}（见 {@code docs/api-layer.md} 的红线 3）。
 * Controller 只调用 {@link #currentUserId()}，<b>不知道</b>身份从哪里来。
 * 将来接入真正的鉴权（Session 或 JWT）时，只需替换本类的实现，
 * Controller、URL、DTO 全都不用动。
 *
 * <h2>⚠️ 当前实现是临时占位，且不安全</h2>
 * <ul>
 *   <li>它<b>从请求头 {@value #DEBUG_USER_HEADER} 直接读 userId</b>，
 *       意味着任何调用方都能自称是任意用户</li>
 *   <li>它<b>绝不能用于任何对外环境</b>，仅用于开发期联调</li>
 *   <li>替换方案见 {@code docs/backlog.md} 的 <b>B-07</b>（登录鉴权与密码加密）</li>
 * </ul>
 * 之所以先这样占位：URL 形态与 Controller 代码按「最终一定有鉴权」的样子写死，
 * 以后换实现不必重写接口。
 */
@Component
public class CurrentUserProvider {

    /** 临时身份请求头。换成 JWT 后本常量应删除。 */
    public static final String DEBUG_USER_HEADER = "X-Debug-User-Id";

    private final HttpServletRequest request;

    public CurrentUserProvider(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * 取当前登录用户的 id。
     *
     * @return 用户 id
     * @throws UnauthenticatedException 请求头缺失、为空或不是合法数字（映射为 HTTP 401）
     */
    public Long currentUserId() {
        String raw = request.getHeader(DEBUG_USER_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthenticatedException(
                    "缺少身份信息：请带上请求头 " + DEBUG_USER_HEADER
                            + "（开发期临时方案，见 docs/backlog.md B-07）");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new UnauthenticatedException(
                    "身份信息格式非法：" + DEBUG_USER_HEADER + " 必须是数字，收到 " + raw);
        }
    }
}
