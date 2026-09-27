package org.example.englishquestionbank.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交一题答案的请求体。
 *
 * <p>阅读题填选项标识（A/B/C/D），翻译题填用户译文 —— 服务端按题型分别处理。
 */
@Schema(description = "提交答案请求")
public record SubmitAnswerRequest(

        @Schema(description = "题目 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "questionId 不能为空")
        Long questionId,

        @Schema(description = "阅读题填选项标识 A/B/C/D；翻译题填用户译文", example = "B",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "userAnswer 不能为空")
        @Size(max = 5000, message = "答案长度不能超过 5000 字符")
        String userAnswer
) {
}
