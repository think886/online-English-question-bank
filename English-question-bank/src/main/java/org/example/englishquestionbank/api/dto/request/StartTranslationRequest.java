package org.example.englishquestionbank.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 开始一次翻译练习的请求体。
 */
@Schema(description = "开始翻译练习的请求")
public record StartTranslationRequest(

        @Schema(description = "本次练习多少道翻译题，1~50", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 1, message = "题量必须大于或等于 1")
        @Max(value = 50, message = "题量不能超过 50")
        int count
) {
}
