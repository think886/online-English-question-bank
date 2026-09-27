package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.entity.Passage;

/**
 * 练习中的文章。
 *
 * <p><b>⚠ 刻意不返回 {@code passage.translation}（全文译文）</b> ——
 * 这是练习<b>开始</b>时的响应，把全文译文一起给出，等于提前把答案交给用户。
 */
@Schema(description = "练习文章（不含全文译文）")
public record PassageResponse(
        Long id,
        String title,
        String content,
        String source,
        String category,
        Integer difficulty,
        Integer wordCount
) {

    public static PassageResponse from(Passage passage) {
        if (passage == null) {
            return null;   // 翻译题会话没有文章
        }
        return new PassageResponse(
                passage.getId(),
                passage.getTitle(),
                passage.getContent(),
                passage.getSource(),
                passage.getCategory(),
                passage.getDifficulty(),
                passage.getWordCount());
    }
}
