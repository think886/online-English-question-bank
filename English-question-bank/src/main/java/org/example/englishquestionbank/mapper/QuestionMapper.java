package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.entity.Question;

import java.util.List;

/**
 * {@code question} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得全部单表 CRUD；复杂 SQL 写在
 * {@code src/main/resources/mapper/QuestionMapper.xml}。
 *
 * <p>「按 {@code passage_id} 取该文章下的题目并按 {@code seq} 排序」是阅读题组卷的核心查询，
 * 属单表条件查询，用构造器表达即可：
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

    /**
     * 按题型取前 N 道启用题目。
     *
     * <p><b>为什么需要它</b>：翻译题的 {@code passage_id} 为 NULL（不属于任何文章），
     * 无法用「按文章取题」的方式组卷，只能按 {@code question_type} 直接取。
     *
     * <p>结果按 {@code id} 升序，保证确定、可复现。
     * 随机抽题属于「组卷算法」，不在当前范围内。
     *
     * @param questionType 题型，如 {@code TRANSLATION}
     * @param limit        最多取多少道
     * @return 题目列表，按 id 升序
     */
    List<Question> selectByTypeWithLimit(@Param("questionType") String questionType,
                                         @Param("limit") int limit);
}
