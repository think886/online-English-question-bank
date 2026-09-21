package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.Question;

/**
 * {@code question} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 * 「按 passage_id 取出该文章下的所有题目并按 seq 排序」是组卷时的核心查询，
 * 用 MyBatis-Plus 的 {@code QueryWrapper} 即可表达，无需手写 SQL：
 *
 * <pre>{@code
 * questionMapper.selectList(
 *     Wrappers.<Question>lambdaQuery()
 *         .eq(Question::getPassageId, passageId)
 *         .orderByAsc(Question::getSeq));
 * }</pre>
 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
