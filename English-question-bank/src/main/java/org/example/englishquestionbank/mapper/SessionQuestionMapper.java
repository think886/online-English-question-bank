package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.englishquestionbank.entity.SessionQuestion;

/**
 * {@code session_question} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 即获得全部单表 CRUD。
 * 开始练习时批量插入本次抽到的题目快照，交卷时更新每题状态。
 */
@Mapper
public interface SessionQuestionMapper extends BaseMapper<SessionQuestion> {
}
