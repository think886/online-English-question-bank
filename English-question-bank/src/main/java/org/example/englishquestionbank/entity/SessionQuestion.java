package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话题目快照实体，对应表 {@code session_question}。
 *
 * <p><b>存在的意义</b>：固化「本次会话抽到了哪些题、以什么顺序展示」。
 * 如果只靠 {@code practice_session.passage_id} 反查题目，一旦文章事后被编辑
 * （增删题目、调整题号），历史会话就会错乱。
 *
 * <p>{@link #markedWordCount} 是冗余统计，让结果页能直接显示「这题你标了几个词」，
 * 不必每次聚合 {@code user_word_mark}。
 *
 * <p>注意本表没有 {@code created_at} / {@code updated_at} 列。
 */
@Data
@TableName("session_question")
public class SessionQuestion {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long questionId;

    /** 本次会话中的展示顺序。 */
    private Integer sortOrder;

    /** UNANSWERED / ANSWERED。 */
    private String status;

    /** 该题标记的词次（冗余）。 */
    private Integer markedWordCount;
}
