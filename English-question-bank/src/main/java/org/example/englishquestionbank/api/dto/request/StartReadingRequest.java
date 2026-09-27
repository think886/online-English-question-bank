package org.example.englishquestionbank.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 开始一次阅读练习的请求体。
 *
 * <p>只有 {@code passageId} —— 用户身份来自请求上下文，**不在请求体里**（见 API 层红线 3）。
 */
@Schema(description = "开始阅读练习的请求")
public record StartReadingRequest(

        @Schema(description = "文章 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "passageId 不能为空")
        Long passageId
) {
}
