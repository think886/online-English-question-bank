package org.example.englishquestionbank.dto.query;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 练习历史条目（查询结果行）。
 *
 * <p>行映射类型，用 {@code @Data} 而非 record，原因见 {@link VocabItem}。
 *
 * <p>字段基本就是 {@code practice_session} 本身，另加两样列表页需要但表里没有的信息：
 * 文章标题（要 JOIN）和标记词数（已在会话行上冗余，无需 JOIN）。
 */
@Data
public class PracticeHistoryItem {

    private Long sessionId;

    /** {@code READING} / {@code TRANSLATION}。 */
    private String mode;

    /** {@code IN_PROGRESS} / {@code FINISHED} / {@code ABANDONED}。 */
    private String status;

    /** 来源文章标题；翻译题练习为 {@code null}。 */
    private String passageTitle;

    private Integer totalCount;

    private Integer answeredCount;

    private Integer correctCount;

    private Integer score;

    private Integer maxScore;

    /** 本次标记的<strong>去重</strong>单词数。 */
    private Integer markedWordCount;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 总耗时（毫秒）；未交卷时为 {@code null}。 */
    private Long durationMs;
}
