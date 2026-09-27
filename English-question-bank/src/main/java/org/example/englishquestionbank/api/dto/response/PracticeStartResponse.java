package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.PracticeContent;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.QuestionOption;

import java.util.List;

/**
 * 「开始练习」的响应体 —— 一次返回渲染练习页所需的全部内容。
 *
 * <p><b>为什么一次返回全部</b>：一篇阅读只有 2~5 题、文章几百词，响应体很小；
 * 而分次拉取会让前端状态复杂、切题有延迟。这符合「一篇阅读整体提交」的模型。
 *
 * @param passage 阅读题会话的文章；<b>翻译题会话为 {@code null}</b>
 */
@Schema(description = "开始练习的响应")
public record PracticeStartResponse(
        Long sessionId,
        @Schema(description = "READING / TRANSLATION") String mode,
        Integer totalCount,
        Integer maxScore,
        PassageResponse passage,
        List<QuestionResponse> questions
) {

    public static PracticeStartResponse from(PracticeContent content) {
        List<QuestionResponse> questions = content.questions().stream()
                .map(q -> QuestionResponse.from(q, optionsOf(content, q)))
                .toList();
        return new PracticeStartResponse(
                content.session().getId(),
                content.session().getMode(),
                content.session().getTotalCount(),
                content.session().getMaxScore(),
                PassageResponse.from(content.passage()),
                questions);
    }

    private static List<QuestionOption> optionsOf(PracticeContent content, Question question) {
        return content.optionsByQuestion().get(question.getId());
    }
}
