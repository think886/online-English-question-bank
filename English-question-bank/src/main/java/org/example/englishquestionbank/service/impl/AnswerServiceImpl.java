package org.example.englishquestionbank.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.dto.AnswerResult;
import org.example.englishquestionbank.dto.PageResult;
import org.example.englishquestionbank.dto.query.WrongQuestionItem;
import org.example.englishquestionbank.entity.AnswerRecord;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.QuestionOption;
import org.example.englishquestionbank.entity.SessionQuestion;
import org.example.englishquestionbank.mapper.AnswerRecordMapper;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.QuestionOptionMapper;
import org.example.englishquestionbank.mapper.SessionQuestionMapper;
import org.example.englishquestionbank.service.AnswerService;
import org.example.englishquestionbank.support.Paging;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link AnswerService} 的实现。
 *
 * <p>一次 {@link #submitAnswer} 会触及三张表：
 * {@code answer_record}（upsert）、{@code practice_session}（按差值调整统计）、
 * {@code session_question}（置为已作答）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerServiceImpl implements AnswerService {

    /** 阅读题的合法选项标识。 */
    private static final Pattern OPTION_KEY_PATTERN = Pattern.compile("[A-D]");

    private static final String TYPE_READING = "READING";

    private final PracticeSessionMapper practiceSessionMapper;
    private final SessionQuestionMapper sessionQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final AnswerRecordMapper answerRecordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnswerResult submitAnswer(Long userId, Long sessionId, Long questionId, String userAnswer) {
        // ---- 1. 校验会话 ----
        PracticeSession session = practiceSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: sessionId=" + sessionId);
        }
        if (!userId.equals(session.getUserId())) {
            throw new IllegalStateException("会话不属于该用户: sessionId=" + sessionId
                    + " sessionUserId=" + session.getUserId() + " userId=" + userId);
        }
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw new IllegalStateException("会话已结束，不能再作答: status=" + session.getStatus());
        }

        // ---- 2. 该题必须在本次会话的快照里 ----
        // 防止绕过组卷、直接对任意题目作答
        long inSession = sessionQuestionMapper.selectCount(
                Wrappers.<SessionQuestion>lambdaQuery()
                        .eq(SessionQuestion::getSessionId, sessionId)
                        .eq(SessionQuestion::getQuestionId, questionId));
        if (inSession == 0) {
            throw new IllegalStateException("该题不在本次会话中: questionId=" + questionId);
        }

        // ---- 3. 取题目 ----
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("题目不存在: questionId=" + questionId);
        }

        // ---- 4. 判分 ----
        Judgment judgment = judge(question, userAnswer);

        // ---- 5. 查上一次的作答记录（用于算差值与判断首次）----
        AnswerRecord previous = answerRecordMapper.selectOne(
                Wrappers.<AnswerRecord>lambdaQuery()
                        .eq(AnswerRecord::getSessionId, sessionId)
                        .eq(AnswerRecord::getQuestionId, questionId)
                        .last("LIMIT 1"));

        // ---- 6. upsert 作答记录（唯一键 session_id + question_id，语义是覆盖）----
        AnswerRecord record = new AnswerRecord();
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setQuestionId(questionId);
        record.setUserAnswer(userAnswer);
        record.setIsCorrect(judgment.isCorrect());
        record.setScore(judgment.score());
        record.setMaxScore(question.getScore());
        record.setGradingStatus(judgment.gradingStatus());
        record.setGradedBy(judgment.gradedBy());
        record.setGradedAt(judgment.gradedAt());
        answerRecordMapper.upsertAnswer(record);

        // ---- 7. 按「差值」调整会话统计 ----
        int answeredDelta = previous == null ? 1 : 0;
        int oldCorrect = isCorrectFlag(previous == null ? null : previous.getIsCorrect());
        int newCorrect = isCorrectFlag(judgment.isCorrect());
        int oldScore = previous == null || previous.getScore() == null ? 0 : previous.getScore();
        int newScore = judgment.score() == null ? 0 : judgment.score();
        practiceSessionMapper.incrementProgress(
                sessionId, answeredDelta, newCorrect - oldCorrect, newScore - oldScore);

        // ---- 8. 标注该题已作答 ----
        sessionQuestionMapper.update(null,
                Wrappers.<SessionQuestion>lambdaUpdate()
                        .set(SessionQuestion::getStatus, "ANSWERED")
                        .eq(SessionQuestion::getSessionId, sessionId)
                        .eq(SessionQuestion::getQuestionId, questionId));

        log.debug("提交答案 sessionId={} questionId={} 首次={} isCorrect={} score={}",
                sessionId, questionId, previous == null, judgment.isCorrect(), judgment.score());

        return new AnswerResult(judgment.isCorrect(), judgment.score(),
                question.getScore(), judgment.gradingStatus(), previous == null);
    }

    @Override
    public List<AnswerRecord> listBySession(Long sessionId) {
        return answerRecordMapper.selectList(
                Wrappers.<AnswerRecord>lambdaQuery()
                        .eq(AnswerRecord::getSessionId, sessionId)
                        .orderByAsc(AnswerRecord::getQuestionId));
    }

    @Override
    public List<AnswerRecord> listWrongByUser(Long userId, int limit) {
        return answerRecordMapper.selectWrongByUser(userId, limit);
    }

    // =====================================================================

    /** 判分结论。 */
    private record Judgment(Integer isCorrect, Integer score, String gradingStatus,
                            String gradedBy, LocalDateTime gradedAt) {
    }

    /**
     * 按题型判分。
     *
     * <p>阅读题：答案必须是 A/B/C/D，比对 {@code question_option.is_correct} 得出对错与得分。
     * <p>翻译题：不判分，置为 {@code PENDING} 等待后续 LLM 评分。
     */
    private Judgment judge(Question question, String userAnswer) {
        if (!TYPE_READING.equals(question.getQuestionType())) {
            // 翻译题：评分未实现，只登记待评分状态
            return new Judgment(null, null, "PENDING", null, null);
        }

        String key = userAnswer == null ? "" : userAnswer.trim().toUpperCase();
        if (!OPTION_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "阅读题的答案必须是 A/B/C/D 之一，收到: " + userAnswer);
        }

        boolean correct = questionOptionMapper.selectCount(
                Wrappers.<QuestionOption>lambdaQuery()
                        .eq(QuestionOption::getQuestionId, question.getId())
                        .eq(QuestionOption::getOptionKey, key)
                        .eq(QuestionOption::getIsCorrect, 1)) > 0;

        int fullScore = question.getScore() == null ? 0 : question.getScore();
        return new Judgment(
                correct ? 1 : 0,
                correct ? fullScore : 0,
                "DONE",
                "AUTO",
                LocalDateTime.now());
    }

    /** 把 {@code is_correct} 的三种取值（null / 0 / 1）折算成 0 或 1。 */
    private static int isCorrectFlag(Integer isCorrect) {
        return Integer.valueOf(1).equals(isCorrect) ? 1 : 0;
    }

    @Override
    public PageResult<WrongQuestionItem> listWrongPage(Long userId, long page, long size) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        // 注意：「只取已交卷会话」与「is_correct = 0」两条过滤都在 XML 的 SQL 里，
        // 刻意不在这里补条件 —— 安全约束写在数据访问层，才不会被调用方绕过。
        IPage<WrongQuestionItem> result = answerRecordMapper.selectWrongPage(
                Paging.of(page, size), userId);
        return Paging.toResult(result);
    }
}
