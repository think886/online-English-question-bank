package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户生词本实体，对应表 {@code user_vocabulary}。
 *
 * <p>这是 {@code user_word_mark} 的**聚合去重视图**——前者记录每一次划词事件，
 * 这里按 {@code (user_id, normalized_form)} 去重，维护累计标记次数与复习状态。
 * 由标记接口在写入 {@code user_word_mark} 时同步 upsert 维护。
 *
 * <p>{@link #translation} 是**标记当时抓到的释义快照**，不是外键到 {@code word} 表。
 * 这样即使词典后续更新，用户看到的释义也不会突然变化。
 *
 * <p>{@link #mastery} / {@link #nextReviewAt} / {@link #reviewCount} 是留给
 * 艾宾浩斯复习功能的字段，当前阶段只建结构，暂未实现业务逻辑。
 */
@Data
@TableName("user_vocabulary")
public class UserVocabulary {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 归一化原形（小写），与 user_id 组成唯一键。 */
    private String normalizedForm;

    /** 命中词典时的词条 id。 */
    private Long wordId;

    private String displayForm;

    /** 标记时抓到的释义快照。 */
    private String translation;

    /** 累计标记次数。 */
    private Integer markCount;

    private LocalDateTime firstMarkedAt;

    private LocalDateTime lastMarkedAt;

    /** 0=陌生 1=模糊 2=已掌握。 */
    private Integer mastery;

    private Integer reviewCount;

    /** 下次复习时间（留给艾宾浩斯曲线）。 */
    private LocalDateTime nextReviewAt;

    /** 用户笔记。 */
    private String note;
}
