package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 选择题选项实体，对应表 {@code question_option}。
 *
 * <p>仅 {@code question.question_type = 'READING'} 的题目有选项。
 * {@code (question_id, option_key)} 上有唯一索引，保证同一题内选项标识不重复。
 *
 * <p>注意本表没有 {@code created_at} / {@code updated_at} 列，与其它表不同。
 */
@Data
@TableName("question_option")
public class QuestionOption {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long questionId;

    /** 选项标识 A/B/C/D，数据库列类型是 CHAR(1)。 */
    private String optionKey;

    /** 选项内容。 */
    private String content;

    /** 1=正确选项。 */
    private Integer isCorrect;

    /** 展示顺序。 */
    private Integer seq;
}
