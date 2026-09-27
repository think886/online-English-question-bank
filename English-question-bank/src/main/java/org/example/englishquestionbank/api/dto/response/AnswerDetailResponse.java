package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.entity.AnswerRecord;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.QuestionOption;
import org.example.englishquestionbank.entity.UserWordMark;

import java.util.List;

/**
 * 结果页里的单题明细。
 *
 * <p><b>这里是全项目唯一会返回正确答案的地方</b> ——
 * {@code correctAnswer} / {@code referenceAnswer} / {@code analysis}
 * 只在<b>交卷之后</b>的结果页出现。练习开始的响应里绝不能有它们。
 *
 * @param correctAnswer   阅读题的正确选项标识；翻译题为 {@code null}
 * @param referenceAnswer 翻译题的参考译文；阅读题为 {@code null}
 * @param analysis        解析
 * @param markedWords     该题的作答过程中标记过的词
 */
@Schema(description = "结果页单题明细（含正确答案与解析，仅交卷后可见）")
public record AnswerDetailResponse(
        Long questionId,
        Integer seq,
        String questionType,
        String stem,
        @Schema(description = "用户的作答：阅读题为选项标识，翻译题为译文") String userAnswer,
        @Schema(description = "阅读题 0/1；翻译题评分前为 null") Boolean isCorrect,
        Integer score,
        Integer maxScore,
        String gradingStatus,
        String correctAnswer,
        String referenceAnswer,
        String analysis,
        List<MarkedWordResponse> markedWords
) {

    public static AnswerDetailResponse from(Question question,
                                            List<QuestionOption> options,
                                            AnswerRecord record,
                                            List<UserWordMark> marks) {
        boolean reading = "READING".equals(question.getQuestionType());
        return new AnswerDetailResponse(
                question.getId(),
                question.getSeq(),
                question.getQuestionType(),
                question.getStem(),
                record == null ? null : record.getUserAnswer(),
                record == null || record.getIsCorrect() == null
                        ? null : record.getIsCorrect() == 1,
                record == null ? null : record.getScore(),
                question.getScore(),
                record == null ? null : record.getGradingStatus(),
                reading ? correctOptionKey(options) : null,
                reading ? null : question.getReferenceAnswer(),
                question.getAnalysis(),
                marks == null ? List.of() : marks.stream().map(MarkedWordResponse::from).toList());
    }

    /** 从选项里找出正确项；找不到（如题目数据不完整）时返回 null。 */
    private static String correctOptionKey(List<QuestionOption> options) {
        if (options == null) {
            return null;
        }
        return options.stream()
                .filter(o -> Integer.valueOf(1).equals(o.getIsCorrect()))
                .map(QuestionOption::getOptionKey)
                .findFirst()
                .orElse(null);
    }
}
