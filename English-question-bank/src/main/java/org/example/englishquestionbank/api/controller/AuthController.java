package org.example.englishquestionbank.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.api.ApiResponse;
import org.example.englishquestionbank.api.CurrentUserProvider;
import org.example.englishquestionbank.api.dto.request.LoginRequest;
import org.example.englishquestionbank.api.dto.response.LoginResponse;
import org.example.englishquestionbank.dto.LoginResult;
import org.example.englishquestionbank.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口 —— 本项目第一个<b>不需要身份</b>的接口组。
 *
 * <p><b>URL 为什么是 {@code /api/auth} 而不是 {@code /api/login}</b>：
 * 认证是一个独立的能力域（登录、登出，将来还有改密码、刷新令牌），
 * 归到一个前缀下便于统一配置「免鉴权白名单」。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "登录换令牌、登出撤销令牌")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserProvider currentUser;

    /**
     * 登录：用户名 + 密码 → 令牌。
     *
     * <p><b>为什么用 {@code POST} 而不是 {@code GET}</b>：密码不能出现在 URL 里
     * （会进入浏览器历史、代理日志、Referer 头）。这是硬性要求，不是风格偏好。
     *
     * <p>返回 200 而不是 201：登录并没有「创建」一个可用 URL 指向的资源，
     * 令牌是响应的一部分而不是可寻址资源。
     */
    @PostMapping("/login")
    @Operation(summary = "登录，换取访问令牌",
            description = "成功后返回 token，后续请求放在请求头 `Authorization: Bearer <token>`。"
                    + "用户名不存在与密码错误返回同一句提示，避免被用来枚举有效账号")
    @SecurityRequirements   // 本接口自身不需要认证，从 OpenAPI 里摘掉全局安全要求
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        LoginResult result = authService.login(request.username(), request.password());
        return ResponseEntity.ok(ApiResponse.ok(LoginResponse.from(result)));
    }

    /**
     * 登出：撤销当前请求携带的令牌。
     *
     * <p><b>幂等</b>：令牌不存在、已撤销、或压根没带令牌，都返回成功。
     * 登出失败没有重试的意义 —— 报错只会让前端多写一段无用的错误处理，
     * 而用户的真实诉求（「别再让别人用这个令牌」）要么已满足，要么本来就没有令牌。
     */
    @PostMapping("/logout")
    @Operation(summary = "登出，撤销当前令牌",
            description = "幂等：令牌不存在或已撤销也返回成功")
    public ResponseEntity<ApiResponse<Void>> logout() {
        // 登出必须知道「撤销哪一个令牌」，而库里只存了 SHA-256，
        // 所以只能从原始请求头里取明文 —— 这也是 currentRawToken() 存在的唯一理由。
        authService.logout(currentUser.currentRawToken());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
