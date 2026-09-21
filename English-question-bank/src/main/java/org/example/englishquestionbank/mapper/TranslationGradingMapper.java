package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.TranslationGrading;

/**
 * {@code translation_grading} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 * 重评时需要「把旧评分的 is_current 置 0、新评分置 1」，那是一次事务操作，
 * 放在 Service 层实现。
 */
@Mapper
public interface TranslationGradingMapper extends BaseMapper<TranslationGrading> {
}
