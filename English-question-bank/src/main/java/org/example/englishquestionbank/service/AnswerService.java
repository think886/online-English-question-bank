package org.example.englishquestionbank.service;

import org.example.englishquestionbank.dto.AnswerResult;
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
}
