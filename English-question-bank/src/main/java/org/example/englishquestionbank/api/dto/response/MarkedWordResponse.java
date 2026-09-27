package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.entity.UserWordMark;

/**
 * 一条划词记录。
 *
 * <p>用于「结果页」展示「用户在某题中标记过哪些单词」，
 * 也用于「本次练习标记了哪些词」。
 *
 * <p>{@code charStart} / {@code charEnd} 一并返回，前端据此在原文上回显高亮位置。
 *
 * <p><b>为什么必须一起返回 {@code questionId} 与 {@code sourceField}</b>
 * （第 22 次修订补上，此前漏了）：
 * 偏移量本身<b>不自带锚点</b>。{@code charStart=5} 究竟是
 * 「第 3 题题干的第 5 个字符」，还是「文章正文的第 5 个字符」？
 * 只给偏移量，前端无法把高亮画回正确的那段文本上 ——
 * 而「在原文上回显标记」正是这个接口存在的理由。
 * 因此偏移量必须和它所属的「哪道题 + 哪段文本」一起给。
 *
 * @param questionId  标记时所在的题目 id；在文章正文上划词时为 {@code null}
 * @param sourceField 文本来源：{@code PASSAGE} / {@code STEM} / {@code OPTION} / {@code SOURCE_TEXT}
 */
@Schema(description = "一条划词记录")
public record MarkedWordResponse(
        @Schema(description = "标记时所在的题目 id；文章正文上划词时为 null") Long questionId,
        @Schema(description = "文本来源", example = "STEM") String sourceField,
        @Schema(description = "原文形式", example = "running") String surfaceForm,
        @Schema(description = "归一化原形", example = "run") String normalizedForm,
        @Schema(description = "命中的词条 id；词典未收录时为 null") Long wordId,
        @Schema(description = "所在句子快照") String sentence,
        @Schema(description = "文本中的起始下标（0 基，含）") Integer charStart,
        @Schema(description = "结束下标（不含）") Integer charEnd
) {

    public static MarkedWordResponse from(UserWordMark mark) {
        return new MarkedWordResponse(
                mark.getQuestionId(),
                mark.getSourceField(),
                mark.getSurfaceForm(),
                mark.getNormalizedForm(),
                mark.getWordId(),
                mark.getSentence(),
                mark.getCharStart(),
                mark.getCharEnd());
    }
}
