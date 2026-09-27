package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.AnswerResult;

/**
 * 提交答案的响应体。
 *
 * <p>返回判分结论是为了让前端**立即得到反馈**（对吗、得了多少分、
 * 翻译题是否还在评分中），不必再查一次。
 */
@Schema(description = "提交答案的结果")
public record AnswerResultResponse(
        Long questionId,

        @Schema(description = "阅读题 0/1；翻译题在评分完成前为 null")
        Boolean isCorrect,

        @Schema(description = "实得分；翻译题在评分前为 null")
        Integer score,

        Integer maxScore,

        @Schema(description = "DONE=已判分 / PENDING=待评分（翻译题）")
        String gradingStatus,

        @Schema(description = "true=首次作答；false=覆盖了上一次答案")
        boolean firstSubmit
) {

    public static AnswerResultResponse from(Long questionId, AnswerResult result) {
        return new AnswerResultResponse(
                questionId,
                result.isCorrect() == null ? null : result.isCorrect() == 1,
                result.score(),
                result.maxScore(),
                result.gradingStatus(),
                result.firstSubmit());
    }
}
