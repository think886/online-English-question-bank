package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.dto.SessionSummary;
import org.example.englishquestionbank.entity.PracticeSession;

/**
 * {@code practice_session} 表的 Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得全部单表 CRUD；复杂 SQL 写在
 * {@code src/main/resources/mapper/PracticeSessionMapper.xml}。
 */
@Mapper
public interface PracticeSessionMapper extends BaseMapper<PracticeSession> {

    /**
     * 调整会话的进度统计（已答题量 / 答对题量 / 得分）。
     *
     * <p><b>三个参数都是「差值」而不是固定 +1</b>：
     * <ul>
     *   <li>首次作答：{@code answeredDelta = 1}</li>
     *   <li>重复提交：{@code answeredDelta = 0}（不能重复计数）</li>
     *   <li>改答案：{@code correctDelta} / {@code scoreDelta} 可能为<b>负数</b>
     *       —— 用户把答对的题改成答错，对题数必须减回去</li>
     * </ul>
     * 调用方负责算出 {@code newValue - oldValue}。
     *
     * @param sessionId      会话 id
     * @param answeredDelta  已答题量增量
     * @param correctDelta   答对题量增量（可负）
     * @param scoreDelta     得分增量（可负）
     * @return 受影响行数
     */
    int incrementProgress(@Param("sessionId") Long sessionId,
                          @Param("answeredDelta") int answeredDelta,
                          @Param("correctDelta") int correctDelta,
                          @Param("scoreDelta") int scoreDelta);

    /**
     * 聚合重算某次会话的作答汇总（已答题量 / 答对题量 / 总分）。
     *
     * <p>交卷时用它覆盖中途累加的增量 —— 增量是性能优化，聚合是正确性保证。
     * 只要任何一次增量漏算或重复累加，最终成绩就会永久错；重算则一定与
     * {@code answer_record} 的事实一致。
     *
     * @param sessionId 会话 id
     * @return 汇总结果；会话下没有任何作答时各项为 0
     */
    SessionSummary selectAnswerSummary(@Param("sessionId") Long sessionId);
}
