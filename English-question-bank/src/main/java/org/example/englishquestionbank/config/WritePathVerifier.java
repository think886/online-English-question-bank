package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.bootstrap.SampleDataInitializer;
import org.example.englishquestionbank.entity.Passage;
import org.example.englishquestionbank.entity.PracticeSession;
import org.example.englishquestionbank.entity.Question;
import org.example.englishquestionbank.entity.SessionQuestion;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.mapper.PassageMapper;
import org.example.englishquestionbank.mapper.PracticeSessionMapper;
import org.example.englishquestionbank.mapper.QuestionMapper;
import org.example.englishquestionbank.mapper.SessionQuestionMapper;
import org.example.englishquestionbank.mapper.SysUserMapper;
import org.example.englishquestionbank.service.PracticeSessionService;
import org.example.englishquestionbank.service.SysUserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 写入链路验证器（开发期辅助）。
 *
 * <p><b>为什么需要它</b>：项目前期只验证过「读」——查询链路、实体映射都是只读的。
 * {@code insert} / {@code update} / {@code delete} / 事务曾经<strong>一次都没执行过</strong>。
 * 「能读」和「能写」是两回事，本类负责把写路径逐阶段敲实。
 *
 * <h2>阶段 1 · 单表写入</h2>
 * <ol>
 *   <li>{@code INSERT} 能否成功</li>
 *   <li>自增主键能否被 MyBatis-Plus 回填（{@code IdType.AUTO} 是否生效）</li>
 *   <li>数据库默认值（{@code role} / {@code userType} / {@code status}）是否正确写入</li>
 *   <li>{@code created_at} 是否由 {@code DEFAULT CURRENT_TIMESTAMP} 自动填充</li>
 *   <li>唯一索引 {@code uk_user_username} 是否真的拦住重名</li>
 *   <li>{@code DELETE} 是否可用</li>
 * </ol>
 *
 * <h2>阶段 2 · 跨表事务</h2>
 * <ol>
 *   <li>业务约束：对没有题目的文章开会话应被拒绝，且<b>不留下残留会话</b></li>
 *   <li>跨表写入：{@code practice_session} + {@code session_question} 同时成功</li>
 *   <li>XML 批量插入（{@code <foreach>}）是否正常工作</li>
 *   <li>外键 {@code ON DELETE CASCADE} 是否真的级联删除题目快照</li>
 * </ol>
 *
 * <p><b>数据清理</b>：验证用数据全部自行清理，不留残留。
 * 演示用户与示例文章由 {@link SampleDataInitializer} 持久提供，不在清理范围内。
 *
 * <p>这是开发期辅助类，写入链路确认无误后可以删除。
 */
@Slf4j
@Component
@Order(10)   // 必须晚于 SampleDataInitializer
@RequiredArgsConstructor
public class WritePathVerifier implements CommandLineRunner {

    private final SysUserService sysUserService;
    private final SysUserMapper sysUserMapper;
    private final PracticeSessionService practiceSessionService;
    private final PracticeSessionMapper practiceSessionMapper;
    private final SessionQuestionMapper sessionQuestionMapper;
    private final PassageMapper passageMapper;
    private final QuestionMapper questionMapper;

    private int passed = 0;
    private int failed = 0;

    @Override
    public void run(String... args) {
        log.info("================= 写入链路验证 · 阶段 1（单表写入）=================");
        verifySingleTableWrites();

        log.info("================= 写入链路验证 · 阶段 2（跨表事务）=================");
        verifyCrossTableTransaction();

        summary();
    }

    // =====================================================================
    //  阶段 1 · 单表写入
    // =====================================================================

    private void verifySingleTableWrites() {
        // 带时间戳，避免多次运行互相干扰；结束后会删除
        String username = "verify_" + System.currentTimeMillis();

        try {
            SysUser created = sysUserService.register(username, "写入验证用户");
            check("注册用户", created != null && created.getId() != null,
                    "id=" + (created == null ? "null" : created.getId()) + " username=" + username);

            if (created == null || created.getId() == null) {
                log.error("✗ 自增主键未回填，阶段 1 后续验证无法继续");
                return;
            }
            Long id = created.getId();

            SysUser byName = sysUserService.findByUsername(username);
            boolean fieldsOk = byName != null
                    && username.equals(byName.getUsername())
                    && "USER".equals(byName.getRole())
                    && Integer.valueOf(1).equals(byName.getUserType())
                    && Integer.valueOf(1).equals(byName.getStatus());
            check("按用户名查回且字段一致", fieldsOk,
                    byName == null ? "查不到" : String.format(
                            "role=%s userType=%s status=%s nickname=%s",
                            byName.getRole(), byName.getUserType(), byName.getStatus(),
                            byName.getNickname()));

            check("created_at 由数据库默认值填充", byName != null && byName.getCreatedAt() != null,
                    byName == null ? "-" : "createdAt=" + byName.getCreatedAt());

            SysUser byId = sysUserService.findById(id);
            check("按主键查回", byId != null && id.equals(byId.getId()),
                    byId == null ? "查不到" : "id=" + byId.getId());

            boolean duplicateRejected = false;
            String duplicateDetail;
            try {
                sysUserService.register(username, "重复的用户");
                duplicateDetail = "未抛出异常 —— 唯一索引可能失效！";
            } catch (DuplicateKeyException e) {
                duplicateRejected = true;
                duplicateDetail = "已拦截，抛 DuplicateKeyException";
            }
            check("唯一索引拦住重复用户名", duplicateRejected, duplicateDetail);

            check("existsByUsername 存在时返回 true",
                    sysUserService.existsByUsername(username), "true");

            int deleted = sysUserMapper.deleteById(id);
            boolean cleaned = deleted == 1 && !sysUserService.existsByUsername(username);
            check("删除验证数据（顺带验证 DELETE）", cleaned,
                    "affectedRows=" + deleted + "，删除后 existsByUsername="
                            + sysUserService.existsByUsername(username));

        } catch (Throwable t) {
            failed++;
            log.error("✗ 阶段 1 出现未预期的异常：{} - {}", t.getClass().getName(), t.getMessage());
        }
    }

    // =====================================================================
    //  阶段 2 · 跨表事务
    // =====================================================================

    private void verifyCrossTableTransaction() {
        try {
            // ---------- 前置：拿到示例数据 ----------
            SysUser demoUser = sysUserService.findByUsername(SampleDataInitializer.DEMO_USERNAME);
            Passage passage = passageMapper.selectOne(
                    Wrappers.<Passage>lambdaQuery()
                            .eq(Passage::getSource, SampleDataInitializer.SAMPLE_SOURCE)
                            .last("LIMIT 1"));
            if (demoUser == null || passage == null) {
                log.warn("✗ 缺少示例数据（演示用户或示例文章），跳过阶段 2 验证");
                failed++;
                return;
            }

            List<Question> questions = questionMapper.selectList(
                    Wrappers.<Question>lambdaQuery()
                            .eq(Question::getPassageId, passage.getId())
                            .orderByAsc(Question::getSeq));

            // ---------- [1] 业务约束：空文章不允许开会话，且不留残留 ----------
            long sessionsBefore = practiceSessionMapper.selectCount(Wrappers.emptyWrapper());
            boolean rejected = false;
            String rejectDetail;
            try {
                practiceSessionService.startSession(demoUser.getId(), -1L);
                rejectDetail = "对不存在的文章开会话竟然成功了！";
            } catch (IllegalStateException | IllegalArgumentException e) {
                rejected = true;
                rejectDetail = "已拒绝：" + e.getClass().getSimpleName();
            }
            long sessionsAfter = practiceSessionMapper.selectCount(Wrappers.emptyWrapper());
            check("空文章拒绝开会话且不留残留", rejected && sessionsBefore == sessionsAfter,
                    rejectDetail + "；会话数 " + sessionsBefore + " → " + sessionsAfter);

            // ---------- [2] 正常开会话 ----------
            PracticeSession session = practiceSessionService.startSession(
                    demoUser.getId(), passage.getId());
            boolean sessionOk = session != null && session.getId() != null
                    && Integer.valueOf(questions.size()).equals(session.getTotalCount())
                    && "IN_PROGRESS".equals(session.getStatus())
                    && Integer.valueOf(questions.size()).equals(session.getMaxScore());
            check("开始会话（跨表写入成功）", sessionOk,
                    session == null ? "返回 null" : String.format(
                            "sessionId=%s totalCount=%s maxScore=%s status=%s",
                            session.getId(), session.getTotalCount(),
                            session.getMaxScore(), session.getStatus()));

            if (session == null || session.getId() == null) {
                log.error("✗ 会话未创建成功，阶段 2 后续验证无法继续");
                return;
            }

            // ---------- [3] 题目快照（验证 XML 批量插入）----------
            List<SessionQuestion> snapshots = sessionQuestionMapper.selectList(
                    Wrappers.<SessionQuestion>lambdaQuery()
                            .eq(SessionQuestion::getSessionId, session.getId())
                            .orderByAsc(SessionQuestion::getSortOrder));
            boolean snapshotOk = snapshots.size() == questions.size()
                    && snapshots.get(0).getSortOrder() == 1
                    && "UNANSWERED".equals(snapshots.get(0).getStatus());
            check("XML 批量插入写入题目快照", snapshotOk,
                    String.format("快照 %d 条 / 题目 %d 道，首条 sortOrder=%s status=%s",
                            snapshots.size(), questions.size(),
                            snapshots.isEmpty() ? "-" : snapshots.get(0).getSortOrder(),
                            snapshots.isEmpty() ? "-" : snapshots.get(0).getStatus()));

            // ---------- [4] 外键级联：删会话应连带删快照 ----------
            int sessionsDeleted = practiceSessionMapper.deleteById(session.getId());
            long snapshotsLeft = sessionQuestionMapper.selectCount(
                    Wrappers.<SessionQuestion>lambdaQuery()
                            .eq(SessionQuestion::getSessionId, session.getId()));
            check("删除会话级联删除题目快照", sessionsDeleted == 1 && snapshotsLeft == 0,
                    String.format("删除会话 %d 行，剩余快照 %d 条（ON DELETE CASCADE）",
                            sessionsDeleted, snapshotsLeft));

        } catch (Throwable t) {
            failed++;
            log.error("✗ 阶段 2 出现未预期的异常：{} - {}", t.getClass().getName(), t.getMessage());
        }
    }

    // =====================================================================

    private void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        // 结果标记放在最前面，避免用 %-34s 做列对齐（String.format 的宽度按字符数算，
        // 中文是双宽字符，补空格会让列参差不齐）。
        //
        // ⚠⚠ SLF4J 只认「恰好是 {}」的占位符。写成 {:<2} / {:<12} 这类带格式说明的写法
        //    不会被识别，会导致参数错位、后面的 arg 被静默丢弃（现象是日志少内容，不报错）。
        //    规则：log 里只用裸 {}，需要对齐就先 String.format 拼好整行再作为单个参数传入。
        log.info("  {} {}", ok ? "✅" : "❌", label + "  " + detail);
    }

    private void summary() {
        log.info("------------------------------------------------------------------");
        if (failed == 0) {
            log.info("通过 {}/{} 项", passed, passed + failed);
            log.info("=================== ✅ 写入链路验证全部通过 ===================");
        } else {
            log.error("通过 {}/{} 项，失败 {} 项", passed, passed + failed, failed);
            log.error("=================== ❌ 写入链路验证存在失败 ===================");
        }
    }
}
