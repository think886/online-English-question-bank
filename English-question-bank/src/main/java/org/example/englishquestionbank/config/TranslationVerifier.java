package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.dto.AnswerResult;
import org.example.englishquestionbank.entity.AnswerRecord;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.SessionQuestion;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.mapper.AnswerRecordMapper;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.SessionQuestionMapper;
import org.example.englishquestionbank.service.AnswerService;
import org.example.englishquestionbank.service.PracticeSessionService;
import org.example.englishquestionbank.service.SysUserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 翻译题链路验证器（开发期辅助）。
 *
 * <p><b>为什么单独一个验证器</b>：在此之前翻译题<b>根本无法作答</b> ——
 * 它的 {@code passage_id} 为 NULL（不属于任何文章），而组卷只按文章取题。
 * 这个缺口登记为待办 B-11。本类验证 B-11 的修复：
 * 新增的 {@code startTranslationSession} 让翻译题能进入会话，
 * 从而连翻译题的判分分支也一并验证了。
 *
 * <h2>覆盖的验证点</h2>
 * <ol>
 *   <li>翻译题能独立组卷：{@code mode=TRANSLATION}、{@code passage_id} 为空</li>
 *   <li>会话题目快照包含翻译题</li>
 *   <li>提交译文后的判分结果：{@code is_correct} 与 {@code score} 均为 {@code null}、
 *       {@code grading_status = PENDING}</li>
 *   <li><b>{@code is_correct} 为 NULL 时不会污染会话的对题数</b> ——
 *       这验证了聚合 SQL 里 {@code SUM(is_correct = 1)} 对 NULL 的处理</li>
 *   <li>用户译文能原样存下来并被查回（结果页需要展示它）</li>
 *   <li>题量参数校验</li>
 * </ol>
 *
 * <p>这是开发期辅助类，链路确认无误后可以删除。
 */
@Slf4j
@Component
@Order(60)
@RequiredArgsConstructor
public class TranslationVerifier implements CommandLineRunner {

    private final SysUserService sysUserService;
    private final PracticeSessionService practiceSessionService;
    private final AnswerService answerService;

    private final QuestionMapper questionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final SessionQuestionMapper sessionQuestionMapper;
    private final AnswerRecordMapper answerRecordMapper;

    /** 模拟用户提交的译文（刻意与参考译文不同，也不追求质量）。 */
    private static final String USER_TRANSLATION =
            "With the growth of cities, more people choose to go out by bike. "
                    + "This helps to reduce traffic jams and improve air quality.";

    private int passed = 0;
    private int failed = 0;

    @Override
    public void run(String... args) {
        log.info("================= 写入链路验证 · 阶段 6（翻译题链路）=================");
        Long sessionId = null;
        try {
            SysUser demoUser = sysUserService.findByUsername("demo");
            if (demoUser == null) {
                log.warn("✗ 缺少演示用户，跳过阶段 6 验证");
                failed++;
                return;
            }
            Long userId = demoUser.getId();

            // ---------- [1] 题库里确实有翻译题 ----------
            List<Question> translations =
                    questionMapper.selectByTypeWithLimit("TRANSLATION", 10);
            check("题库中存在翻译题（B-11 的前置条件）",
                    !translations.isEmpty(),
                    "查到 " + translations.size() + " 道翻译题");
            if (translations.isEmpty()) {
                log.warn("✗ 没有翻译题可测，阶段 6 后续验证无法继续");
                return;
            }
            Question tq = translations.get(0);

            // ---------- [2] 翻译题独立组卷 ----------
            PracticeSession session = practiceSessionService.startTranslationSession(userId, 1);
            sessionId = session.getId();
            check("翻译题独立组卷",
                    "TRANSLATION".equals(session.getMode())
                            && session.getPassageId() == null
                            && Integer.valueOf(1).equals(session.getTotalCount()),
                    String.format("sessionId=%s mode=%s passageId=%s totalCount=%s maxScore=%s",
                            sessionId, session.getMode(), session.getPassageId(),
                            session.getTotalCount(), session.getMaxScore()));

            // ---------- [3] 快照包含翻译题 ----------
            List<SessionQuestion> snapshots = sessionQuestionMapper.selectList(
                    Wrappers.<SessionQuestion>lambdaQuery()
                            .eq(SessionQuestion::getSessionId, sessionId));
            check("会话快照包含翻译题",
                    snapshots.size() == 1 && snapshots.get(0).getQuestionId().equals(tq.getId()),
                    "快照 " + snapshots.size() + " 条，questionId="
                            + (snapshots.isEmpty() ? "-" : snapshots.get(0).getQuestionId()));

            // ---------- [4] 提交译文：不判分，置 PENDING ----------
            AnswerResult result = answerService.submitAnswer(userId, sessionId, tq.getId(),
                    USER_TRANSLATION);
            check("翻译题提交后置为待评分（不自动判分）",
                    result.isCorrect() == null
                            && result.score() == null
                            && "PENDING".equals(result.gradingStatus()),
                    String.format("isCorrect=%s score=%s gradingStatus=%s",
                            result.isCorrect(), result.score(), result.gradingStatus()));

            // ---------- [5] 关键：NULL 的 is_correct 不污染对题数 ----------
            PracticeSession afterSubmit = practiceSessionMapper.selectById(sessionId);
            check("待评分的翻译题不污染会话统计",
                    Integer.valueOf(1).equals(afterSubmit.getAnsweredCount())
                            && Integer.valueOf(0).equals(afterSubmit.getCorrectCount())
                            && Integer.valueOf(0).equals(afterSubmit.getScore()),
                    String.format("已答=%s（应为 1）答对=%s（应为 0，因为 is_correct 是 NULL）得分=%s（应为 0）",
                            afterSubmit.getAnsweredCount(), afterSubmit.getCorrectCount(),
                            afterSubmit.getScore()));

            // ---------- [6] 用户译文能查回 ----------
            AnswerRecord record = answerRecordMapper.selectOne(
                    Wrappers.<AnswerRecord>lambdaQuery()
                            .eq(AnswerRecord::getSessionId, sessionId)
                            .eq(AnswerRecord::getQuestionId, tq.getId())
                            .last("LIMIT 1"));
            check("用户译文被完整保存并可查回",
                    record != null && USER_TRANSLATION.equals(record.getUserAnswer()),
                    record == null ? "查不到作答记录"
                            : "译文长度 " + record.getUserAnswer().length() + " 字符，与提交内容一致");

            // ---------- [7] 交卷后的聚合重算同样不受 NULL 影响 ----------
            practiceSessionService.finishSession(userId, sessionId);
            PracticeSession finished = practiceSessionMapper.selectById(sessionId);
            check("交卷聚合重算后统计仍然正确",
                    "FINISHED".equals(finished.getStatus())
                            && Integer.valueOf(1).equals(finished.getAnsweredCount())
                            && Integer.valueOf(0).equals(finished.getCorrectCount()),
                    String.format("status=%s 已答=%s 答对=%s",
                            finished.getStatus(), finished.getAnsweredCount(),
                            finished.getCorrectCount()));

            // ---------- [8] 题量参数校验 ----------
            boolean rejectedBadCount = false;
            String badCountDetail;
            try {
                practiceSessionService.startTranslationSession(userId, 0);
                badCountDetail = "题量 0 竟然被接受了！";
            } catch (IllegalArgumentException e) {
                rejectedBadCount = true;
                badCountDetail = "已拒绝：" + e.getMessage();
            }
            check("非法题量被拒绝", rejectedBadCount, badCountDetail);

        } catch (Throwable t) {
            failed++;
            log.error("✗ 阶段 6 出现未预期的异常，因果链如下：");
            Throwable current = t;
            for (int depth = 0; current != null && depth < 8; depth++) {
                log.error("  [{}] {} : {}", depth,
                        current.getClass().getName(), current.getMessage());
                current = current.getCause();
            }
        } finally {
            cleanup(sessionId);
        }

        summary();
    }

    private void cleanup(Long sessionId) {
        try {
            if (sessionId != null) {
                practiceSessionMapper.deleteById(sessionId);
                log.info("阶段 6 清理：删除会话 {}（级联删作答记录与快照）", sessionId);
            }
        } catch (Throwable t) {
            log.warn("阶段 6 清理失败（不影响验证结论）：{}", t.getMessage());
        }
    }

    private void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        log.info("  {} {}", ok ? "✅" : "❌", label + "  " + detail);
    }

    private void summary() {
        log.info("------------------------------------------------------------------");
        if (failed == 0) {
            log.info("通过 {}/{} 项", passed, passed + failed);
            log.info("================= ✅ 翻译题链路验证通过 =================");
        } else {
            log.error("通过 {}/{} 项，失败 {} 项", passed, passed + failed, failed);
            log.error("================= ❌ 翻译题链路验证存在失败 =================");
        }
    }
}
