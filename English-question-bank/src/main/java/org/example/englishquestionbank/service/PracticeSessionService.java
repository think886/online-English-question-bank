package org.example.englishquestionbank.service;

import org.example.englishquestionbank.entity.PracticeSession;

/**
 * 练习会话服务 —— 管理一次练习的「开始 → 作答中 → 交卷」生命周期。
 *
 * <p><b>当前范围</b>：只支持「按文章开始一次练习」，即一次会话 = 一篇文章及其下 N 道选择题。
 * <b>不含组卷算法</b>（随机抽题、按难度选题等），也不含翻译题会话
 * （翻译题的 {@code passage_id} 为空，如何组卷尚未讨论）。
 *
 * <p><b>{@code finishSession} 尚未实现</b>：交卷需要用作答记录做一次聚合重算，
 * 依赖 {@code AnswerService} 先落地。计划在实现作答链路之后补上，
 * 详见 {@code docs/service-layer.md} 第四节的步骤 7。
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
     * 按主键查询会话。
     *
     * @param sessionId 会话 id
     * @return 会话实体；不存在时返回 {@code null}
     */
    PracticeSession findById(Long sessionId);
}
