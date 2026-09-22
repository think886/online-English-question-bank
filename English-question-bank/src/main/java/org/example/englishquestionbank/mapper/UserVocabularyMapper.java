package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.entity.UserVocabulary;

import java.util.List;

/**
 * {@code user_vocabulary} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得全部单表 CRUD；复杂 SQL 写在
 * {@code src/main/resources/mapper/UserVocabularyMapper.xml}。
 *
 * <p>生词本由划词接口维护：每次标记时按 {@code (user_id, normalized_form)} upsert ——
 * 首次插入 {@code mark_count = 1}，之后每次 {@code +1}。
 */
@Mapper
public interface UserVocabularyMapper extends BaseMapper<UserVocabulary> {

    /**
     * 生词本累加写入：不存在则插入，已存在则 {@code mark_count + 1}。
     *
     * <p>用 SQL 层 upsert 而非「先查后写」：后者的「读出来 +1 再写回」在并发下会丢更新。
     *
     * @param userId         用户 id
     * @param normalizedForm 归一化原形（小写），与 {@code userId} 组成唯一键
     * @param wordId         命中的词条 id，可为 null（词典未收录）
     * @param displayForm    展示形式
     * @param translation    释义快照，可为 null
     * @return 受影响行数
     */
    int upsertMark(@Param("userId") Long userId,
                   @Param("normalizedForm") String normalizedForm,
                   @Param("wordId") Long wordId,
                   @Param("displayForm") String displayForm,
                   @Param("translation") String translation);

    /**
     * 取某用户最近的生词，按最后标记时间倒序。
     *
     * <p>需要 {@code LIMIT}，因此进 XML（构造器只能靠字符串拼接实现，有注入风险）。
     *
     * @param userId 用户 id
     * @param limit  取多少条
     * @return 生词列表
     */
    List<UserVocabulary> selectByUser(@Param("userId") Long userId, @Param("limit") int limit);
}
