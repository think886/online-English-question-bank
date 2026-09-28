package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.query.PracticeHistoryItem;

import java.time.LocalDateTime;

/**
 * 练习历史条目（对外契约）。
 *
 * <p>与「交卷」返回的 {@link SessionSummaryResponse} 长得像但用途不同：
 * 那个是<strong>单次会话的完整汇总</strong>（含 {@code finishedAt}、{@code durationMs} 等），
 * 这个是<strong>列表里的一行</strong>（多一个 {@code passageTitle} 便于辨识，
 * 且 {@code id} 改名为语义更明确的 {@code sessionId}）。
 */
@Schema(description = "练习历史条目")
public record PracticeHistoryResponse(
        @Schema(description = "会话 id") Long sessionId,
        @Schema(description = "READING / TRANSLATION") String mode,
        @Schema(description = "IN_PROGRESS / FINISHED / ABANDONED") String status,
        @Schema(description = "来源文章标题；翻译题练习为 null") String passageTitle,
        Integer totalCount,
        Integer answeredCount,
        Integer correctCount,
        Integer score,
        Integer maxScore,
        @Schema(description = "本次标记的去重单词数") Integer markedWordCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        @Schema(description = "总耗时（毫秒）；未交卷时为 null") Long durationMs
) {

    public static PracticeHistoryResponse from(PracticeHistoryItem item) {
        return new PracticeHistoryResponse(
                item.getSessionId(),
                item.getMode(),
                item.getStatus(),
                item.getPassageTitle(),
                item.getTotalCount(),
                item.getAnsweredCount(),
                item.getCorrectCount(),
                item.getScore(),
                item.getMaxScore(),
                item.getMarkedWordCount(),
                item.getStartedAt(),
                item.getFinishedAt(),
                item.getDurationMs());
    }
}
