package org.example.englishquestionbank.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 划词标记的请求体。
 *
 * <p><b>偏移量由前端计算</b>：前端手里有原文，能精确知道用户划的是哪一处；
 * 服务端算不准 —— 同一个词在文中可能出现多次。服务端只负责校验取值范围。
 *
 * <p>注意这里<b>没有 {@code userId}</b>，也没有 {@code sessionId}（它在 URL 路径里）——
 * 身份来自请求上下文。
 */
@Schema(description = "划词标记请求")
public record MarkWordRequest(

        @Schema(description = "标记时正在作答的题目 id；在文章正文上划词时可为空", example = "1")
        Long questionId,

        @Schema(description = "文本来源", example = "STEM",
                allowableValues = {"PASSAGE", "STEM", "OPTION", "SOURCE_TEXT"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "sourceField 不能为空")
        @Pattern(regexp = "PASSAGE|STEM|OPTION|SOURCE_TEXT",
                message = "sourceField 只能是 PASSAGE / STEM / OPTION / SOURCE_TEXT")
        String sourceField,

        @Schema(description = "原文形式，允许带首尾标点，服务端会清洗", example = "running",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "surfaceForm 不能为空")
        @Size(max = 64, message = "surfaceForm 不能超过 64 字符")
        String surfaceForm,

        @Schema(description = "所在句子快照（可选，便于复习时看语境）",
                example = "He is running fast.")
        @Size(max = 1024, message = "sentence 不能超过 1024 字符")
        String sentence,

        @Schema(description = "在文本中的起始下标（0 基，含）", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "charStart 不能为空")
        @Min(value = 0, message = "charStart 不能为负数")
        Integer charStart,

        @Schema(description = "结束下标（不含），必须大于 charStart", example = "12",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "charEnd 不能为空")
        @Min(value = 1, message = "charEnd 必须大于 0")
        Integer charEnd
) {
}
