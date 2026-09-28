package org.example.englishquestionbank.service;

import org.example.englishquestionbank.dto.LoginResult;

/**
 * 认证服务 —— 登录换令牌、登出撤销令牌、按令牌解析用户。
 *
 * <h2>方案：不透明随机令牌，存库</h2>
 * 没有采用 JWT。理由：
 * <ul>
 *   <li><b>本项目每个请求本来就要查库</b>（会话、作答记录、划词标记都在 MySQL），
 *       JWT「无状态、免查库」的优势在这里没有意义</li>
 *   <li><b>JWT 不可撤销</b>：登出、改密码、封号之后，已签发的令牌在过期前依然有效。
 *       不透明令牌存库则天然可撤销（删行或置 {@code revoked_at}）</li>
 *   <li><b>零新依赖</b>：只需要 BCrypt 那一个 jar；JWT 要额外引入 jjwt 或整套 Spring Security</li>
 * </ul>
 *
 * <h2>令牌的存放</h2>
 * 库里存的是令牌的 <b>SHA-256</b>，不是明文 —— 令牌等价于密码，
 * 明文入库则一次拖库就等于所有在线用户被冒充。详见 {@code db/alter-03-user-token.sql}。
 *
 * <h2>为什么本服务不依赖 Spring Security 的过滤器链</h2>
 * 身份解析由 {@code api.CurrentUserProvider} 调用 {@link #resolveUserId} 完成，
 * 没有引入 Security 的 Filter，因此现有 7 个接口的行为完全不变
 * （含 {@code X-Debug-User-Id} 的开发期回退）。
 */
public interface AuthService {

    /**
     * 用用户名 + 密码登录，成功则签发一个新令牌。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>校验入参非空</li>
     *   <li>按用户名查用户</li>
     *   <li><b>校验密码</b>（用户不存在或未设密码时也走一次等量计算，抵抗时序攻击）</li>
     *   <li>校验账号状态（放在密码校验<strong>之后</strong>，避免泄露「哪些用户名存在」）</li>
     *   <li>生成 32 字节随机令牌，入库其 SHA-256，返回明文令牌</li>
     * </ol>
     *
     * @param username 登录名
     * @param password 明文密码
     * @return 令牌与用户信息
     * @throws IllegalArgumentException 入参为空
     * @throws org.example.englishquestionbank.exception.UnauthenticatedException
     *         用户名或密码错误 / 账号被禁用（映射为 HTTP 401）
     */
    LoginResult login(String username, String password);

    /**
     * 按令牌解析出用户 id。
     *
     * <p>令牌必须同时满足：存在、未撤销、未过期。
     *
     * <p><b>副作用</b>：会节流更新 {@code last_used_at}
     * （仅在距上次记录超过 5 分钟时才写），避免把每个「读」请求都变成一次 UPDATE。
     *
     * @param rawToken 明文令牌；为空时返回 {@code null}
     * @return 用户 id；令牌无效、被撤销或已过期时返回 {@code null}
     */
    Long resolveUserId(String rawToken);

    /**
     * 登出：撤销给定令牌。
     *
     * <p><b>幂等</b>：令牌不存在或已撤销时不报错。登出失败没有重试的意义，
     * 报错只会让前端多写一段无用的错误处理。
     *
     * @param rawToken 明文令牌；为空时什么也不做
     */
    void logout(String rawToken);

    /**
     * 撤销某用户的<strong>全部</strong>有效令牌（「踢下线」）。
     *
     * <p>改密码、封号、疑似盗号时应调用。目前尚无调用方（没有改密码接口），
     * 先作为能力提供出来，并在 {@code docs/backlog.md} 里登记。
     *
     * @param userId 用户 id
     * @return 被撤销的令牌条数
     */
    int revokeAll(Long userId);
}
