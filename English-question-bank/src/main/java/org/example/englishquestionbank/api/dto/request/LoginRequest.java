package org.example.englishquestionbank.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体。
 *
 * <p>注意这里<b>没有 {@code userId}</b> —— 身份来自凭据本身，
 * 这也是「{@code userId} 绝不作为 URL 或请求参数」这条红线在登录接口上的体现。
 */
@Schema(description = "登录请求")
public record LoginRequest(

        @Schema(description = "登录名", example = "demo", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64")
        String username,

        @Schema(description = "明文密码（HTTPS 下传输；服务端只存 BCrypt 哈希）",
                example = "demo123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "密码不能为空")
        @Size(max = 128, message = "密码长度不能超过 128")
        String password
) {
}
