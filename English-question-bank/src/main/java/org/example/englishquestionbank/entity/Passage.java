package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阅读文章实体，对应表 {@code passage}。
 *
 * <p>一篇 {@code passage} 对应 N 道选择题（见 {@link Question#getPassageId()}）。
 * 用户在文章正文上划词标记时，{@code user_word_mark.source_field} 记为 {@code PASSAGE}。
 */
@Data
@TableName("passage")
public class Passage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    /** 英文原文，用换行分段。MEDIUMTEXT，可能较长。 */
    private String content;

    /** 全文参考译文（可选）。 */
    private String translation;

    /** 出处，如「2023 考研英语一 Text 2」。 */
    private String source;

    /** 题材：科普 / 经济 / 教育…… */
    private String category;

    /** 难度 1~5。 */
    private Integer difficulty;

    /** 词数。 */
    private Integer wordCount;

    /** 1=启用 0=下架。 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
