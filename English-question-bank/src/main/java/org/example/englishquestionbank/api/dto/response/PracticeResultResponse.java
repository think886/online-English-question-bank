package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.PracticeContent;
import org.example.englishquestionbank.dto.PracticeResult;

import java.util.List;

/**
 * 结果页 —— 本项目核心需求的兑现。
 *
 * <p>项目最初的需求是：「在完成题目后，会有已经标记过的单词及其翻译，
 * 以及记录用户已经做过的题目，以及其在题目中标记过的单词」。
 * 这个响应体一次给出全部四件事：
 * <ol>
 *   <li>{@link #session} —— 这次的成绩与耗时</li>
 *   <li>{@link #answers} —— 做过的每道题、用户答案、正确答案、解析，
 *       以及<b>该题中标记过的词</b></li>
 *   <li>{@link #markedWords} —— 本次标记过的<b>去重单词及释义</b></li>
 *   <li>{@link #passage} —— 阅读题会话的文章（翻译题会话为 {@code null}）</li>
 * </ol>
 *
 * <p><b>只在会话状态为 {@code FINISHED} 时返回</b>，否则等于提前给答案。
 */
@Schema(description = "练习结果页")
public record PracticeResultResponse(
        SessionSummaryResponse session,
        PassageResponse passage,
        List<AnswerDetailResponse> answers,
        List<MarkedWordSummaryResponse> markedWords
) {

    /**
     * 把服务层的结果对象拍平成响应体。
     *
     * <p>「哪道题 → 哪个作答记录 / 哪些标记词」这类查表放在这里做，
     * Controller 就只剩一行调用 —— 保持「Controller 不含业务规则」这条线。
     */
    public static PracticeResultResponse from(PracticeResult result) {
        PracticeContent content = result.content();

        // 按会话快照的题目顺序展开，保证结果页题号与做题时一致
        List<AnswerDetailResponse> answers = content.questions().stream()
                .map(question -> AnswerDetailResponse.from(
                        question,
                        content.optionsByQuestion().get(question.getId()),
                        result.answerByQuestion().get(question.getId()),
                        result.marksByQuestion().get(question.getId())))
                .toList();

        return new PracticeResultResponse(
                SessionSummaryResponse.from(content.session()),
                content.passage() == null ? null : PassageResponse.from(content.passage()),
                answers,
                result.markedWords().stream().map(MarkedWordSummaryResponse::from).toList());
    }
}
