package org.example.englishquestionbank.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger UI）配置。
 *
 * <h2>为什么必须显式声明安全方案</h2>
 * 本项目的所有接口都要身份（见 {@code docs/api-layer.md} 红线 3：{@code userId} 不出现在 URL 里，
 * 一律从请求上下文取）。而 <b>Swagger UI 默认没有任何填写自定义请求头的入口</b> ——
 * 只有在 OpenAPI 描述里声明了 {@code securitySchemes}，页面右上角才会出现
 * <b>Authorize</b> 按钮。
 *
 * <p>没有这段配置时的真实症状（已实测）：接口文档能正常打开，
 * 但点任何一个接口都返回 <b>401</b>，因为无法注入身份头，只能靠命令行 curl 手工加头。
 *
 * <h2>两个方案是「或」的关系</h2>
 * 两个 {@link SecurityRequirement} 并列，OpenAPI 语义是 <b>OR</b>：
 * 满足任意一个即可。所以：
 * <ul>
 *   <li>填 {@code X-Debug-User-Id} → 走开发期占位身份</li>
 *   <li>填 {@code bearerAuth} → 走登录换来的真实 token</li>
 * </ul>
 * 两个都填也不会出错，服务端按「token 优先、回退请求头」处理。
 *
 * <h2>⚠️ 安全提示</h2>
 * {@code X-Debug-User-Id} 方案是<b>临时且不安全</b>的（任何调用方都能自称是任意用户），
 * 仅用于开发期联调，上线前必须随 {@code CurrentUserProvider} 的占位实现一起移除
 * （见 {@code docs/backlog.md} B-07）。
 */
@Configuration
public class OpenApiConfig {

    /** 开发期临时身份头的方案名，与 {@code CurrentUserProvider.DEBUG_USER_HEADER} 保持一致。 */
    private static final String DEBUG_HEADER_SCHEME = "X-Debug-User-Id";

    /** 登录 token 的方案名，供前端与 Swagger 引用。 */
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI englishQuestionBankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("英语答题系统 API")
                        .version("v1")
                        .description("""
                                阅读题与翻译题练习系统。

                                **怎么在页面上调通接口**：点右上角 **Authorize**，二选一 ——

                                1. `X-Debug-User-Id` 填演示用户 id `9`（开发期临时方案，见 backlog B-07）
                                2. `bearerAuth` 填 `POST /api/auth/login` 返回的 `token`（只填 token 本身，不要自己加 `Bearer ` 前缀）
                                """))
                .components(new Components()
                        .addSecuritySchemes(DEBUG_HEADER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Debug-User-Id")
                                .description("""
                                        **开发期临时方案，不安全**：直接填用户 id（如 `9`）。
                                        任何调用方都能自称是任意用户，上线前必须移除（backlog B-07）。"""))
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque")
                                .description("""
                                        先调 `POST /api/auth/login` 拿 token，再把 **token 本身**填到这里。
                                        服务端会自行加上 `Bearer ` 前缀，不要在这里重复写。""")))
                // 两个并列 = OR，满足其一即可通过认证
                .addSecurityItem(new SecurityRequirement().addList(DEBUG_HEADER_SCHEME))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
