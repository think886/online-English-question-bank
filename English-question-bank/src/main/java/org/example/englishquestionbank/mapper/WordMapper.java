package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.englishquestionbank.entity.Word;

import java.util.List;

/**
 * {@code word} 表的 Mapper。
 *
 * <p><b>继承 {@link BaseMapper} 就自动获得全部单表 CRUD</b>：
 * {@code insert}、{@code selectById}、{@code selectList}、{@code updateById}、
 * {@code deleteById}、{@code selectCount} 等，一行 SQL 都不用写。
 * MyBatis-Plus 在启动时通过动态代理为这些方法生成 SQL。
 *
 * <p>下面用 {@code @Select} 额外声明了三个查询，目的是验证「自定义 SQL 也能跑通」，
 * 其中 {@link #selectByHeadword} 是划词查释义的核心方法。
 *
 * <p><b>关于 {@code @Mapper} 注解</b>：MyBatis-Plus 默认会自动扫描主启动类所在包
 * （{@code org.example.englishquestionbank}）及其子包，所以本接口即使不加注解也能被注册。
 * 这里显式标注是为了让意图明确，并且以后即使包结构变动也不容易漏扫。
 */
@Mapper
public interface WordMapper extends BaseMapper<Word> {

    /**
     * 按词条原形精确查词 —— 划词功能的查询入口。
     *
     * <p>用 {@code #{headword}}（预编译占位符，即 PreparedStatement 的 {@code ?}）
     * 而不是 {@code ${}}（字符串拼接），可以天然防 SQL 注入。
     *
     * @param headword 小写词条原形，如 run
     * @return 匹配的词条；查无结果时返回 null
     */
    @Select("SELECT * FROM word WHERE headword = #{headword} LIMIT 1")
    Word selectByHeadword(@Param("headword") String headword);

    /**
     * <b>反查词形</b>：给定一个变形形式，找出它所属的原形词条。
     *
     * <p><b>为什么需要这个方法</b>：ECDICT 把变形形式记录在**原形词条**的
     * {@code exchange} 字段里，但**不为每个变形都建立独立词条**。实测：
     * <pre>
     *   abandon   → exchange 含 i:abandoning / 3:abandons，但词典里查不到 abandoning、abandons
     *   abandoned → 反而有独立词条（exchange = 0:abandon/...）
     *   gave      → 查不到（give 的 exchange 里记着 p:gave）
     * </pre>
     * 因此只靠 {@link #selectByHeadword} 会让 {@code abandoning} 归一到它自己，
     * 而生词本里 {@code run}/{@code running} 能合并、{@code abandon}/{@code abandoning} 却不能，
     * 行为不一致。反查可以补齐这一类。
     *
     * <p><b>为什么要 {@code CONCAT(exchange, '/')} 和两端的分隔符</b>：
     * {@code exchange} 的格式是 {@code 类型:形式/类型:形式/...}，形式之间以 {@code /} 分隔。
     * 直接写 {@code LIKE '%:abandon/%'} 已经避免了匹配到 {@code :abandoned}（因为后面跟的是 e 不是 /）；
     * 补末尾的 {@code /} 是为了让**最后一段**也能被匹配到。
     *
     * <p>注意：该查询带前置通配符、无法走索引，是全表扫描。word 表仅数万行、
     * 且划词是用户主动触发（非高并发），可接受。若以后成为瓶颈，可在导入时
     * 额外生成一张「形式 → 原形」的反向索引表。
     *
     * @param form 变形形式，小写，如 abandoning
     * @return 拥有该变形形式的原形词条；查无结果时返回 null
     */
    @Select("SELECT * FROM word "
          + "WHERE exchange IS NOT NULL "
          + "  AND CONCAT(exchange, '/') LIKE CONCAT('%:', #{form}, '/%') "
          + "LIMIT 1")
    Word selectByExchangeForm(@Param("form") String form);

    /**
     * 按词频升序取前 N 条（序号越小越高频）。
     *
     * <p>过滤掉 {@code frq IS NULL} 的词条 —— 它们在 ECDICT 里未进入当代语料库词频榜。
     *
     * @param limit 取多少条
     */
    @Select("SELECT * FROM word WHERE frq IS NOT NULL ORDER BY frq ASC LIMIT #{limit}")
    List<Word> selectTopByFrequency(@Param("limit") int limit);

    /**
     * 统计带有某个考试标签的词条数量，例如统计考研词汇。
     *
     * <p><b>为什么要写成 {@code CONCAT(' ', tag, ' ') LIKE '% ky %'}</b>：
     * {@code tag} 列存的是空格分隔的标签串（如 {@code "cet4 cet6 ky"}）。
     * 直接写 {@code LIKE '%ky%'} 会误匹配「任何位置含有 ky 子串」的值；
     * 先给两边补上空格，再用「空格 + 标签 + 空格」去匹配，才是真正的按词边界匹配。
     *
     * <p>注意：这种查询无法走索引（前置通配符），但 word 表只有几万行，全表扫描可接受。
     *
     * @param tag 单个标签，如 ky（考研）、cet6、toefl
     * @return 匹配的词条数
     */
    @Select("SELECT COUNT(*) FROM word WHERE CONCAT(' ', tag, ' ') LIKE CONCAT('% ', #{tag}, ' %')")
    long countByExamTag(@Param("tag") String tag);
}
