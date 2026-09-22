package org.example.englishquestionbank.dto;

/**
 * 划词标记的返回结果。
 *
 * <p>返回 {@code translation} 是刻意的：让前端<b>一次调用就拿到释义</b>，
 * 不必再单独查一次词典。用户划词的即时反馈是这个系统体验的关键一环。
 *
 * @param markId         标记记录的 id；本次是重复标记（未新增行）时为已存在行的 id
 * @param normalizedForm 归一化后的原形，如 {@code running → run}
 * @param wordId         命中的词条 id；词典未收录时为 {@code null}
 * @param translation    中文释义；{@code wordId} 为 null 时也为 null
 * @param alreadyMarked  {@code true} 表示该位置之前已标记过，本次为空操作
 */
public record MarkResult(
        Long markId,
        String normalizedForm,
        Long wordId,
        String translation,
        boolean alreadyMarked
) {
}
