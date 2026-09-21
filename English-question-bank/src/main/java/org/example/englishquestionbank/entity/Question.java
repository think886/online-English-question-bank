package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 题目实体，对应表 {@code question}。
 *
 * <p><b>阅读题与翻译题共用这一张表</b>，靠 {@link #questionType} 区分，
 * 因此下列字段是「按题型二选一」使用的：
 *
 * <table border="1">
 *   <caption>两种题型的字段用法</caption>
 *   <tr><th>字段</th><th>READING（阅读选择）</th><th>TRANSLATION（翻译）</th></tr>
 *   <tr><td>{@link #passageId}</td><td>所属文章 id</td><td>NULL</td></tr>
 *   <tr><td>{@link #stem}</td><td>题干</td><td>作答要求</td></tr>
 *   <tr><td>{@link #sourceText}</td><td>NULL</td><td>待翻译的英文原文</td></tr>
 *   <tr><td>{@link #referenceAnswer}</td><td>NULL</td><td>参考译文</td></tr>
 * </table>
 *
 * <p>选择题的选项不在这张表里，而在 {@code question_option} 表。
 */
@Data
@TableName("question")
public class Question {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** READING=阅读选择 / TRANSLATION=翻译。 */
    private String questionType;

    /** 阅读题所属文章；翻译题为 NULL。 */
    private Long passageId;

    /** 题干 / 作答要求。 */
    private String stem;

    /** 翻译题：待翻译的英文原文。 */
    private String sourceText;

    /** 翻译题：参考译文。 */
    private String referenceAnswer;

    /** 解析。 */
    private String analysis;

    private Integer difficulty;

    /** 满分。 */
    private Integer score;

    /** 文章内 / 章节内题号。 */
    private Integer seq;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
