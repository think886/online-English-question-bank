package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.query.WrongQuestionItem;

import java.time.LocalDateTime;

/**
 * 错题本条目（对外契约）。
 *
 * <p><b>这里出现正确答案是安全的</b>：本接口只返回<strong>已交卷会话</strong>里的错题
 * （过滤条件写在 {@code AnswerRecordMapper.xml} 的 SQL 里），
 * 因此不构成「交卷前泄题」。详见 {@code docs/api-layer.md} 红线 2。
 */
@Schema(description = "错题本条目（仅已交卷会话中的错题）")
public record WrongQuestionResponse(
        Long answerRecordId,
        @Schema(description = "属于哪一次练习，可据此跳回该次结果页") Long sessionId,
        Long questionId,
        @Schema(description = "READING / TRANSLATION") String questionType,
        @Schema(description = "题干") String stem,
        @Schema(description = "翻译题的待译中文原文；阅读题为 null") String sourceText,
        @Schema(description = "当时的作答：阅读题是选项标识，翻译题是用户译文") String userAnswer,
        @Schema(description = "阅读题的正确选项标识；翻译题为 null") String correctOptionKey,
        @Schema(description = "翻译题的参考译文；阅读题为 null") String referenceAnswer,
        @Schema(description = "解析；示例数据中为 null") String analysis,
        @Schema(description = "来源文章 id；翻译题为 null") Long passageId,
        @Schema(description = "来源文章标题；翻译题为 null") String passageTitle,
        LocalDateTime answeredAt
) {

    public static WrongQuestionResponse from(WrongQuestionItem item) {
        return new WrongQuestionResponse(
                item.getAnswerRecordId(),
                item.getSessionId(),
                item.getQuestionId(),
                item.getQuestionType(),
                item.getStem(),
                item.getSourceText(),
                item.getUserAnswer(),
                item.getCorrectOptionKey(),
                item.getReferenceAnswer(),
                item.getAnalysis(),
                item.getPassageId(),
                item.getPassageTitle(),
                item.getAnsweredAt());
    }
}
