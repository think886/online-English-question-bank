package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.QuestionOption;

import java.util.List;

/**
 * 练习中的一道题。阅读题与翻译题共用这个结构，靠字段的有无区分。
 *
 * <p><b>⚠ 三道刻意的省略</b>（都在练习<b>开始</b>时生效，交卷后的结果页另给）：
 * <ul>
 *   <li>阅读题不返回 {@code analysis}（解析）</li>
 *   <li>翻译题不返回 {@code referenceAnswer}（参考译文）</li>
 *   <li>选项里不含 {@code isCorrect}</li>
 * </ul>
 *
 * @param sourceText 翻译题：待翻译的中文原文；阅读题为 {@code null}
 * @param options    阅读题：选项列表（按 seq 排序）；翻译题为 {@code null}
 */
@Schema(description = "练习题（阅读或翻译）")
public record QuestionResponse(
        Long id,
        @Schema(description = "READING / TRANSLATION") String questionType,
        Integer seq,
        @Schema(description = "题干 / 作答要求") String stem,
        Integer score,
        @Schema(description = "翻译题：待翻译的原文；阅读题为 null") String sourceText,
        List<OptionResponse> options
) {

    public static QuestionResponse from(Question question, List<QuestionOption> options) {
        boolean reading = "READING".equals(question.getQuestionType());
        return new QuestionResponse(
                question.getId(),
                question.getQuestionType(),
                question.getSeq(),
                question.getStem(),
                question.getScore(),
                // 翻译题只给「待翻译原文」，不给参考译文
                reading ? null : question.getSourceText(),
                // 阅读题才给选项；选项里不含 isCorrect
                reading
                        ? (options == null ? List.of()
                           : options.stream().map(OptionResponse::from).toList())
                        : null);
    }
}
