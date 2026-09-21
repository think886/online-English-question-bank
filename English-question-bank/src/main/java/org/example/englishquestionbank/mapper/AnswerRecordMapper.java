package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.AnswerRecord;

/**
 * {@code answer_record} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 * 错题本查询（{@code is_correct = 0}）与「用户是否做过某题」用
 * {@code QueryWrapper} 即可表达，无需手写 SQL。
 */
@Mapper
public interface AnswerRecordMapper extends BaseMapper<AnswerRecord> {
}
