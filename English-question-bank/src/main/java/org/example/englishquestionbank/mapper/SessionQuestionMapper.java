package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.entity.SessionQuestion;

import java.util.List;

/**
 * {@code session_question} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得全部单表 CRUD；复杂 SQL 写在
 * {@code src/main/resources/mapper/SessionQuestionMapper.xml}。
 * 按项目规范（根目录 {@code AGENTS.md}）：
 * <b>简单操作用构造器，复杂操作写 XML，禁止用 {@code @Select} 等注解</b>。
 */
@Mapper
public interface SessionQuestionMapper extends BaseMapper<SessionQuestion> {

    /**
     * 批量插入会话题目快照。
     *
     * <p>开始一次练习时，会话下所有题目要一次性写入。用单条
     * {@code INSERT ... VALUES (...),(...)} 完成，而不是在 Service 里循环调用
     * {@code insert()} —— 后者会产生 N 次数据库往返。
     *
     * @param items 待插入的快照列表，不能为空
     * @return 实际插入行数
     */
    int insertBatch(@Param("items") List<SessionQuestion> items);

    /**
     * 把某道题在本次会话中的「已标记词次」原子自增 1。
     *
     * <p>之所以用 SQL 自增而不是 Java 里读改写：并发下后者会丢更新。
     *
     * @param sessionId  会话 id
     * @param questionId 题目 id
     * @return 受影响行数；题目不属于该会话时为 0
     */
    int incrementMarkedWordCount(@Param("sessionId") Long sessionId,
                                 @Param("questionId") Long questionId);
}
