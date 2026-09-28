package org.example.englishquestionbank.dto;

import org.example.englishquestionbank.entity.SysUser;

import java.time.LocalDateTime;

/**
 * 登录成功的结果。
 *
 * <p><b>为什么把 token 和 user 一起返回</b>：前端登录后立刻要显示用户昵称、
 * 判断角色（USER/ADMIN）决定显示哪些入口。若只给 token，前端还得再调一次「查我自己」，
 * 白白多一个来回。
 *
 * @param token     令牌的<strong>明文</strong>，只在这一次响应里出现。
 *                  服务端存的是它的 SHA-256，之后再也拿不到明文 ——
 *                  前端必须自己保存好（正式前端应放在 HttpOnly Cookie 或内存里，不要写进 localStorage）
 * @param expiresAt 过期时间（服务端本地时间）
 * @param user      已登录用户（不含 {@code passwordHash}）
 */
public record LoginResult(
        String token,
        LocalDateTime expiresAt,
        SysUser user
) {
}
