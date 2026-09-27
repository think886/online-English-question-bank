package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.bootstrap.SampleDataInitializer;
import org.example.englishquestionbank.dto.AnswerResult;
import org.example.englishquestionbank.dto.MarkCommand;
import org.example.englishquestionbank.dto.MarkResult;
import org.example.englishquestionbank.entity.AnswerRecord;
import org.example.englishquestionbank.entity.Passage;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.entity.UserVocabulary;
import org.example.englishquestionbank.entity.UserWordMark;
import org.example.englishquestionbank.mapper.PassageMapper;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.UserVocabularyMapper;
import org.example.englishquestionbank.mapper.UserWordMarkMapper;
import org.example.englishquestionbank.service.AnswerService;
import org.example.englishquestionbank.service.PracticeSessionService;
import org.example.englishquestionbank.service.SysUserService;
import org.example.englishquestionbank.service.WordMarkService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 端到端链路验证器（开发期辅助）—— 本项目主流程的集成测试。
 *
 * <p>前面几个验证器各自只测一段：单表写入、跨表事务、划词、作答。
 * 本类把用户真实会走的那条路径<b>串起来跑一遍</b>：
 *
 * <pre>
 *   建会话 → 划词标记 → 提交作答 → 交卷 → 取结果页数据
 * </pre>
 *
 * <h2>覆盖的验证点</h2>
 * <ol>
 *   <li>整条链路能连贯跑通，中间不出现跨模块的状态不一致</li>
 *   <li>{@code finishSession} 的状态守卫：交卷后不能再作答、不能重复交卷</li>
 *   <li>{@code finished_at} / {@code duration_ms} 被填充</li>
 *   <li>{@code marked_word_count} 取的是<b>去重单词数</b>而非标记次数</li>
 *   <li><b>聚合重算能纠正被篡改的统计</b> —— 见下</li>
 *   <li>结果页所需的数据（作答记录、标记列表）都能查出来</li>
 * </ol>
 *
 * <h2>第 5 项是怎么测的</h2>
 * 交卷前，<b>故意把 {@code practice_session} 的三个统计字段改成荒谬的值</b>
 * （把「已答 2 题、答对 1 题、得 1 分」改成「已答 99、答对 99、得 999」），
 * 然后调用交卷。如果交卷真的以 {@code answer_record} 为准做了聚合重算，
 * 这些假值就会被纠正回来。
 *
 * <p>这直接验证了设计文档里那句「增量更新是性能优化，聚合重算是正确性保证」——
 * 而不是只看代码里写了个 SELECT 就认为它有效。
 *
 * <p>这是开发期辅助类，链路确认无误后可以删除。
 */
@Slf4j
@Component
@Order(50)   // 最后执行
@RequiredArgsConstructor
public class EndToEndVerifier implements CommandLineRunner {

    private final SysUserService sysUserService;
    private final PracticeSessionService practiceSessionService;
    private final WordMarkService wordMarkService;
    private final AnswerService answerService;

    private final PassageMapper passageMapper;
    private final QuestionMapper questionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final UserWordMarkMapper userWordMarkMapper;
    private final UserVocabularyMapper userVocabularyMapper;

    private int passed = 0;
    private int failed = 0;

    @Override
    public void run(String... args) {
        log.info("================= 写入链路验证 · 阶段 5（端到端主流程）=================");
        Long sessionId = null;
        Long userId = null;
        try {
            SysUser demoUser = sysUserService.findByUsername(SampleDataInitializer.DEMO_USERNAME);
            Passage passage = passageMapper.selectOne(
                    Wrappers.<Passage>lambdaQuery()
                            .eq(Passage::getSource, SampleDataInitializer.SAMPLE_SOURCE)
                            .last("LIMIT 1"));
            if (demoUser == null || passage == null) {
                log.warn("✗ 缺少示例数据，跳过阶段 5 验证");
                failed++;
                return;
            }
            userId = demoUser.getId();

            List<Question> questions = questionMapper.selectList(
                    Wrappers.<Question>lambdaQuery()
                            .eq(Question::getPassageId, passage.getId())
                            .orderByAsc(Question::getSeq));
            if (questions.size() < 2) {
                log.warn("✗ 示例题目不足 2 道，跳过阶段 5 验证");
                failed++;
                return;
            }
            Question q1 = questions.get(0);
            Question q2 = questions.get(1);

            // ================= 1. 开始练习 =================
            PracticeSession session = practiceSessionService.startSession(userId, passage.getId());
            sessionId = session.getId();
            check("开始练习会话", session.getId() != null, "sessionId=" + sessionId);

            // ================= 2. 划词标记 =================
            // 标 3 次，其中 "running" 出现两次（不同位置），去重后是 2 个不同的词
            MarkResult m1 = wordMarkService.markWord(new MarkCommand(
                    userId, sessionId, q1.getId(), passage.getId(),
                    "STEM", "running", "He is running fast.", 5, 12));
            wordMarkService.markWord(new MarkCommand(
                    userId, sessionId, q1.getId(), passage.getId(),
                    "STEM", "running", "Running is good for you.", 40, 47));
            MarkResult m3 = wordMarkService.markWord(new MarkCommand(
                    userId, sessionId, q2.getId(), passage.getId(),
                    "PASSAGE", "harvest", "the harvest is rarely enough", 8, 15));
            long markRows = userWordMarkMapper.selectCount(
                    Wrappers.<UserWordMark>lambdaQuery()
                            .eq(UserWordMark::getSessionId, sessionId));
            check("划词标记：3 次标记 / 2 个不同的词",
                    markRows == 3
                            && "run".equals(m1.normalizedForm())
                            && "harvest".equals(m3.normalizedForm()),
                    String.format("标记行数=%d（期望 3）归一化: %s / %s",
                            markRows, m1.normalizedForm(), m3.normalizedForm()));

            // ================= 3. 提交作答 =================
            AnswerResult a1 = answerService.submitAnswer(userId, sessionId, q1.getId(), "B");  // 对
            AnswerResult a2 = answerService.submitAnswer(userId, sessionId, q2.getId(), "A");  // 错
            check("提交作答：一题对一题错",
                    Integer.valueOf(1).equals(a1.isCorrect())
                            && Integer.valueOf(0).equals(a2.isCorrect()),
                    String.format("q1.isCorrect=%s q2.isCorrect=%s", a1.isCorrect(), a2.isCorrect()));

            // ================= 4. 故意篡改统计，为第 5 项做准备 =================
            practiceSessionMapper.update(null,
                    Wrappers.<PracticeSession>lambdaUpdate()
                            .set(PracticeSession::getAnsweredCount, 99)
                            .set(PracticeSession::getCorrectCount, 99)
                            .set(PracticeSession::getScore, 999)
                            .set(PracticeSession::getMarkedWordCount, 99)
                            .eq(PracticeSession::getId, sessionId));
            PracticeSession tampered = practiceSessionMapper.selectById(sessionId);
            log.info("  （已把统计篡改为 已答={} 答对={} 得分={} 标记词数={}，用于验证交卷重算）",
                    tampered.getAnsweredCount(), tampered.getCorrectCount(),
                    tampered.getScore(), tampered.getMarkedWordCount());

            // ================= 5. 交卷 =================
            practiceSessionService.finishSession(userId, sessionId);
            PracticeSession finished = practiceSessionMapper.selectById(sessionId);

            check("交卷：聚合重算纠正了被篡改的统计",
                    Integer.valueOf(2).equals(finished.getAnsweredCount())
                            && Integer.valueOf(1).equals(finished.getCorrectCount())
                            && Integer.valueOf(q1.getScore()).equals(finished.getScore()),
                    String.format("已答=%s（应 2，篡改值 99）答对=%s（应 1，篡改值 99）得分=%s（应 %s，篡改值 999）",
                            finished.getAnsweredCount(), finished.getCorrectCount(),
                            finished.getScore(), q1.getScore()));

            check("交卷：状态与时间字段已填充",
                    "FINISHED".equals(finished.getStatus())
                            && finished.getFinishedAt() != null
                            && finished.getDurationMs() != null,
                    String.format("status=%s finishedAt=%s durationMs=%s",
                            finished.getStatus(), finished.getFinishedAt(),
                            finished.getDurationMs()));

            check("交卷：marked_word_count 取去重单词数（而非标记次数）",
                    Integer.valueOf(2).equals(finished.getMarkedWordCount()),
                    String.format("marked_word_count=%s（期望 2；标记共 3 次，但 running 出现两次）",
                            finished.getMarkedWordCount()));

            // ================= 6. 交卷后的状态守卫 =================
            boolean rejectedAfterFinish = false;
            String afterFinishDetail;
            try {
                answerService.submitAnswer(userId, sessionId, q2.getId(), "C");
                afterFinishDetail = "交卷后竟然还能作答！";
            } catch (IllegalStateException e) {
                rejectedAfterFinish = true;
                afterFinishDetail = "已拒绝：" + e.getMessage();
            }
            check("交卷后不能再作答", rejectedAfterFinish, afterFinishDetail);

            boolean rejectedSecondFinish = false;
            String secondFinishDetail;
            try {
                practiceSessionService.finishSession(userId, sessionId);
                secondFinishDetail = "重复交卷竟然成功了！";
            } catch (IllegalStateException e) {
                rejectedSecondFinish = true;
                secondFinishDetail = "已拒绝：" + e.getMessage();
            }
            check("不能重复交卷", rejectedSecondFinish, secondFinishDetail);

            // ================= 7. 结果页数据可用 =================
            List<AnswerRecord> records = answerService.listBySession(sessionId);
            List<UserWordMark> marks = wordMarkService.listMarksBySession(userId, sessionId);
            check("结果页数据都能查出来",
                    records.size() == 2 && marks.size() == 3,
                    String.format("作答记录 %d 条（期望 2）、标记记录 %d 条（期望 3）",
                            records.size(), marks.size()));

        } catch (Throwable t) {
            failed++;
            log.error("✗ 阶段 5 出现未预期的异常，因果链如下：");
            Throwable current = t;
            for (int depth = 0; current != null && depth < 8; depth++) {
                log.error("  [{}] {} : {}", depth,
                        current.getClass().getName(), current.getMessage());
                current = current.getCause();
            }
        } finally {
            cleanup(userId, sessionId);
        }

        summary();
    }

    /** 删除本次会话（级联清理作答记录、题目快照、划词标记）与本次产生的生词本条目。 */
    private void cleanup(Long userId, Long sessionId) {
        try {
            if (sessionId != null) {
                practiceSessionMapper.deleteById(sessionId);
            }
            if (userId != null) {
                userVocabularyMapper.delete(
                        Wrappers.<UserVocabulary>lambdaQuery()
                                .eq(UserVocabulary::getUserId, userId)
                                .in(UserVocabulary::getNormalizedForm, List.of("run", "harvest")));
            }
            log.info("阶段 5 清理：删除会话 {} 及本次的生词本条目", sessionId);
        } catch (Throwable t) {
            log.warn("阶段 5 清理失败（不影响验证结论）：{}", t.getMessage());
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
            log.info("================= ✅ 端到端主流程验证通过 =================");
        } else {
            log.error("通过 {}/{} 项，失败 {} 项", passed, passed + failed, failed);
            log.error("================= ❌ 端到端主流程验证存在失败 =================");
        }
    }
}
