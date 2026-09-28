package org.example.englishquestionbank.service;

import org.example.englishquestionbank.dto.AnswerResult;
import org.example.englishquestionbank.dto.PageResult;
import org.example.englishquestionbank.dto.query.WrongQuestionItem;
import org.example.englishquestionbank.entity.AnswerRecord;

import java.util.List;

/**
 * 作答服务 —— 提交答案、自动判分、查询作答记录。
 *
 * <p><b>当前范围</b>：
 * <ul>
 *   <li>阅读题走<b>自动判分</b>（比对正确选项）</li>
 *   <li>翻译题只把 {@code grading_status} 置为 {@code PENDING} 并返回，
 *       <b>不调用 LLM 评分</b> —— 那部分尚未实现（见 {@code docs/backlog.md} B-05）</li>
 * </ul>
 */
public interface AnswerService {

    /**
     * 提交一道题的答案。整个过程在一个事务内。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>校验会话存在、属于该用户、且状态为 {@code IN_PROGRESS}</li>
     *   <li>校验该题确实在本次会话的题目快照里</li>
     *   <li>按题型判分：阅读题比对正确选项；翻译题置 {@code PENDING}</li>
     *   <li>查询上一次的作答记录 —— 用于判断是否首次、以及计算统计差值</li>
     *   <li>upsert 作答记录（唯一键 {@code session_id + question_id}，语义是<b>覆盖</b>）</li>
     *   <li>按<b>差值</b>调整会话统计（不是固定 +1，见下）</li>
     *   <li>把该题在会话快照里的状态置为 {@code ANSWERED}</li>
     * </ol>
     *
     * <p><b>为什么统计要用差值</b>：用户可能改答案。
     * 若把「答对」改成「答错」时只做 {@code +1} 而不减回去，
     * 会话里会留下一个永远消不掉的对题数。因此增量是 {@code newValue - oldValue}，
     * 可能为负。
     *
     * @param userId     用户 id
     * @param sessionId  会话 id
     * @param questionId 题目 id
     * @param userAnswer 阅读题填选项 key（A/B/C/D）；翻译题填用户译文
     * @return 判分结果
     * @throws IllegalArgumentException 会话/题目不存在，或答案格式非法
     * @throws IllegalStateException    会话不属于该用户、已结束，或该题不在本次会话中
     */
    AnswerResult submitAnswer(Long userId, Long sessionId, Long questionId, String userAnswer);

    /**
     * 查询某次会话的全部作答记录。
     *
     * @param sessionId 会话 id
     * @return 作答记录列表
     */
    List<AnswerRecord> listBySession(Long sessionId);

    /**
     * 错题本：取某用户答错过的题，按作答时间倒序。
     *
     * <p>不需要额外的表 —— {@code answer_record WHERE is_correct = 0} 就是错题本。
     *
     * @param userId 用户 id
     * @param limit  取多少条
     * @return 答错的作答记录
     */
    List<AnswerRecord> listWrongByUser(Long userId, int limit);

    /**
     * 错题本<b>分页</b>查询 —— 带题干、正确答案、解析、来源文章。
     *
     * <p><b>关键约束：只取已交卷会话里的错题。</b>
     * 练习进行中的错题不会进错题本，否则这个接口就成了
     * 「绕开结果页提前看答案」的入口（违反红线 2）。
     * 过滤条件写在 {@code AnswerRecordMapper.xml} 的 SQL 里。
     *
     * <p>翻译题在评分完成前 {@code is_correct} 为 {@code NULL}，
     * 那种「还没判分」的记录既不算对也不算错，不会出现在错题本里。
     *
     * <p>页码与每页条数的归一化规则同 {@code WordMarkService#listVocabularyPage}。
     *
     * @param userId 用户 id
     * @param page   页码，从 1 开始
     * @param size   每页条数
     * @return 分页结果，按作答时间倒序
     * @throws IllegalArgumentException userId 为空
     */
    PageResult<WrongQuestionItem> listWrongPage(Long userId, long page, long size);
}
