package org.example.englishquestionbank.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.SessionQuestion;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.SessionQuestionMapper;
import org.example.englishquestionbank.mapper.SysUserMapper;
import org.example.englishquestionbank.service.PracticeSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final SysUserMapper sysUserMapper;
    private final QuestionMapper questionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final SessionQuestionMapper sessionQuestionMapper;

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

        // ---- 3. 写会话 ----
        LocalDateTime now = LocalDateTime.now();
        PracticeSession session = new PracticeSession();
        session.setUserId(userId);
        session.setMode("READING");
        session.setPassageId(passageId);
        session.setTotalCount(questions.size());
        session.setAnsweredCount(0);
        session.setCorrectCount(0);
        session.setScore(0);
        session.setMaxScore(questions.stream()
                .mapToInt(q -> q.getScore() == null ? 0 : q.getScore())
                .sum());
        session.setMarkedWordCount(0);
        session.setStatus("IN_PROGRESS");
        session.setStartedAt(now);
        // finishedAt / durationMs 留空，交卷时才填
        practiceSessionMapper.insert(session);

        // ---- 4. 批量写题目快照 ----
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

        log.debug("开始会话成功 sessionId={} userId={} passageId={} 题目数={}",
                session.getId(), userId, passageId, questions.size());
        return session;
    }

    @Override
    public PracticeSession findById(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        return practiceSessionMapper.selectById(sessionId);
    }
}
