package org.example.englishquestionbank.dto;

/**
 * 划词标记的入参。
 *
 * <p>用 record 而不是一长串方法参数：本方法有 8~9 个参数，平铺在签名里调用处完全没法读。
 *
 * <p><b>字段顺序提醒</b>（Java 侧构造时按位置传参，请对照）：
 * {@code userId, sessionId, questionId, passageId, sourceField,
 * surfaceForm, sentence, charStart, charEnd}
 *
 * <p><b>三个 id 允许为 null</b>：
 * <ul>
 *   <li>{@code sessionId} —— 脱离练习会话的自由阅读</li>
 *   <li>{@code questionId} —— 在<b>文章正文</b>上划词时通常为空（正文不属于某一题）</li>
 *   <li>{@code passageId} —— 该词实际所在文章，从题目题干划词时也可能是空</li>
 * </ul>
 * 这三个值共同决定「在哪段文本上标的」，是去重键 {@code uk_mark_pos} 的组成部分。
 *
 * @param userId      谁标的
 * @param sessionId   所属练习会话，可空
 * @param questionId  标记时正在作答的题目，可空
 * @param passageId   该词实际所在文章，可空
 * @param sourceField 文本来源：{@code PASSAGE} / {@code STEM} / {@code OPTION} / {@code SOURCE_TEXT}
 * @param surfaceForm 原文形式，如 {@code running}（允许带首尾标点，服务会清洗）
 * @param sentence    所在句子快照，可空。由前端提供更简单（它已经有原文），
 *                    后端按偏移量提取是后续的改进项
 * @param charStart   在文本中的起始下标（0 基，含）
 * @param charEnd     结束下标（不含）
 */
public record MarkCommand(
        Long userId,
        Long sessionId,
        Long questionId,
        Long passageId,
        String sourceField,
        String surfaceForm,
        String sentence,
        Integer charStart,
        Integer charEnd
) {
}
