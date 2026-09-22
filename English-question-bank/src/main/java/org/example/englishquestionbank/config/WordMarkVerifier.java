package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.bootstrap.SampleDataInitializer;
import org.example.englishquestionbank.dto.MarkCommand;
import org.example.englishquestionbank.dto.MarkResult;
import org.example.englishquestionbank.entity.Passage;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.SessionQuestion;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.entity.UserVocabulary;
import org.example.englishquestionbank.entity.UserWordMark;
import org.example.englishquestionbank.mapper.PassageMapper;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.SessionQuestionMapper;
import org.example.englishquestionbank.mapper.UserVocabularyMapper;
import org.example.englishquestionbank.mapper.UserWordMarkMapper;
import org.example.englishquestionbank.service.PracticeSessionService;
import org.example.englishquestionbank.service.SysUserService;
import org.example.englishquestionbank.service.WordMarkService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 划词标记链路验证器（开发期辅助）。
 *
 * <p><b>为什么单开一个类</b>：{@code WritePathVerifier} 已经覆盖了「单表写入」和
 * 「跨表事务」两个阶段，再往里塞划词验证会让单个文件过长。
 * 划词是本项目写入最复杂的路径，值得独立验证。
 *
 * <h2>覆盖的验证点</h2>
 * <ol>
 *   <li>词形归一化在<b>真实写入路径</b>上的表现（{@code running, → run}）</li>
 *   <li><b>数据库生成列</b>：{@code session_key} / {@code question_key} 是否被正确计算 ——
 *       这两个列实体刻意没有映射，这是第一次真正执行相关 insert</li>
 *   <li>{@code question_id} 为 NULL 时，生成列是否归零为 0（alter-02 修复的就是这个问题）</li>
 *   <li>生词本 upsert 的<b>累加</b>语义（同一词标两次 → {@code mark_count == 2}）</li>
 *   <li>{@code session_question.marked_word_count} 的原子自增</li>
 *   <li>同一位置重复标记是<b>幂等空操作</b>，不新增行、不重复累加</li>
 *   <li><b>事务回滚</b> —— 写入成功后抛异常，已写入的标记必须被撤销</li>
 * </ol>
 *
 * <p><b>数据清理</b>：验证结束后删除本次创建的会话（连带级联删除标记）
 * 与本次涉及的生词本条目，不留残留。
 *
 * <p>这是开发期辅助类，链路确认无误后可以删除。
 */
@Slf4j
@Component
@Order(20)   // 晚于 SampleDataInitializer(1) 与 WritePathVerifier(10)
@RequiredArgsConstructor
public class WordMarkVerifier implements CommandLineRunner {

    private final SysUserService sysUserService;
    private final PracticeSessionService practiceSessionService;
    private final WordMarkService wordMarkService;
    private final TransactionRollbackProbe rollbackProbe;

    private final PassageMapper passageMapper;
    private final QuestionMapper questionMapper;
    private final PracticeSessionMapper practiceSessionMapper;
    private final SessionQuestionMapper sessionQuestionMapper;
    private final UserWordMarkMapper userWordMarkMapper;
    private final UserVocabularyMapper userVocabularyMapper;

    private int passed = 0;
    private int failed = 0;

    @Override
    public void run(String... args) {
        log.info("================= 写入链路验证 · 阶段 3（划词标记）=================");
        Long sessionId = null;
        Long userId = null;
        try {
            // ---------- 前置数据 ----------
            SysUser demoUser = sysUserService.findByUsername(SampleDataInitializer.DEMO_USERNAME);
            Passage passage = passageMapper.selectOne(
                    Wrappers.<Passage>lambdaQuery()
                            .eq(Passage::getSource, SampleDataInitializer.SAMPLE_SOURCE)
                            .last("LIMIT 1"));
            if (demoUser == null || passage == null) {
                log.warn("✗ 缺少示例数据，跳过阶段 3 验证");
                failed++;
                return;
            }
            userId = demoUser.getId();

            List<Question> questions = questionMapper.selectList(
                    Wrappers.<Question>lambdaQuery()
                            .eq(Question::getPassageId, passage.getId())
                            .orderByAsc(Question::getSeq));
            Long questionId = questions.get(0).getId();

            // 开一个会话作为划词的容器
            PracticeSession session = practiceSessionService.startSession(userId, passage.getId());
            sessionId = session.getId();

            long marksBefore = countMarks(userId, sessionId);

            // ---------- [1] 首次标记 + 词形归一化 ----------
            // 故意把标点一起送来（"running,"），验证服务会清洗
            MarkResult first = wordMarkService.markWord(new MarkCommand(
                    userId, sessionId, questionId, passage.getId(),
                    "STEM", "running,", "He is running fast.", 5, 12));
            check("首次标记 + 词形归一化（running, → run）",
                    "run".equals(first.normalizedForm())
                            && first.wordId() != null
                            && !first.alreadyMarked()
                            && first.translation() != null,
                    String.format("normalized=%s wordId=%s alreadyMarked=%s translation=%.20s",
                            first.normalizedForm(), first.wordId(), first.alreadyMarked(),
                            String.valueOf(first.translation())));

            // ---------- [2] 生成列是否正确计算 ----------
            Map<String, Object> keys = selectGeneratedKeys(first.markId());
            check("生成列 session_key / question_key 计算正确",
                    keys != null
                            && eq(keys.get("session_key"), sessionId)
                            && eq(keys.get("question_key"), questionId),
                    String.format("session_key=%s（期望 %s）question_key=%s（期望 %s）",
                            keys == null ? "-" : keys.get("session_key"), sessionId,
                            keys == null ? "-" : keys.get("question_key"), questionId));

            // ---------- [3] 生词本首次写入 ----------
            Integer count1 = vocabMarkCount(userId, "run");
            check("生词本首次写入 mark_count=1", Integer.valueOf(1).equals(count1),
                    "mark_count=" + count1);

            // ---------- [4] 重复标记同一位置：幂等空操作 ----------
            MarkResult again = wordMarkService.markWord(new MarkCommand(
                    userId, sessionId, questionId, passage.getId(),
                    "STEM", "running", "He is running fast.", 5, 12));
            long marksAfterDup = countMarks(userId, sessionId);
            Integer count2 = vocabMarkCount(userId, "run");
            check("同位置重复标记是幂等空操作",
                    again.alreadyMarked()
                            && marksAfterDup == marksBefore + 1
                            && Integer.valueOf(1).equals(count2),
                    String.format("alreadyMarked=%s 标记行数=%d（期望 %d）mark_count=%s（期望 1）",
                            again.alreadyMarked(), marksAfterDup, marksBefore + 1, count2));

            // ---------- [5] 不同位置标记同一个词：新增行但生词本仍累加 ----------
            MarkResult other = wordMarkService.markWord(new MarkCommand(
                    userId, sessionId, questionId, passage.getId(),
                    "STEM", "running", "Running is good for you.", 40, 47));
            long marksAfterSecond = countMarks(userId, sessionId);
            Integer count3 = vocabMarkCount(userId, "run");
            check("不同位置标记同词：新增行 + 生词本累加",
                    !other.alreadyMarked()
                            && marksAfterSecond == marksBefore + 2
                            && Integer.valueOf(2).equals(count3),
                    String.format("标记行数=%d（期望 %d）mark_count=%s（期望 2）",
                            marksAfterSecond, marksBefore + 2, count3));

            // ---------- [6] question_id 为 NULL 时生成列归零 ----------
            MarkResult noQuestion = wordMarkService.markWord(new MarkCommand(
                    userId, sessionId, null, passage.getId(),
                    "PASSAGE", "harvest", "the harvest is rarely enough", 8, 15));
            Map<String, Object> keys2 = selectGeneratedKeys(noQuestion.markId());
            check("question_id 为空时 question_key 归零",
                    keys2 != null
                            && eq(keys2.get("session_key"), sessionId)
                            && eq(keys2.get("question_key"), 0L),
                    String.format("session_key=%s（期望 %s）question_key=%s（期望 0）",
                            keys2 == null ? "-" : keys2.get("session_key"), sessionId,
                            keys2 == null ? "-" : keys2.get("question_key")));

            // ---------- [7] session_question.marked_word_count 原子自增 ----------
            Integer markedCount = markedWordCount(sessionId, questionId);
            // 落在该题上的标记共 3 次（[1] [4] 是同一位置，[4] 未新增 → 实际新增 2 次：[1] 与 [5]）
            check("session_question.marked_word_count 自增正确",
                    Integer.valueOf(2).equals(markedCount),
                    "marked_word_count=" + markedCount + "（期望 2：首次 + 换位置各一次）");

            // ---------- [8] 事务回滚 ----------
            long beforeRollback = countMarks(userId, sessionId);
            Integer vocabBeforeRollback = vocabMarkCount(userId, "outdoor");
            boolean threw = false;
            try {
                rollbackProbe.markThenFail(new MarkCommand(
                        userId, sessionId, questionId, passage.getId(),
                        "STEM", "outdoor", "outdoor activities", 60, 67));
            } catch (IllegalStateException e) {
                threw = true;
            }
            long afterRollback = countMarks(userId, sessionId);
            Integer vocabAfterRollback = vocabMarkCount(userId, "outdoor");
            check("事务回滚：写入后抛异常，标记被撤销",
                    threw && afterRollback == beforeRollback
                            && java.util.Objects.equals(vocabBeforeRollback, vocabAfterRollback),
                    String.format("抛异常=%s 标记行数 %d → %d（应不变）outdoor 生词本 %s → %s（应不变）",
                            threw, beforeRollback, afterRollback,
                            vocabBeforeRollback, vocabAfterRollback));

        } catch (Throwable t) {
            failed++;
            // 只打 getMessage() 往往不够 —— Spring 把底层 SQLException 包成
            // DataIntegrityViolationException 时 message 可能是空的，
            // 真正的原因藏在 cause 链里。这里把整条链打出来。
            log.error("✗ 阶段 3 出现未预期的异常，因果链如下：");
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

    // =====================================================================
    //  辅助
    // =====================================================================

    private long countMarks(Long userId, Long sessionId) {
        return userWordMarkMapper.selectCount(
                Wrappers.<UserWordMark>lambdaQuery()
                        .eq(UserWordMark::getUserId, userId)
                        .eq(UserWordMark::getSessionId, sessionId));
    }

    private Integer vocabMarkCount(Long userId, String normalizedForm) {
        UserVocabulary v = userVocabularyMapper.selectOne(
                Wrappers.<UserVocabulary>lambdaQuery()
                        .eq(UserVocabulary::getUserId, userId)
                        .eq(UserVocabulary::getNormalizedForm, normalizedForm)
                        .last("LIMIT 1"));
        return v == null ? null : v.getMarkCount();
    }

    private Integer markedWordCount(Long sessionId, Long questionId) {
        SessionQuestion sq = sessionQuestionMapper.selectOne(
                Wrappers.<SessionQuestion>lambdaQuery()
                        .eq(SessionQuestion::getSessionId, sessionId)
                        .eq(SessionQuestion::getQuestionId, questionId)
                        .last("LIMIT 1"));
        return sq == null ? null : sq.getMarkedWordCount();
    }

    /**
     * 读取生成列的值。
     *
     * <p>{@code session_key} / {@code question_key} 是 VIRTUAL 生成列，
     * <b>实体刻意没有映射它们</b>，所以不能用 {@code selectById} 拿到。
     * 这里用 {@code selectMaps} 显式指定列名 —— 既不需要为「仅测试用」的查询
     * 往生产 Mapper 里加方法，也不用引入 JdbcTemplate 这套额外机制。
     */
    private Map<String, Object> selectGeneratedKeys(Long markId) {
        List<Map<String, Object>> rows = userWordMarkMapper.selectMaps(
                Wrappers.<UserWordMark>query()
                        .select("id", "session_key", "question_key")
                        .eq("id", markId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 比较数据库返回的数值与期望值，处理 Long / BigInteger 等类型差异。 */
    private static boolean eq(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    /** 清理本次验证产生的数据：会话（连带级联删除标记）+ 本次涉及的生词本条目。 */
    private void cleanup(Long userId, Long sessionId) {
        try {
            if (sessionId != null) {
                practiceSessionMapper.deleteById(sessionId);   // 级联删除 user_word_mark
            }
            if (userId != null) {
                int removed = userVocabularyMapper.delete(
                        Wrappers.<UserVocabulary>lambdaQuery()
                                .eq(UserVocabulary::getUserId, userId)
                                .in(UserVocabulary::getNormalizedForm,
                                        List.of("run", "harvest", "outdoor")));
                log.info("阶段 3 清理：删除会话 {}（级联删标记），删除生词本条目 {} 条",
                        sessionId, removed);
            }
        } catch (Throwable t) {
            log.warn("阶段 3 清理失败（不影响验证结论）：{}", t.getMessage());
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
            log.info("================= ✅ 划词标记链路验证通过 =================");
        } else {
            log.error("通过 {}/{} 项，失败 {} 项", passed, passed + failed, failed);
            log.error("================= ❌ 划词标记链路验证存在失败 =================");
        }
    }
}
