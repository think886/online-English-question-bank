package org.example.englishquestionbank.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单词词典实体，对应数据库表 {@code word}。
 *
 * <p><b>映射约定（本项目统一遵循）</b>：类上标 {@link TableName}，主键标 {@link TableId}，
 * 其余字段依赖「下划线列名 → 驼峰属性名」的自动映射（已在 application.yml 中开启
 * {@code mybatis-plus.configuration.map-underscore-to-camel-case=true}）。
 * 只有当列名与属性名对不上时，才补 {@code @TableField("列名")}。
 * 本表 19 个字段的命名都能自动对应，因此没有一处 {@code @TableField}。
 *
 * <p><b>为什么主键要显式写 {@code type = IdType.AUTO}</b>：全局配置里已经设了
 * {@code id-type: auto}，这里再写一次是冗余的，但属于「故意冗余」——
 * 本表主键是 AUTO_INCREMENT，一旦有人改动全局策略，显式声明能保证这张表不受影响。
 *
 * <p><b>字段类型选择说明</b>：
 * <ul>
 *   <li>{@code BIGINT UNSIGNED} → {@code Long}</li>
 *   <li>{@code TINYINT} → {@code Integer}（不用 Boolean：部分表存在 0/1/2 三态语义）</li>
 *   <li>{@code JSON} → {@code String}（JDBC 驱动直接把 JSON 列读成字符串）</li>
 *   <li>{@code DATETIME} → {@code LocalDateTime}</li>
 * </ul>
 *
 * <p><b>时间字段刻意不用 MyBatis-Plus 的自动填充</b>：数据库已经定义了
 * {@code DEFAULT CURRENT_TIMESTAMP} 与 {@code ON UPDATE CURRENT_TIMESTAMP}。
 * 两套机制并存会导致「这个值到底是谁写的」无法排查，因此统一交给数据库负责。
 */
@Data
@TableName("word")
public class Word {

    /** 主键，对应 AUTO_INCREMENT 自增列。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 词条原形（小写），如 run。词典的去重键，有唯一索引。 */
    private String headword;

    /** 展示形式，保留原始大小写，如 Run。 */
    private String displayForm;

    /** 英式音标。ECDICT 只提供一套音标，因此实际偏英式。 */
    private String phoneticUk;

    /** 美式音标。ECDICT 无此数据，当前全部为 NULL，留待后续补充。 */
    private String phoneticUs;

    /** 词性，如 n./v./adj.，由导入脚本从中文释义前缀提取。 */
    private String partOfSpeech;

    /** 中文释义，多个义项用「; 」分隔；lookup_status 为 NOT_FOUND 时为空。 */
    private String translation;

    /**
     * 词形变化，格式形如 {@code d:did/p:done/i:doing/3:does/s:复数/0:lemma}。
     * 其中 {@code 0:} 段给出该词的 lemma（原形），是「标记 running 时归一化成 run」的依据。
     */
    private String exchange;

    /** 考试大纲标签，空格分隔：zk/gk/cet4/cet6/ky/toefl/ielts/gre。 */
    private String tag;

    /** BNC 语料库词频序号，越小越高频；NULL 表示未进榜。 */
    private Integer bnc;

    /** 当代语料库词频序号，越小越高频；NULL 表示未进榜。 */
    private Integer frq;

    /**
     * 词典条目的来源状态：
     * LOCAL=本地导入 / API=外部接口回写 / PENDING=待补全 / NOT_FOUND=查无结果。
     * NOT_FOUND 会写入一条空释义记录，用于防止反复请求外部接口（缓存穿透）。
     */
    private String lookupStatus;

    /** 数据来源标识，如 ecdict / youdao / manual。 */
    private String source;

    /** 外部接口原始返回的 JSON，用于以后扩展多义项结构。本批 ECDICT 导入的数据为 NULL。 */
    private String rawJson;

    /** 被查询次数，用于热度排序或缓存淘汰。 */
    private Integer hitCount;

    /** 是否已人工校对：1=是，0=否。 */
    private Integer isVerified;

    /** 释义的抓取/导入时间。 */
    private LocalDateTime fetchedAt;

    /** 记录创建时间，由数据库默认值填充。 */
    private LocalDateTime createdAt;

    /** 记录更新时间，由数据库 ON UPDATE 自动维护。 */
    private LocalDateTime updatedAt;
}
