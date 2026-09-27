package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.entity.QuestionOption;

/**
 * 选择题选项。
 *
 * <p><b>⚠ 刻意只有两个字段：没有 {@code isCorrect}</b>。
 * 把正确标志返回给前端，用户打开浏览器 DevTools 就能看到答案。
 * 判分只在服务端进行。
 */
@Schema(description = "选择题选项（不含是否为正确答案的标志）")
public record OptionResponse(
        @Schema(description = "选项标识", example = "A") String key,
        @Schema(description = "选项内容") String content
) {

    public static OptionResponse from(QuestionOption option) {
        return new OptionResponse(option.getOptionKey(), option.getContent());
    }
}
