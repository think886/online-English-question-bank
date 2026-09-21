package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 练习会话实体，对应表 {@code practice_session}。
 *
 * <p>一次会话 = 一篇阅读及其下 N 道题，整体提交。
 * 会话内抽到的题目快照记录在 {@code session_question} 表，用户的划词标记记录在
 * {@code user_word_mark} 表（通过 {@code session_id} 关联）。
 *
 * <p>统计字段（{@link #totalCount} 等）是冗余的汇总值，用于结果页快速展示，
 * 避免每次聚合 {@code answer_record}。
 */
@Data
@TableName("practice_session")
public class PracticeSession {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** READING / TRANSLATION / MIXED（预留）。 */
    private String mode;

    /** 按篇练习时记录所属文章。 */
    private Long passageId;

    /** 本次题量。 */
    private Integer totalCount;

    /** 已作答题量。 */
    private Integer answeredCount;

    /** 答对题量。 */
    private Integer correctCount;

    /** 总得分。 */
    private Integer score;

    /** 总分值。 */
    private Integer maxScore;

    /** 本次标记的去重单词数。 */
    private Integer markedWordCount;

    /** IN_PROGRESS / FINISHED / ABANDONED。 */
    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 总耗时（毫秒）。 */
    private Long durationMs;
}
