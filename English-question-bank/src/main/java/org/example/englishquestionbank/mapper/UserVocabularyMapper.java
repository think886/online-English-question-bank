package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.UserVocabulary;

/**
 * {@code user_vocabulary} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 *
 * <p>生词本由标记接口维护：每次划词时按 {@code (user_id, normalized_form)} 查找，
 * 存在则 {@code mark_count + 1} 并更新 {@code last_marked_at}，不存在则插入。
 * 这是「先查后写」的 upsert 逻辑，放在 Service 层实现。
 */
@Mapper
public interface UserVocabularyMapper extends BaseMapper<UserVocabulary> {
}
