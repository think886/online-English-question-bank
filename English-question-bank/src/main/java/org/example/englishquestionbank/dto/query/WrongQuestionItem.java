package org.example.englishquestionbank.dto.query;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错题本条目（查询结果行）。
 *
 * <p>行映射类型，用 {@code @Data} 而非 record，原因见 {@link VocabItem}。
 *
 * <h2>为什么这里可以带正确答案</h2>
 * 「交卷前不返回答案」是红线 2，但错题本<strong>只取已交卷会话（{@code status='FINISHED'}）的错题</strong>
 * —— 练习进行中的错题不会进来。所以这里的 {@code correctOptionKey} / {@code referenceAnswer}
 * 不构成提前泄题，而「做错了却看不到正确答案」会让错题本失去意义。
 *
 * <p>这条过滤条件写在 {@code AnswerRecordMapper.xml} 的 SQL 里，是本接口的关键约束。
 */
@Data
public class WrongQuestionItem {

    /** 作答记录 id。同一道题在不同会话里做错会有多条，用它可以区分。 */
    private Long answerRecordId;

    /** 属于哪一次练习。前端可据此跳回那次练习的结果页。 */
    private Long sessionId;

    private Long questionId;

    /** {@code READING} / {@code TRANSLATION}。 */
    private String questionType;

    /** 题干；翻译题则是「请将下面这段中文翻译成英文」这类提示语。 */
    private String stem;

    /** 翻译题的待译中文原文；阅读题为 {@code null}。 */
    private String sourceText;

    /** 当时的作答：阅读题是选项标识，翻译题是用户译文。 */
    private String userAnswer;

    /** 阅读题的正确选项标识；翻译题为 {@code null}。 */
    private String correctOptionKey;

    /** 翻译题的参考译文；阅读题为 {@code null}。 */
    private String referenceAnswer;

    /** 解析；示例数据中为 {@code null}。 */
    private String analysis;

    /** 来源文章 id；翻译题为 {@code null}。 */
    private Long passageId;

    /** 来源文章标题；翻译题为 {@code null}。 */
    private String passageTitle;

    /** 作答时间。列表按它倒序，最近错的排最前。 */
    private LocalDateTime answeredAt;
}
