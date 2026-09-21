package org.example.englishquestionbank.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.englishquestionbank.entity.SysUser;
import org.example.englishquestionbank.mapper.SysUserMapper;
import org.example.englishquestionbank.service.SysUserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 写入链路验证器（开发期辅助）。
 *
 * <p><b>为什么需要它</b>：在此之前项目只验证过「读」——查询链路、实体映射都是只读的。
 * 而 {@code insert} / {@code update} / {@code delete} / 事务<strong>从未执行过哪怕一次</strong>。
 * 「能读」和「能写」是两回事，本类负责把写的第一环敲实。
 *
 * <p><b>本步覆盖的验证点</b>：
 * <ol>
 *   <li>{@code INSERT} 本身能否成功</li>
 *   <li>自增主键能否被 MyBatis-Plus 回填到实体（{@code IdType.AUTO} 是否生效）</li>
 *   <li>数据库默认值（{@code role} / {@code userType} / {@code status}）是否正确写入</li>
 *   <li>{@code created_at} 是否由数据库 {@code DEFAULT CURRENT_TIMESTAMP} 自动填充</li>
 *   <li>唯一索引 {@code uk_user_username} 是否真的拦住重名（抛 {@code DuplicateKeyException}）</li>
 *   <li>{@code DELETE} 是否可用 —— 顺带验证，并用于清理验证数据</li>
 * </ol>
 *
 * <p><b>数据清理</b>：验证用的用户名带时间戳，验证结束后会自行删除，<strong>不留下残留数据</strong>。
 *
 * <p>这是开发期辅助类，写入链路确认无误后可以删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WritePathVerifier implements CommandLineRunner {

    private final SysUserService sysUserService;
    private final SysUserMapper sysUserMapper;

    private int passed = 0;
    private int failed = 0;

    @Override
    public void run(String... args) {
        log.info("========= 写入链路验证 · 第 1 步（INSERT / SELECT / DELETE）=========");

        // 带时间戳，避免多次运行互相干扰；结束后会删除
        String username = "verify_" + System.currentTimeMillis();

        try {
            // ---------- [1] 注册 ----------
            SysUser created = sysUserService.register(username, "写入验证用户");
            check("注册用户", created != null && created.getId() != null,
                    "id=" + (created == null ? "null" : created.getId()) + " username=" + username);

            if (created == null || created.getId() == null) {
                // 主键没回填的话后续步骤全部无意义，直接中止
                log.error("✗ 自增主键未回填，后续验证无法继续");
                summary();
                return;
            }
            Long id = created.getId();

            // ---------- [2] 按用户名查回 ----------
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

            // ---------- [3] 数据库默认值：created_at ----------
            boolean timeOk = byName != null && byName.getCreatedAt() != null;
            check("created_at 由数据库默认值填充", timeOk,
                    byName == null ? "-" : "createdAt=" + byName.getCreatedAt());

            // ---------- [4] 按 id 查回 ----------
            SysUser byId = sysUserService.findById(id);
            check("按主键查回", byId != null && id.equals(byId.getId()),
                    byId == null ? "查不到" : "id=" + byId.getId());

            // ---------- [5] 唯一约束：重复注册同名 ----------
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

            // ---------- [6] existsByUsername ----------
            check("existsByUsername 存在时返回 true",
                    sysUserService.existsByUsername(username), "true");

            // ---------- [7] 清理：顺带验证 DELETE ----------
            int deleted = sysUserMapper.deleteById(id);
            boolean cleaned = deleted == 1 && !sysUserService.existsByUsername(username);
            check("删除验证数据（顺带验证 DELETE）", cleaned,
                    "affectedRows=" + deleted + "，删除后 existsByUsername="
                            + sysUserService.existsByUsername(username));

        } catch (Throwable t) {
            failed++;
            log.error("✗ 写入链路出现未预期的异常");
            log.error("  类型 : {}", t.getClass().getName());
            log.error("  信息 : {}", t.getMessage());
        }

        summary();
    }

    private void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        // 结果标记放在最前面，避免用 %-34s 做列对齐。
        // 原因：String.format 的宽度按「字符数」计算，而中文是双宽字符，补空格会让列参差不齐。
        //
        // ⚠⚠ 这里踩过三次同一个坑：SLF4J 只认「恰好是 {}」的占位符。
        //    写成 {:<2} / {:<12} 这类带格式说明的写法不会被识别，会导致参数错位、
        //    后面的 arg 被静默丢弃（现象是日志里少内容，不报错）。
        //    规则：log 里只用裸 {}，需要对齐就先 String.format 拼好整行再作为单个参数传入。
        log.info("  {} {}", ok ? "✅" : "❌", label + "  " + detail);
    }

    private void summary() {
        log.info("------------------------------------------------------------------");
        if (failed == 0) {
            log.info("通过 {}/{} 项", passed, passed + failed);
            log.info("================== ✅ 写入链路（第 1 步）正常 ==================");
        } else {
            log.error("通过 {}/{} 项，失败 {} 项", passed, passed + failed, failed);
            log.error("================== ❌ 写入链路（第 1 步）异常 ==================");
        }
    }
}
