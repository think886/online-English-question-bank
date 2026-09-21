package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 翻译题评分明细实体，对应表 {@code translation_grading}。
 *
 * <p><b>为什么和 {@code answer_record} 分开</b>：{@code answer_record} 只保留**结论**
 * （得分、判分人、判分时间），而这里保存**过程**——多维得分、点评、LLM 原始返回。
 * 好处是同一份答案可以反复重评并保留历史，便于回溯提示词效果。
 *
 * <p>{@link #isCurrent} 标记哪一条是当前生效的评分：重评时把旧记录置 0、新记录置 1。
 *
 * <p>{@link #sentenceFeedback} 与 {@link #rawResponse} 是 MySQL 的 JSON 列，
 * 实体里映射为 {@code String}（JDBC 驱动直接把 JSON 读成字符串）。
 */
@Data
@TableName("translation_grading")
public class TranslationGrading {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long answerRecordId;

    /** AI / SELF / TEACHER。 */
    private String graderType;

    /** 模型名或批改人。 */
    private String graderName;

    /** 提示词版本，便于效果回溯。 */
    private String promptVersion;

    /** 准确度得分。 */
    private BigDecimal accuracyScore;

    /** 流畅度得分。 */
    private BigDecimal fluencyScore;

    /** 完整度得分。 */
    private BigDecimal completenessScore;

    private BigDecimal totalScore;

    private BigDecimal maxScore;

    /** 总评。 */
    private String comment;

    /** 逐句点评 / 参考译法（JSON）。 */
    private String sentenceFeedback;

    /** LLM 原始返回（JSON）。 */
    private String rawResponse;

    /** 1=当前生效的评分，0=历史评分。 */
    private Integer isCurrent;

    private LocalDateTime createdAt;
}
