package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.entity.PracticeSession;

import java.time.LocalDateTime;

/**
 * 会话汇总。
 *
 * <p>「交卷」与「结果页」共用这一个结构 —— 交卷后前端立刻能拿到与结果页一致的汇总，
 * 不必再请求一次。
 */
@Schema(description = "练习会话汇总")
public record SessionSummaryResponse(
        Long id,
        @Schema(description = "READING / TRANSLATION") String mode,
        @Schema(description = "IN_PROGRESS / FINISHED") String status,
        Integer totalCount,
        Integer answeredCount,
        Integer correctCount,
        Integer score,
        Integer maxScore,
        @Schema(description = "本次标记的去重单词数") Integer markedWordCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        @Schema(description = "总耗时（毫秒）") Long durationMs
) {

    public static SessionSummaryResponse from(PracticeSession session) {
        return new SessionSummaryResponse(
                session.getId(),
                session.getMode(),
                session.getStatus(),
                session.getTotalCount(),
                session.getAnsweredCount(),
                session.getCorrectCount(),
                session.getScore(),
                session.getMaxScore(),
                session.getMarkedWordCount(),
                session.getStartedAt(),
                session.getFinishedAt(),
                session.getDurationMs());
    }
}
