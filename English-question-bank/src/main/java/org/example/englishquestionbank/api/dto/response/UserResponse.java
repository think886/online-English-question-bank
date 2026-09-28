package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.entity.SysUser;

/**
 * 用户信息的对外形态。
 *
 * <p><b>为什么不直接返回 {@code SysUser} 实体</b>：实体带着 {@code passwordHash}
 * （BCrypt 哈希）与 {@code status}、{@code userType} 等内部字段。
 * 返回实体等于把哈希泄漏给前端，而哈希是可以离线爆破的 ——
 * 这正是 {@code docs/api-layer.md} 红线 1「绝不直接返回实体」。
 *
 * @param id       用户 id。前端拿到它之后<b>不要</b>再往 URL 里塞，身份一律靠令牌
 * @param username 登录名
 * @param nickname 昵称
 * @param role     {@code USER} / {@code ADMIN}
 */
@Schema(description = "用户信息（不含密码哈希）")
public record UserResponse(
        Long id,
        String username,
        String nickname,
        @Schema(description = "USER=普通用户 / ADMIN=题库管理员", example = "USER") String role
) {

    public static UserResponse from(SysUser user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole());
    }
}
