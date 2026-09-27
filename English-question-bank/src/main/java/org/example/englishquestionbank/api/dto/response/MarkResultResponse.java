package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.MarkResult;

/**
 * 划词标记的响应体。
 *
 * <p><b>返回 {@code translation} 是刻意的</b>：让前端一次调用就拿到释义，
 * 不必再单独查一次词典。用户划词后的即时反馈是这个系统体验的关键一环。
 */
@Schema(description = "划词标记结果")
public record MarkResultResponse(
        Long markId,
        @Schema(description = "归一化后的原形", example = "run") String normalizedForm,
        @Schema(description = "命中的词条 id；词典未收录时为 null") Long wordId,
        @Schema(description = "中文释义；词典未收录时为 null") String translation,
        @Schema(description = "true 表示该位置之前已标记过，本次为空操作") boolean alreadyMarked
) {

    public static MarkResultResponse from(MarkResult result) {
        return new MarkResultResponse(
                result.markId(),
                result.normalizedForm(),
                result.wordId(),
                result.translation(),
                result.alreadyMarked());
    }
}
