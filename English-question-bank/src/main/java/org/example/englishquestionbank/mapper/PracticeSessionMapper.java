package org.example.englishquestionbank.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.englishquestionbank.dto.SessionSummary;
import org.example.englishquestionbank.dto.query.PracticeHistoryItem;
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

    /**
     * 练习历史<b>分页</b>查询（带来源文章标题）。
     *
     * <p><b>为什么要 JOIN {@code passage}</b>：列表里只显示「阅读练习 · 2 题 · 1 分」
     * 无法让人回忆起是哪一篇；带上标题（如 {@code Community Gardens}）才有辨识度。
     * 因为是多表 JOIN，按项目规范进 XML。
     *
     * <p>注意翻译题练习的 {@code passage_id} 为 NULL，因此必须用
     * {@code LEFT JOIN}，否则翻译练习会被整条过滤掉。
     *
     * @param page   分页参数，由 MyBatis-Plus 插件读取并回填 total
     * @param userId 用户 id
     * @return 分页结果；{@code resultType} 为 {@code dto.query.PracticeHistoryItem}，XML 里写全限定名
     */
    IPage<PracticeHistoryItem> selectHistoryPage(IPage<PracticeHistoryItem> page,
                                                 @Param("userId") Long userId);
}
