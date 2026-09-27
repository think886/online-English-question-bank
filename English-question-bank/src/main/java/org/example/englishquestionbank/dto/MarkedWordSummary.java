package org.example.englishquestionbank.dto;

/**
 * 本次会话标记过的词，按原形去重后的摘要。
 *
 * <p><b>为什么需要它</b>：{@code user_word_mark} 记录的是每一次划词**事件**
 * （同一个词在不同位置标两次就是两行），而结果页要展示的是
 * 「本次练习中我标记了哪些**不同的词**，各自是什么意思」——需要按原形聚合，
 * 并从词典补上释义。
 *
 * @param normalizedForm 归一化原形，如 {@code run}
 * @param displayForm    展示形式
 * @param translation    中文释义；词典未收录时为 {@code null}
 * @param wordId         命中的词条 id；未收录时为 {@code null}
 * @param markCount      在本次会话中被标记的次数（同一词标在不同位置会累计）
 * @param firstSentence  该词在<b>文本中最靠前</b>的那次标记的句子快照。
 *                       【纠正】此处曾写作「第一次标记时的句子」—— 不准确：
 *                       分组来自按 {@code char_start} 排序的标记列表，
 *                       取的是位置最靠前的句子，与用户点击的先后无关。
 */
public record MarkedWordSummary(
        String normalizedForm,
        String displayForm,
        String translation,
        Long wordId,
        int markCount,
        String firstSentence
) {
}
