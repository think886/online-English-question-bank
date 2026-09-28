package org.example.englishquestionbank.api;

import jakarta.servlet.http.HttpServletRequest;
import org.example.englishquestionbank.exception.UnauthenticatedException;
import org.example.englishquestionbank.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 当前用户身份提供者 —— Controller 获取「我是谁」的唯一入口。
 *
 * <p><b>存在的意义</b>：URL 里绝不出现 {@code userId}（见 {@code docs/api-layer.md} 红线 3）。
 * Controller 只调用 {@link #currentUserId()}，<b>不知道</b>身份从哪里来。
 *
 * <h2>身份解析顺序（先令牌，后请求头）</h2>
 * <ol>
 *   <li><b>登录令牌</b>：{@code Authorization: Bearer <token>} —— 真实鉴权</li>
 *   <li><b>开发期请求头</b>：{@value #DEBUG_USER_HEADER} —— <b>临时且不安全</b>，见下</li>
 * </ol>
 *
 * <h2>⚠️ 为什么还保留 {@value #DEBUG_USER_HEADER} 回退</h2>
 * 它是<strong>开发期占位实现，任何调用方都能自称是任意用户</strong>，
 * <b>绝不能用于任何对外环境</b>。保留它是为了让既有资产继续可用：
 * <ul>
 *   <li>{@code tools/api-smoke.ps1} 的 53 项断言全部用这个头，不必改写</li>
 *   <li>调试单个接口时不必先登录拿令牌</li>
 * </ul>
 * 迁移路径：等前端全面接入令牌后，<b>删掉回退分支与 {@value #DEBUG_USER_HEADER} 常量</b>，
 * 并同步改写 smoke 脚本。见 {@code docs/backlog.md} B-07。
 */
@Component
public class CurrentUserProvider {

    /** 临时身份请求头。彻底移除回退分支后本常量应一并删除。 */
    public static final String DEBUG_USER_HEADER = "X-Debug-User-Id";

    private static final String BEARER_PREFIX = "Bearer ";

    private final HttpServletRequest request;
    private final AuthService authService;

    /**
     * 是否启用开发期便利功能。见 {@code application.yml} 的 {@code app.dev-mode}。
     *
     * <p><b>代码里的兜底默认值是 {@code false}</b>（{@code :false} 那段）——
     * 万一配置文件丢了或换了配置源，行为是**安全**的那种，而不是相反。
     */
    private final boolean devMode;

    public CurrentUserProvider(HttpServletRequest request,
                               AuthService authService,
                               @Value("${app.dev-mode:false}") boolean devMode) {
        this.request = request;
        this.authService = authService;
        this.devMode = devMode;
    }

    /**
     * 取当前登录用户的 id。
     *
     * @return 用户 id
     * @throws UnauthenticatedException 没有有效令牌（且开发期回退不可用时）→ HTTP 401
     */
    public Long currentUserId() {
        // ---- 1. 登录令牌（唯一的真实鉴权方式）----
        String rawToken = currentRawToken();
        if (rawToken != null) {
            Long userId = authService.resolveUserId(rawToken);
            if (userId == null) {
                // 令牌「给了但无效」与「根本没给」是两回事：
                // 前者通常是过期或已登出，前端应当清掉本地令牌并跳回登录页。
                // 消息里明确说出来，前端才能区分该「重新登录」还是该「先登录」。
                throw new UnauthenticatedException("登录令牌无效、已过期或已登出，请重新登录");
            }
            return userId;
        }

        // ---- 2. 开发期回退：受 app.dev-mode 控制 ----
        if (!devMode) {
            // ⚠️ 这里的提示刻意【不提】X-Debug-User-Id ——
            // 关闭状态下不该再向调用方宣传这个后门的存在。
            throw new UnauthenticatedException(
                    "缺少身份信息：请先调用 POST /api/auth/login 获取令牌，"
                            + "再带上请求头 Authorization: Bearer <token>");
        }
        return resolveFromDebugHeader();
    }

    /**
     * 从 {@value #DEBUG_USER_HEADER} 解析用户 id。
     *
     * <p><b>⚠️ 只有在 {@code app.dev-mode=true} 时才会走到这里。</b>
     * 这条路径下任何调用方都能自称是任意用户，绝不能用于对外环境。
     */
    private Long resolveFromDebugHeader() {
        String raw = request.getHeader(DEBUG_USER_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthenticatedException(
                    "缺少身份信息：请带上 Authorization: Bearer <token>（先调 POST /api/auth/login），"
                            + "或开发期临时请求头 " + DEBUG_USER_HEADER
                            + "（不安全，见 docs/backlog.md B-07）");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new UnauthenticatedException(
                    "身份信息格式非法：" + DEBUG_USER_HEADER + " 必须是数字，收到 " + raw);
        }
    }

    /**
     * 取本次请求携带的<b>明文登录令牌</b>；没有则返回 {@code null}。
     *
     * <p>供 {@code POST /api/auth/logout} 使用 —— 登出必须知道「要撤销哪一个令牌」，
     * 而库里只存了它的哈希，所以只能从原始请求头里拿。
     *
     * <p>前缀比较用 {@code regionMatches(..., ignoreCase = true)}：
     * HTTP 规范里认证方案名是大小写不敏感的，{@code bearer} / {@code Bearer} 都应接受。
     *
     * @return 令牌明文，或 {@code null}
     */
    public String currentRawToken() {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
