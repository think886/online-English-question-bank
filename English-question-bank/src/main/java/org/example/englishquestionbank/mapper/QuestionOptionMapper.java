package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.QuestionOption;

/**
 * {@code question_option} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 * 呈现一道选择题时需要「按 question_id 取选项并按 seq 排序」。
 */
@Mapper
public interface QuestionOptionMapper extends BaseMapper<QuestionOption> {
}
