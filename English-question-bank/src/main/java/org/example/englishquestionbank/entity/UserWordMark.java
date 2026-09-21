package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单词标记原始记录实体，对应表 {@code user_word_mark}。
 *
 * <p><b>一次划词 = 一行</b>。这四维信息缺任何一个，都无法还原「用户在某题中标记过的单词」：
 * <ol>
 *   <li>谁标的 —— {@link #userId}</li>
 *   <li>哪个词 —— {@link #surfaceForm}（原文形式）与 {@link #normalizedForm}（归一化原形）</li>
 *   <li>在哪儿标的 —— {@link #sessionId} / {@link #questionId} / {@link #passageId} / {@link #sourceField}</li>
 *   <li>文本中的位置 —— {@link #charStart} / {@link #charEnd}</li>
 * </ol>
 *
 * <p><b>⚠ 刻意不映射的两个数据库生成列</b>：{@code session_key} 与 {@code question_key}。
 * 它们是 VIRTUAL 生成列，把可空的 {@code session_id} / {@code question_id} 归一为 0，
 * 仅供唯一键 {@code uk_mark_pos} 使用。业务代码永远不需要读写它们；
 * 若映射进实体，MyBatis-Plus 插入时会尝试写入，而 MySQL 对生成列只允许写 DEFAULT，会直接报错。
 *
 * <p>注意本表没有 {@code updated_at} 列 —— 标记是只追加的事件，不会修改。
 */
@Data
@TableName("user_word_mark")
public class UserWordMark {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 所属练习会话；脱离会话的自由阅读为 NULL。 */
    private Long sessionId;

    /** 标记时正在作答的题目。 */
    private Long questionId;

    /** 该词实际所在文章。 */
    private Long passageId;

    /** PASSAGE=文章正文 / STEM=题干 / OPTION=选项 / SOURCE_TEXT=待译原文。 */
    private String sourceField;

    /** 命中词典则为词条 id；未收录为 NULL。 */
    private Long wordId;

    /** 原文形式，如 running。 */
    private String surfaceForm;

    /** 归一化原形（小写），如 run。词典的 exchange 字段给出 0:lemma，可用于归一化。 */
    private String normalizedForm;

    /** 所在句子快照，复习时可看语境。 */
    private String sentence;

    /** 在文本中的起始下标（0 基，含）。 */
    private Integer charStart;

    /** 结束下标（不含）。 */
    private Integer charEnd;

    private LocalDateTime createdAt;
}
