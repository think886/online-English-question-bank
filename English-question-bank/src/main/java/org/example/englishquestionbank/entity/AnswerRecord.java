package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作答记录实体，对应表 {@code answer_record}。
 *
 * <p>粒度是「一次会话 × 一道题」，{@code (session_id, question_id)} 上有唯一索引。
 * 这张表同时也是**错题本的数据源**——不需要额外的错题表，
 * 查询 {@code is_correct = 0} 即可。
 *
 * <p><b>{@link #isCorrect} 是可空的</b>：阅读题自动判分得到 0/1，
 * 而翻译题在评分完成前为 NULL。配合 {@link #gradingStatus} 表达评分生命周期。
 *
 * <p>注意本表没有 {@code created_at} / {@code updated_at}，用 {@link #answeredAt} 记录作答时间。
 */
@Data
@TableName("answer_record")
public class AnswerRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long userId;

    private Long questionId;

    /** 阅读题存选项 key(A/B/C/D)，翻译题存用户译文。 */
    private String userAnswer;

    /** 阅读题 0/1；翻译题在评分完成前为 NULL。 */
    private Integer isCorrect;

    /** 实得分。 */
    private Integer score;

    /** 本题满分快照（防止题目分值事后被改而影响历史记录）。 */
    private Integer maxScore;

    /** NONE=无需判分 / PENDING=待评分 / GRADING=评分中 / DONE=已评分 / FAILED=评分失败。 */
    private String gradingStatus;

    /** AUTO / AI / SELF / TEACHER。 */
    private String gradedBy;

    private LocalDateTime gradedAt;

    /** 本题耗时（毫秒）。 */
    private Long durationMs;

    private LocalDateTime answeredAt;
}
