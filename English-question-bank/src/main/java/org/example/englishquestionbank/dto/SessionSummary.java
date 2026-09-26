package org.example.englishquestionbank.dto;

import lombok.Data;

/**
 * 会话的作答汇总，由聚合查询得出。
 *
 * <p><b>为什么用 class 而不是 record</b>：MyBatis 的自动结果映射依赖
 * 「无参构造 + setter」，record 没有 setter。虽然可以用 XML 的
 * {@code <constructor>} 显式映射，但那要多写一段配置，不划算。
 *
 * <p>字段名与聚合 SQL 的列别名一一对应
 * （{@code max_score} 这类下划线别名由 {@code map-underscore-to-camel-case} 自动转换）。
 */
@Data
public class SessionSummary {

    /** 已作答题量 —— {@code COUNT(*)}。 */
    private Integer answered;

    /**
     * 答对题量 —— {@code SUM(is_correct = 1)}。
     *
     * <p>翻译题的 {@code is_correct} 为 NULL，{@code NULL = 1} 结果是 NULL，
     * 会被 {@code SUM} 忽略，因此不会污染计数。
     */
    private Integer correct;

    /** 总得分 —— {@code SUM(score)}；未判分的翻译题 score 为 NULL，同样被忽略。 */
    private Integer score;
}
