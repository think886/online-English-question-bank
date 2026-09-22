package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.entity.Word;

import java.util.List;

/**
 * {@code word} 表的 Mapper。
 *
 * <p><b>继承 {@link BaseMapper} 就自动获得全部单表 CRUD</b>：
 * {@code insert}、{@code selectById}、{@code selectList}、{@code updateById}、
 * {@code deleteById}、{@code selectCount} 等，一行 SQL 都不用写。
 *
 * <p><b>本接口只声明「构造器表达不了」的方法</b>，它们的 SQL 写在
 * {@code src/main/resources/mapper/WordMapper.xml}。
 *
 * <p>按项目规范（见根目录 {@code AGENTS.md}）：
 * <ul>
 *   <li><b>简单操作</b>（单表等值/范围查询、排序、条件更新删除）→ 直接用
 *       {@code Wrappers.lambdaQuery()} 等在 Service 层表达，<b>不在本接口声明方法</b>。
 *       例如「按 headword 查词」就属于这一类。</li>
 *   <li><b>复杂操作</b>（LIMIT、JOIN、动态 SQL、批量、upsert、函数聚合）→ 写进 XML。</li>
 *   <li><b>禁止</b>用 {@code @Select} / {@code @Update} 等注解写 SQL。</li>
 * </ul>
 */
@Mapper
public interface WordMapper extends BaseMapper<Word> {

    /**
     * 按词频升序取前 N 条（序号越小越高频）。
     *
     * <p>之所以进 XML 而不是用构造器：构造器要限制条数只能靠
     * {@code .last("LIMIT " + n)} 做字符串拼接，存在注入风险。
     *
     * @param limit 取多少条
     * @return 词频最靠前的若干词条；已过滤掉 {@code frq IS NULL}（未进当代语料库词频榜）的条目
     */
    List<Word> selectTopByFrequency(@Param("limit") int limit);

    /**
     * 统计带有某个考试标签的词条数量，例如统计考研词汇。
     *
     * <p><b>为什么要写成 {@code CONCAT(' ', tag, ' ') LIKE '% ky %'}</b>：
     * {@code tag} 列存的是空格分隔的标签串（如 {@code "cet4 cet6 ky"}）。
     * 直接写 {@code LIKE '%ky%'} 会误匹配「任何位置含有 ky 子串」的值；
     * 先给两边补上空格，再用「空格 + 标签 + 空格」匹配，才是真正的按词边界匹配。
     *
     * <p>该查询带前置通配符、无法走索引，属全表扫描；word 表仅数万行，可接受。
     *
     * @param tag 单个标签，如 ky（考研）、cet6、toefl
     * @return 匹配的词条数
     */
    long countByExamTag(@Param("tag") String tag);

    /**
     * <b>反查词形</b>：给定一个变形形式，找出它所属的原形词条。
     *
     * <p><b>为什么需要它</b>：ECDICT 把变形形式记录在**原形词条**的
     * {@code exchange} 字段里，但**不为每个变形都建立独立词条**。实测：
     * <pre>
     *   abandon   → exchange 含 i:abandoning / 3:abandons，但词典里查不到 abandoning、abandons
     *   abandoned → 反而有独立词条（exchange = 0:abandon/...）
     *   gave      → 查不到（give 的 exchange 里记着 p:gave）
     * </pre>
     * 因此只靠正向查词会让 {@code abandoning} 归一到它自己，
     * 而生词本里 {@code run}/{@code running} 能合并、{@code abandon}/{@code abandoning} 却不能，
     * 行为不一致。反查可以补齐这一类。
     *
     * <p>SQL 中的两处边界保护：{@code %:abandon/%} 不会误匹配到 {@code :abandoned}；
     * 补末尾的 {@code /} 让 exchange 的最后一段也能被匹配。
     *
     * <p>⚠️ 该查询是全表扫描，性能问题已登记为待办 <b>B-01</b>（见 {@code docs/backlog.md}）。
     *
     * @param form 变形形式，小写，如 abandoning
     * @return 拥有该变形形式的原形词条；查无结果时返回 null
     */
    Word selectByExchangeForm(@Param("form") String form);
}
