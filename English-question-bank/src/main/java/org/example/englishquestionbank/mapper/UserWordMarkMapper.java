package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.entity.UserWordMark;

/**
 * {@code user_word_mark} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得全部单表 CRUD；复杂 SQL 写在
 * {@code src/main/resources/mapper/UserWordMarkMapper.xml}。
 *
 * <p><b>⚠ 写入时不要触碰生成列</b>：{@code session_key} / {@code question_key} 是
 * VIRTUAL 生成列，实体刻意没有映射它们（见 {@link UserWordMark} 的类注释），
 * XML 里也不要手工写这两列 —— 唯一键的去重完全由数据库计算。
 */
@Mapper
public interface UserWordMarkMapper extends BaseMapper<UserWordMark> {

    /**
     * 插入一条划词标记；若同一位置已标记过则什么都不做（幂等）。
     *
     * <p><b>不要用本方法的返回值判断「是否新增了行」</b>：
     * {@code ON DUPLICATE KEY UPDATE} 的 affected-rows 语义受 JDBC 的
     * {@code useAffectedRows} 开关影响，Connector/J 默认会开启 {@code CLIENT_FOUND_ROWS}，
     * 把「匹配到但未改变」也计成 1。
     * 需要知道是否新增时，应由调用方在事务内先做存在性查询。
     *
     * @param mark 待插入的标记；{@code id} 无需设置，由数据库自增
     * @return 受影响行数（<b>不可靠</b>，仅用于日志）
     */
    int insertIgnoreDuplicate(@Param("m") UserWordMark mark);

    /**
     * 统计某次会话中标记的「去重单词数」。
     *
     * <p>{@code COUNT(DISTINCT normalized_form)} 与 {@code COUNT(*)} 含义不同：
     * 前者是「多少个不同的词」，后者是「标记了多少次」。
     * {@code practice_session.marked_word_count} 的语义是前者，
     * 因此无法用 {@code +1} 增量维护，只在交卷时算一次。
     *
     * @param sessionId 会话 id
     * @return 去重后的单词数
     */
    int countDistinctMarkedWords(@Param("sessionId") Long sessionId);
}
