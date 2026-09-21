package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.Passage;

/**
 * {@code passage} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。出题时「按难度/题材筛选文章」
 * 之类的条件查询，在实现组卷功能时再补。
 */
@Mapper
public interface PassageMapper extends BaseMapper<Passage> {
}
