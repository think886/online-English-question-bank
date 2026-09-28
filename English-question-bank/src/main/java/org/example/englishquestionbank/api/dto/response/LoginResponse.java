package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.LoginResult;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 登录成功的响应体。
 *
 * <p>同时给出 {@code expiresAt}（绝对时间）与 {@code expiresInSeconds}（相对秒数）：
 * <ul>
 *   <li>绝对时间便于前端显示「有效期至 …」</li>
 *   <li>相对秒数便于前端做倒计时，且<b>不受客户端与服务器时钟偏差影响</b>，
 *       前端应优先用它来判断何时该清理本地令牌</li>
 * </ul>
 *
 * @param token 令牌明文。<b>只在这一次响应里出现</b>，服务端之后再也拿不到它（库里存的是 SHA-256），
 *              前端必须自己保存好
 */
@Schema(description = "登录结果")
public record LoginResponse(
        @Schema(description = "登录令牌。后续请求放在请求头 Authorization: Bearer <token>") String token,
        @Schema(description = "过期时间（服务端本地时间）") LocalDateTime expiresAt,
        @Schema(description = "距过期还有多少秒；前端做倒计时请用它，避免时钟偏差") long expiresInSeconds,
        UserResponse user
) {

    public static LoginResponse from(LoginResult result) {
        long seconds = result.expiresAt() == null
                ? 0L
                : Math.max(0L, Duration.between(LocalDateTime.now(), result.expiresAt()).toSeconds());
        return new LoginResponse(
                result.token(),
                result.expiresAt(),
                seconds,
                UserResponse.from(result.user()));
    }
}
