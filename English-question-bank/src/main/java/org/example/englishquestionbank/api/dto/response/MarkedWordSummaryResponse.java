package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.MarkedWordSummary;

/**
 * 结果页里「本次标记的单词及其释义」。
 *
 * <p>这正是项目最初的核心需求之一 —— 用户做完题后，要能看到自己标记过哪些词、分别是什么意思。
 *
 * <p><b>注意这里没有 {@code questionId} / {@code sourceField} / 偏移量</b>，
 * 这是刻意的：本结构按<b>原形去重</b>，同一个词可能在多处（不同题、正文、选项）被标，
 * 锚点并不唯一。要定位到具体位置，请用单题明细里的 {@code AnswerDetailResponse.markedWords}
 * 或 {@code GET /api/practices/{id}/marks}。
 */
@Schema(description = "本次标记的单词（按原形去重，含释义）")
public record MarkedWordSummaryResponse(
        @Schema(description = "归一化原形", example = "run") String normalizedForm,
        @Schema(description = "展示形式") String displayForm,
        @Schema(description = "中文释义；词典未收录时为 null") String translation,
        @Schema(description = "命中的词条 id；未收录时为 null") Long wordId,
        @Schema(description = "在本次会话中被标记的次数") int markCount,
        @Schema(description = "该词在文本中最靠前的那次标记的句子快照") String firstSentence
) {

    public static MarkedWordSummaryResponse from(MarkedWordSummary summary) {
        return new MarkedWordSummaryResponse(
                summary.normalizedForm(),
                summary.displayForm(),
                summary.translation(),
                summary.wordId(),
                summary.markCount(),
                summary.firstSentence());
    }
}
