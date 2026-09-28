package org.example.englishquestionbank.dto.query;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生词本条目（查询结果行）。
 *
 * <h2>⚠️ 为什么这里是 {@code @Data} 类，而 {@code dto} 包里其他类都是 record</h2>
 * 本类<strong>不是</strong>普通的传输对象，它是 <b>MyBatis 的行映射目标</b>
 * （XML 里 {@code resultType} 指向它）：
 * <ul>
 *   <li>MyBatis 的自动映射靠 <b>setter</b> 注入列值，record 没有 setter；
 *       用 record 必须改成「按构造器参数顺序映射」，一旦有人调整了 SQL 的列顺序
 *       或 DTO 的字段顺序，映射会<strong>静默错位</strong>（不是报错），极难排查</li>
 *   <li>所以「能用 record 就用 record」这条偏好在这里必须让位于正确性</li>
 * </ul>
 * 为让这个区别在包结构上就看得见，这些行映射类型统一放在 {@code dto.query} 子包下。
 *
 * <p>列名与字段名靠 {@code map-underscore-to-camel-case: true} 自动对应
 * （{@code normalized_form} → {@code normalizedForm}）。
 */
@Data
public class VocabItem {

    /** 归一化原形，如 {@code run}。生词本按它去重，一个词只有一行。 */
    private String normalizedForm;

    /** 展示形式，如 {@code Run}；词典未收录时为标记时的原文。 */
    private String displayForm;

    /** 中文释义；词典未收录时为 {@code null}（前端应提示「暂未收录释义」）。 */
    private String translation;

    /** 英式音标。ECDICT 只提供一套，偏英式。 */
    private String phoneticUk;

    /** 美式音标。ECDICT 无此数据，当前恒为 {@code null}（见 backlog B-02）。 */
    private String phoneticUs;

    /** 词性，如 {@code n.} / {@code v.}。 */
    private String partOfSpeech;

    /** 考试大纲标签，空格分隔：{@code cet4 cet6 ky} 等。 */
    private String tag;

    /** 累计标记次数（同一词在不同位置标多次会累加）。 */
    private Integer markCount;

    /** 掌握程度 0~5，留给将来的间隔复习功能。 */
    private Integer mastery;

    private LocalDateTime firstMarkedAt;

    private LocalDateTime lastMarkedAt;
}
