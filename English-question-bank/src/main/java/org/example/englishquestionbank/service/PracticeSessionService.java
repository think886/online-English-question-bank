package org.example.englishquestionbank.service;

import org.example.englishquestionbank.dto.PracticeContent;
import org.example.englishquestionbank.dto.PracticeResult;
import org.example.englishquestionbank.entity.PracticeSession;

/**
 * 练习会话服务 —— 管理一次练习的「开始 → 作答中 → 交卷」生命周期。
 *
 * <p><b>当前范围</b>：支持两类会话 ——
 * <ul>
 *   <li><b>阅读题</b>：{@link #startSession}，一次会话 = 一篇文章及其下 N 道选择题</li>
 *   <li><b>翻译题</b>：{@link #startTranslationSession}，按题型取题
 *       （翻译题的 {@code passage_id} 为 NULL，无法按文章组卷）</li>
 * </ul>
 * <b>不含组卷算法</b>（随机抽题、按难度选题等）。
 */
public interface PracticeSessionService {

    /**
     * 开始一次练习。
     *
     * <p>执行步骤（整个过程在<strong>一个事务</strong>内）：
     * <ol>
     *   <li>校验用户存在</li>
     *   <li>查出该文章下所有启用题目（{@code passage_id = ? AND status = 1}，按 {@code seq} 排序）；
     *       题目为空则抛异常 —— 不允许开一个空会话</li>
     *   <li>写入 {@code practice_session}，状态 {@code IN_PROGRESS}</li>
     *   <li>把本次抽到的题目<strong>快照</strong>批量写入 {@code session_question}</li>
     * </ol>
     *
     * <p><b>为什么必须事务</b>：若第 3 步成功而第 4 步失败，会留下一个
     * 「有会话但无题目」的僵尸会话，结果页空白且极难排查。
     *
     * <p><b>为什么要落快照</b>：文章事后被编辑（增删题、改题号）时，
     * 历史会话不会错乱。这是 {@code session_question} 表存在的唯一理由。
     *
     * @param userId    用户 id
     * @param passageId 文章 id
     * @return 新建的会话实体，{@code id} 已由 MyBatis-Plus 回填
     * @throws IllegalArgumentException 用户不存在
     * @throws IllegalStateException    该文章下没有可用题目
     */
    PracticeSession startSession(Long userId, Long passageId);

    /**
     * 开始一次<b>翻译题</b>练习。
     *
     * <p><b>为什么需要独立入口</b>：翻译题的 {@code passage_id} 为 NULL
     * （它不属于任何文章），因此无法用 {@link #startSession} 那种「按文章取题」的方式组卷。
     * 这里改为按 {@code question_type = 'TRANSLATION'} 直接取题。
     *
     * <p>与阅读题会话的区别：
     * <table border="1">
     *   <caption>两类会话的对比</caption>
     *   <tr><th></th><th>{@link #startSession}</th><th>{@link #startTranslationSession}</th></tr>
     *   <tr><td>{@code mode}</td><td>{@code READING}</td><td>{@code TRANSLATION}</td></tr>
     *   <tr><td>{@code passage_id}</td><td>文章 id</td><td><b>NULL</b></td></tr>
     *   <tr><td>选题依据</td><td>{@code passage_id = ?}</td><td>{@code question_type = ?}</td></tr>
     * </table>
     *
     * <p><b>不含随机抽题</b>：按 {@code id} 升序取前 N 道，结果确定、可复现。
     * 随机选题属于「组卷算法」，不在当前范围内。
     *
     * @param userId 用户 id
     * @param count  本次练习多少道翻译题，取值范围 1~50
     * @return 新建的会话实体，{@code id} 已回填
     * @throws IllegalArgumentException 用户不存在，或 {@code count} 超出范围
     * @throws IllegalStateException    题库中没有可用的翻译题
     */
    PracticeSession startTranslationSession(Long userId, int count);

    /**
     * 按主键查询会话。
     *
     * @param sessionId 会话 id
     * @return 会话实体；不存在时返回 {@code null}
     */
    PracticeSession findById(Long sessionId);

    /**
     * 交卷，结束一次练习。
     *
     * <p>执行步骤（在一个事务内）：
     * <ol>
     *   <li>校验会话存在、<b>属于该用户</b>、且状态为 {@code IN_PROGRESS} ——
     *       防止重复交卷，也防止交别人的卷</li>
     *   <li><b>聚合重算</b>作答汇总（已答题量 / 答对题量 / 得分），以 {@code answer_record} 为准</li>
     *   <li>统计本次会话标记的<b>去重单词数</b>（{@code COUNT(DISTINCT normalized_form)}）</li>
     *   <li>更新会话：状态置 {@code FINISHED}、填充 {@code finished_at} 与 {@code duration_ms}，
     *       并用第 2、3 步的结果<b>覆盖</b>中途累加的统计值</li>
     * </ol>
     *
     * <p><b>为什么要重算而不是沿用增量</b>：中途每次提交答案都会增量更新统计
     * （为了让前端实时显示进度），那是「性能优化」；交卷时的聚合是「正确性保证」。
     * 只要任何一次增量漏算或重复累加，最终成绩就永久错了；
     * 用事实数据重算一遍，结果一定与作答记录一致。
     *
     * <p>{@code max_score}（本次总分值）<b>不参与重算</b> —— 它在 {@link #startSession}
     * 时按会话内全部题目的分值之和确定，是个固定值；
     * 若改成按已作答题目求和，语义就变成了另一个东西。
     *
     * @param userId    用户 id —— 用于<b>归属校验</b>，防止交别人的卷
     * @param sessionId 会话 id
     * @throws IllegalArgumentException 会话不存在
     * @throws IllegalStateException    会话不属于该用户，或状态不是 {@code IN_PROGRESS}
     *                                  （已交卷或已放弃）
     */
    void finishSession(Long userId, Long sessionId);

    /**
     * 读取一次练习的完整内容（会话 + 文章 + 题目 + 选项）。
     *
     * <p><b>题目按会话快照的顺序返回，而不是按文章当前的题目顺序</b> ——
     * 这正是 {@code session_question} 表存在的意义：文章事后被编辑也不会打乱历史会话。
     *
     * <p>这个方法同时服务「开始练习」与「结果页」两个场景。
     *
     * @param sessionId 会话 id
     * @return 练习内容；翻译题会话的 {@code passage} 为 {@code null}
     * @throws IllegalArgumentException 会话不存在
     */
    PracticeContent loadContent(Long sessionId);

    /**
     * 读取一次练习的<b>结果页数据</b> —— 文章、题目、作答记录、标记词。
     *
     * <p><b>与 {@link #loadContent} 的区别</b>：这个方法会带出<b>正确答案与解析</b>
     * （在响应层通过 {@code AnswerDetailResponse} 暴露），
     * 因此有两条硬性约束：
     * <ol>
     *   <li>会话必须属于 {@code userId} —— 别人的卷不给看</li>
     *   <li>会话状态必须是 {@code FINISHED} —— <b>没交卷就不给看答案</b>，
     *       否则「结果页」就成了作弊入口</li>
     * </ol>
     * 这两条是安全边界，所以放在服务层而不是 Controller ——
     * Controller 只是「取身份、调服务、转 DTO」，不该承载访问规则。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @return 结果页数据
     * @throws IllegalArgumentException 会话不存在
     * @throws IllegalStateException    会话不属于该用户，或尚未交卷
     */
    PracticeResult loadResult(Long userId, Long sessionId);
}
