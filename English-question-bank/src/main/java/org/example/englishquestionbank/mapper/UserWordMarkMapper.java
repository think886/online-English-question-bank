package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.UserWordMark;

/**
 * {@code user_word_mark} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 *
 * <p><b>⚠ 写入时不要触碰生成列</b>：{@code session_key} / {@code question_key} 是
 * VIRTUAL 生成列，实体刻意没有映射它们（见 {@link UserWordMark} 的类注释），
 * 因此常规的 {@code insert(mark)} 不会出问题。切勿手工拼 SQL 去写这两列。
 */
@Mapper
public interface UserWordMarkMapper extends BaseMapper<UserWordMark> {
}
