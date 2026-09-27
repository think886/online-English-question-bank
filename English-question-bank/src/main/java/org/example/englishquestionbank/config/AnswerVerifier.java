package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.bootstrap.SampleDataInitializer;
import org.example.englishquestionbank.dto.AnswerResult;
import org.example.englishquestionbank.entity.AnswerRecord;
import org.example.englishquestionbank.entity.Passage;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.SessionQuestion;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.mapper.AnswerRecordMapper;
import org.example.englishquestionbank.mapper.PassageMapper;
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
 * 作答链路验证器（开发期辅助）。
 *
 * <h2>覆盖的验证点</h2>
 * <ol>
 *   <li>阅读题自动判分：对/错两条分支</li>
 *   <li>{@code answer_record} 的唯一键是 {@code (session_id, question_id)}，
 *       重复提交要<b>覆盖</b>而不是报错或留两行</li>
 *   <li><b>改答案时会话统计按差值调整</b> —— 这是最容易写错的地方：
 *       用户把答对的题改成答错，对题数必须减回去，否则会留下一个永远消不掉的数</li>
 *   <li>{@code session_question.status} 是否被置为 {@code ANSWERED}</li>
 *   <li>错题本查询（兑现 schema 里「不需要额外的表」那句声明）</li>
 *   <li>会话结束后不能再作答</li>
 *   <li>不在本次会话中的题不能作答</li>
 *   <li>非法选项标识被拒绝</li>
 * </ol>
 *
 * <p><b>未覆盖</b>：翻译题分支。示例数据里的题目都是阅读题，
 * 而翻译题的 {@code passage_id} 为空、不在按文章开的会话里，
 * 组卷方式尚未设计（见 {@code docs/backlog.md}）。该分支已实现但<b>未验证</b>。
 *
 * <p>这是开发期辅助类，链路确认无误后可以删除。
 */
@Slf4j
@Component
@Order(40)   // 晚于 SampleDataInitializer(1) / WritePathVerifier(10) / WordMarkVerifier(20)
@RequiredArgsConstructor
public class AnswerVerifier implements CommandLineRunner {

    private final SysUserService sysUserService;
    private final PracticeSessionService practiceSessionService;
    private final AnswerService answerService;

    private final PassageMapper passageMapper;
    private final QuestionMapper questionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final SessionQuestionMapper sessionQuestionMapper;
    private final AnswerRecordMapper answerRecordMapper;

    private int passed = 0;
    private int failed = 0;

    @Override
    public void run(String... args) {
        log.info("================= 写入链路验证 · 阶段 4（作答与判分）=================");
        Long sessionId = null;
        try {
            SysUser demoUser = sysUserService.findByUsername(SampleDataInitializer.DEMO_USERNAME);
            Passage passage = passageMapper.selectOne(
                    Wrappers.<Passage>lambdaQuery()
                            .eq(Passage::getSource, SampleDataInitializer.SAMPLE_SOURCE)
                            .last("LIMIT 1"));
            if (demoUser == null || passage == null) {
                log.warn("✗ 缺少示例数据，跳过阶段 4 验证");
                failed++;
                return;
            }
            Long userId = demoUser.getId();

            List<Question> questions = questionMapper.selectList(
                    Wrappers.<Question>lambdaQuery()
                            .eq(Question::getPassageId, passage.getId())
                            .orderByAsc(Question::getSeq));
            if (questions.size() < 2) {
                log.warn("✗ 示例题目不足 2 道，跳过阶段 4 验证");
                failed++;
                return;
            }
            Question q1 = questions.get(0);
            Question q2 = questions.get(1);

            PracticeSession session = practiceSessionService.startSession(userId, passage.getId());
            sessionId = session.getId();

            // 示例数据的正确答案：第 1 题 B、第 2 题 C（见 SampleDataInitializer）
            // 本验证器故意对第 1 题先答对、再改成答错，以检验差值调整

            // ---------- [1] 答对第 1 题 ----------
            AnswerResult r1 = answerService.submitAnswer(userId, sessionId, q1.getId(), "B");
            check("阅读题答对：自动判分正确",
                    Integer.valueOf(1).equals(r1.isCorrect())
                            && r1.score() != null && r1.score().equals(q1.getScore())
                            && "DONE".equals(r1.gradingStatus())
                            && r1.firstSubmit(),
                    String.format("isCorrect=%s score=%s/%s status=%s firstSubmit=%s",
                            r1.isCorrect(), r1.score(), r1.maxScore(),
                            r1.gradingStatus(), r1.firstSubmit()));

            // ---------- [2] 会话统计（第一次作答）----------
            PracticeSession afterFirst = practiceSessionMapper.selectById(sessionId);
            check("会话统计：已答 1 / 答对 1",
                    Integer.valueOf(1).equals(afterFirst.getAnsweredCount())
                            && Integer.valueOf(1).equals(afterFirst.getCorrectCount())
                            && Integer.valueOf(q1.getScore()).equals(afterFirst.getScore()),
                    String.format("answered=%s correct=%s score=%s",
                            afterFirst.getAnsweredCount(), afterFirst.getCorrectCount(),
                            afterFirst.getScore()));

            // ---------- [3] 答错第 2 题 ----------
            AnswerResult r2 = answerService.submitAnswer(userId, sessionId, q2.getId(), "A");
            check("阅读题答错：判 0 分",
                    Integer.valueOf(0).equals(r2.isCorrect())
                            && Integer.valueOf(0).equals(r2.score()),
                    String.format("isCorrect=%s score=%s", r2.isCorrect(), r2.score()));

            PracticeSession afterSecond = practiceSessionMapper.selectById(sessionId);
            check("会话统计：已答 2 / 答对 1",
                    Integer.valueOf(2).equals(afterSecond.getAnsweredCount())
                            && Integer.valueOf(1).equals(afterSecond.getCorrectCount()),
                    String.format("answered=%s correct=%s score=%s",
                            afterSecond.getAnsweredCount(), afterSecond.getCorrectCount(),
                            afterSecond.getScore()));

            // ---------- [4] 改答案：把第 1 题从答对改成答错 ----------
            AnswerResult r3 = answerService.submitAnswer(userId, sessionId, q1.getId(), "A");
            long recordCount = answerRecordMapper.selectCount(
                    Wrappers.<AnswerRecord>lambdaQuery()
                            .eq(AnswerRecord::getSessionId, sessionId));
            check("重复提交覆盖而非新增",
                    !r3.firstSubmit() && recordCount == 2,
                    String.format("firstSubmit=%s 作答记录行数=%d（期望 2：两道题各一行）",
                            r3.firstSubmit(), recordCount));

            // ---------- [5] 关键：统计按差值调整 ----------
            PracticeSession afterChange = practiceSessionMapper.selectById(sessionId);
            check("改答案后统计按差值调整（答对 → 答错）",
                    Integer.valueOf(2).equals(afterChange.getAnsweredCount())
                            && Integer.valueOf(0).equals(afterChange.getCorrectCount())
                            && Integer.valueOf(0).equals(afterChange.getScore()),
                    String.format("answered=%s（应仍为 2）correct=%s（应为 0）score=%s（应为 0）",
                            afterChange.getAnsweredCount(), afterChange.getCorrectCount(),
                            afterChange.getScore()));

            // ---------- [6] 会话题目状态 ----------
            List<SessionQuestion> snapshots = sessionQuestionMapper.selectList(
                    Wrappers.<SessionQuestion>lambdaQuery()
                            .eq(SessionQuestion::getSessionId, sessionId));
            long answered = snapshots.stream()
                    .filter(s -> "ANSWERED".equals(s.getStatus())).count();
            check("session_question.status 已置为 ANSWERED",
                    answered == 2, "已作答快照 " + answered + " / " + snapshots.size());

            // ---------- [7] 错题本 ----------
            //
            // 【第 2 次修订】原来断言 wrong.size() == 2，隐含假设「该用户没有别的错题」。
            // 只要用同一个用户手工测过一次作答（例如 tools/api-smoke.ps1），
            // 错题本里就会多出别人的记录，自检误报失败。
            // 改为【只看本次会话产生的记录】，同时仍然验证「错题本 = is_correct=0」这条语义。
            List<AnswerRecord> wrong = answerService.listWrongByUser(userId, 50);
            // sessionId 是「先赋 null 再赋值」，不是 effectively final，lambda 里用不了，
            // 因此先拷一份只赋一次值的局部变量
            Long sid = sessionId;
            List<AnswerRecord> wrongOfThisSession = wrong.stream()
                    .filter(r -> sid.equals(r.getSessionId()))
                    .toList();
            check("错题本可查到本次的两道错题，且不含答对的题",
                    wrongOfThisSession.size() == 2
                            && wrongOfThisSession.stream()
                                    .allMatch(r -> Integer.valueOf(0).equals(r.getIsCorrect())),
                    String.format("本次会话错题 %d 条（期望 2）；查询共返回 %d 条",
                            wrongOfThisSession.size(), wrong.size()));

            // ---------- [8] 不在会话中的题不能作答 ----------
            boolean rejectedNotInSession = false;
            String notInSessionDetail;
            try {
                answerService.submitAnswer(userId, sessionId, -999L, "A");
                notInSessionDetail = "竟然作答成功了！";
            } catch (IllegalStateException e) {
                rejectedNotInSession = true;
                notInSessionDetail = "已拒绝：" + e.getMessage();
            }
            check("不在会话中的题被拒绝", rejectedNotInSession, notInSessionDetail);

            // ---------- [9] 非法选项标识 ----------
            boolean rejectedBadKey = false;
            String badKeyDetail;
            try {
                answerService.submitAnswer(userId, sessionId, q2.getId(), "E");
                badKeyDetail = "竟然接受了 E！";
            } catch (IllegalArgumentException e) {
                rejectedBadKey = true;
                badKeyDetail = "已拒绝：" + e.getMessage();
            }
            check("非法选项标识被拒绝", rejectedBadKey, badKeyDetail);

            // ---------- [10] 会话结束后不能再作答 ----------
            // 手工把会话置为 FINISHED 后再提交，验证状态守卫
            practiceSessionMapper.update(null,
                    Wrappers.<PracticeSession>lambdaUpdate()
                            .set(PracticeSession::getStatus, "FINISHED")
                            .eq(PracticeSession::getId, sessionId));
            boolean rejectedFinished = false;
            String finishedDetail;
            try {
                answerService.submitAnswer(userId, sessionId, q2.getId(), "C");
                finishedDetail = "已结束的会话竟然还能作答！";
            } catch (IllegalStateException e) {
                rejectedFinished = true;
                finishedDetail = "已拒绝：" + e.getMessage();
            }
            check("已结束的会话不能作答", rejectedFinished, finishedDetail);

        } catch (Throwable t) {
            failed++;
            log.error("✗ 阶段 4 出现未预期的异常，因果链如下：");
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

    /** 删除本次会话；answer_record 与 session_question 都由外键 ON DELETE CASCADE 级联清理。 */
    private void cleanup(Long sessionId) {
        try {
            if (sessionId != null) {
                int deleted = practiceSessionMapper.deleteById(sessionId);
                log.info("阶段 4 清理：删除会话 {}（级联删作答记录与题目快照），影响 {} 行",
                        sessionId, deleted);
            }
        } catch (Throwable t) {
            log.warn("阶段 4 清理失败（不影响验证结论）：{}", t.getMessage());
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
            log.info("================= ✅ 作答链路验证通过 =================");
        } else {
            log.error("通过 {}/{} 项，失败 {} 项", passed, passed + failed, failed);
            log.error("================= ❌ 作答链路验证存在失败 =================");
        }
    }
}
