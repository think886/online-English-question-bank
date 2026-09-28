package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.query.VocabItem;

import java.time.LocalDateTime;

/**
 * 生词本条目（对外契约）。
 *
 * <p>与内部的 {@code dto.query.VocabItem} 字段一致但<strong>是两个类</strong> ——
 * 内部行映射类型随时可能因为 SQL 改动而增删字段，对外契约不能跟着被动变化
 * （见 {@code docs/api-layer.md} 第六节 DTO 分层）。
 */
@Schema(description = "生词本条目")
public record VocabItemResponse(
        @Schema(description = "归一化原形", example = "run") String normalizedForm,
        @Schema(description = "展示形式") String displayForm,
        @Schema(description = "中文释义；词典未收录时为 null") String translation,
        @Schema(description = "英式音标") String phoneticUk,
        @Schema(description = "美式音标；ECDICT 无此数据，当前恒为 null") String phoneticUs,
        @Schema(description = "词性，如 n. / v.") String partOfSpeech,
        @Schema(description = "考试大纲标签，空格分隔：cet4 cet6 ky 等") String tag,
        @Schema(description = "累计标记次数") Integer markCount,
        @Schema(description = "掌握程度 0~5，预留给间隔复习") Integer mastery,
        LocalDateTime firstMarkedAt,
        LocalDateTime lastMarkedAt
) {

    public static VocabItemResponse from(VocabItem item) {
        return new VocabItemResponse(
                item.getNormalizedForm(),
                item.getDisplayForm(),
                item.getTranslation(),
                item.getPhoneticUk(),
                item.getPhoneticUs(),
                item.getPartOfSpeech(),
                item.getTag(),
                item.getMarkCount(),
                item.getMastery(),
                item.getFirstMarkedAt(),
                item.getLastMarkedAt());
    }
}
