package org.example.englishquestionbank.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.dto.SessionSummary;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.SessionQuestion;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.SessionQuestionMapper;
import org.example.englishquestionbank.mapper.SysUserMapper;
import org.example.englishquestionbank.mapper.UserWordMarkMapper;
import org.example.englishquestionbank.service.PracticeSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link PracticeSessionService} 的实现。
 *
 * <p><b>事务说明</b>：{@link #startSession} 是本项目<b>第一个真正需要事务</b>的方法 ——
 * 它要写 {@code practice_session} 和 {@code session_question} 两张表。
 *
 * <p>⚠️ <b>自调用陷阱</b>：同类内部用 {@code this.otherMethod()} 调用<b>不走 Spring 代理</b>，
 * {@code @Transactional} 不会生效。因此这里的事务方法都必须是外部调用进来的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeSessionServiceImpl implements PracticeSessionService {

    /** 一次翻译题练习允许的最大题量，防止误传一个大数字把题库全抽出来。 */
    private static final int MAX_TRANSLATION_COUNT = 50;

    private static final String TYPE_TRANSLATION = "TRANSLATION";

    private final SysUserMapper sysUserMapper;
    private final QuestionMapper questionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final SessionQuestionMapper sessionQuestionMapper;
    private final UserWordMarkMapper userWordMarkMapper;

    /**
     * {@inheritDoc}
     *
     * <p>{@code rollbackFor = Exception.class} 是刻意写的：Spring 默认只对
     * <b>运行时异常</b>回滚，受检异常不回滚。显式声明可以避免「以为回滚了其实提交了」
     * 这类最难查的问题。本项目目前不抛受检异常，但把语义钉死更安全。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PracticeSession startSession(Long userId, Long passageId) {
        // ---- 1. 校验用户 ----
        if (userId == null || sysUserMapper.selectById(userId) == null) {
            throw new IllegalArgumentException("用户不存在: userId=" + userId);
        }

        // ---- 2. 查该文章下的启用题目 ----
        // 单表条件查询 + 排序 → 简单操作 → 用构造器（项目规范）
        List<Question> questions = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .eq(Question::getPassageId, passageId)
                        .eq(Question::getStatus, 1)
                        .orderByAsc(Question::getSeq));
        if (questions.isEmpty()) {
            throw new IllegalStateException("该文章下没有可用题目: passageId=" + passageId);
        }

        // ---- 3~4. 落库 ----
        return createSession(userId, "READING", passageId, questions);
    }

    /**
     * 把会话与题目快照写入数据库 —— {@link #startSession} 与
     * {@link #startTranslationSession} 共用的部分。
     *
     * <p><b>为什么抽成私有方法而不是让一个入口调另一个</b>：
     * 同类内部 {@code this.xxx()} 调用<b>不走 Spring 代理</b>，被调方法上的
     * {@code @Transactional} 会失效。所以共用的只能是「不含事务语义的纯写入逻辑」，
     * 事务边界仍然留在各自的公开方法上（两者都带 {@code @Transactional}）。
     *
     * <p><b>为什么值得共用</b>：如果两类会话各写一套落库代码，
     * 以后改字段（比如新增一个统计列）时极易只改一处，造成行为漂移。
     */
    private PracticeSession createSession(Long userId, String mode, Long passageId,
                                          List<Question> questions) {
        // ---- 写会话 ----
        PracticeSession session = new PracticeSession();
        session.setUserId(userId);
        session.setMode(mode);
        session.setPassageId(passageId);   // 翻译题会话此处为 null
        session.setTotalCount(questions.size());
        session.setAnsweredCount(0);
        session.setCorrectCount(0);
        session.setScore(0);
        session.setMaxScore(questions.stream()
                .mapToInt(q -> q.getScore() == null ? 0 : q.getScore())
                .sum());
        session.setMarkedWordCount(0);
        session.setStatus("IN_PROGRESS");
        session.setStartedAt(LocalDateTime.now());
        // finishedAt / durationMs 留空，交卷时才填
        practiceSessionMapper.insert(session);

        // ---- 批量写题目快照 ----
        // 走 XML 的 <foreach> 批量插入（项目规范：批量插入属复杂操作，写 XML）
        List<SessionQuestion> snapshots = new ArrayList<>(questions.size());
        int order = 1;
        for (Question q : questions) {
            SessionQuestion snapshot = new SessionQuestion();
            snapshot.setSessionId(session.getId());
            snapshot.setQuestionId(q.getId());
            snapshot.setSortOrder(order++);
            snapshot.setStatus("UNANSWERED");
            snapshot.setMarkedWordCount(0);
            snapshots.add(snapshot);
        }
        sessionQuestionMapper.insertBatch(snapshots);

        log.debug("开始会话成功 sessionId={} userId={} mode={} passageId={} 题目数={}",
                session.getId(), userId, mode, passageId, questions.size());
        return session;
    }

    @Override
    public PracticeSession findById(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return practiceSessionMapper.selectById(sessionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PracticeSession startTranslationSession(Long userId, int count) {
        // ---- 1. 校验 ----
        if (count < 1 || count > MAX_TRANSLATION_COUNT) {
            throw new IllegalArgumentException(
                    "题量必须在 1~" + MAX_TRANSLATION_COUNT + " 之间，收到: " + count);
        }
        if (userId == null || sysUserMapper.selectById(userId) == null) {
            throw new IllegalArgumentException("用户不存在: userId=" + userId);
        }

        // ---- 2. 按题型取题（翻译题没有 passage_id，只能用 question_type 取）----
        List<Question> questions = questionMapper.selectByTypeWithLimit(TYPE_TRANSLATION, count);
        if (questions.isEmpty()) {
            throw new IllegalStateException("题库中没有可用的翻译题");
        }

        // ---- 3~4. 与 startSession 相同的落库逻辑 ----
        // 抽成私有方法是为了保证两类会话的写入方式完全一致，不会各写一套而逐渐漂移。
        // ⚠ 但注意：这里不能用 this.xxx() 去调 startSession —— 自调用不走代理，事务会失效。
        //   因此共用的是「纯粹的写入代码」，事务边界仍留在各自的方法上。
        return createSession(userId, "TRANSLATION", null, questions);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishSession(Long sessionId) {
        // ---- 1. 校验状态，防止重复交卷 ----
        PracticeSession session = practiceSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: sessionId=" + sessionId);
        }
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw new IllegalStateException("会话状态不是 IN_PROGRESS，不能重复交卷: status="
                    + session.getStatus());
        }

        // ---- 2. 聚合重算作答汇总（以 answer_record 为事实来源）----
        SessionSummary summary = practiceSessionMapper.selectAnswerSummary(sessionId);

        // ---- 3. 统计去重单词数（COUNT(DISTINCT) 无法用 +1 增量维护）----
        int markedWords = userWordMarkMapper.countDistinctMarkedWords(sessionId);

        // ---- 4. 落库 ----
        LocalDateTime now = LocalDateTime.now();
        Long durationMs = session.getStartedAt() == null
                ? null
                : Duration.between(session.getStartedAt(), now).toMillis();

        // 用 updateById + 实体：MyBatis-Plus 默认只更新非 null 字段，
        // 因此没设置 max_score，它就会保持 startSession 时算出的总分值不变。
        PracticeSession update = new PracticeSession();
        update.setId(sessionId);
        update.setStatus("FINISHED");
        update.setFinishedAt(now);
        update.setDurationMs(durationMs);
        update.setAnsweredCount(summary.getAnswered());
        update.setCorrectCount(summary.getCorrect());
        update.setScore(summary.getScore());
        update.setMarkedWordCount(markedWords);
        practiceSessionMapper.updateById(update);

        log.debug("交卷完成 sessionId={} 已答={} 答对={} 得分={} 标记词数={} 耗时={}ms",
                sessionId, summary.getAnswered(), summary.getCorrect(),
                summary.getScore(), markedWords, durationMs);
    }
}
