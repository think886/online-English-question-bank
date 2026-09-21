package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「实体 ↔ 数据库表」映射自检。
 *
 * <p><b>存在的意义</b>：实体字段是手写的，一个列名笔误会造成极难排查的问题——
 * 编译能过、启动能过，直到真正执行那条 SQL 才报 {@code Unknown column}。
 * 本类在启动时就把 11 个实体映射的列全部拿去和 {@code information_schema} 比对，
 * 让错误立刻暴露。
 *
 * <p>比对规则：
 * <ul>
 *   <li><b>实体映射的列必须真实存在</b> —— 否则判为错误</li>
 *   <li><b>表里有列没被实体映射</b> —— 只作提示，不算错误。这是预期的：
 *       {@code user_word_mark} 的 {@code session_key} / {@code question_key}
 *       是 VIRTUAL 生成列，刻意不映射进实体</li>
 * </ul>
 *
 * <p>属于开发期辅助类，映射确认无误后可以删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityMappingChecker implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        log.info("================ 实体 ↔ 表 映射自检 ================");
        try {
            Map<String, Set<String>> actualColumns = loadActualColumns();
            List<TableInfo> tableInfos = new ArrayList<>(TableInfoHelper.getTableInfos());

            if (tableInfos.isEmpty()) {
                log.error("✗ 没有任何已初始化的实体（TableInfo 数量为 0），Mapper 可能没被扫描到");
                return;
            }

            List<String> errors = new ArrayList<>();
            int passCount = 0;

            for (TableInfo ti : tableInfos) {
                String entity = ti.getEntityType().getSimpleName();
                String table = ti.getTableName();
                Set<String> actual = actualColumns.get(table.toLowerCase());

                if (actual == null) {
                    errors.add(String.format("%s 声明的表 `%s` 在数据库中不存在", entity, table));
                    continue;
                }

                // 实体映射的列 = 主键列 + 所有普通字段列
                Set<String> mapped = new LinkedHashSet<>();
                if (ti.getKeyColumn() != null) {
                    mapped.add(ti.getKeyColumn().toLowerCase());
                }
                for (TableFieldInfo field : ti.getFieldList()) {
                    mapped.add(field.getColumn().toLowerCase());
                }

                Set<String> missing = new LinkedHashSet<>(mapped);
                missing.removeAll(actual);

                if (!missing.isEmpty()) {
                    errors.add(String.format("%s -> 表 `%s` 映射了不存在的列: %s", entity, table, missing));
                    continue;
                }

                Set<String> unmapped = new LinkedHashSet<>(actual);
                unmapped.removeAll(mapped);

                passCount++;
                // 注意：SLF4J 的 {} 不支持宽度语法，必须先用 String.format 拼好整行
                String line = String.format("  ✓ %-18s -> %-18s 已映射 %2d / 实际 %2d 列%s",
                        entity, table, mapped.size(), actual.size(),
                        unmapped.isEmpty() ? "" : "   [未映射: " + String.join(", ", unmapped) + "]");
                log.info("{}", line);
            }

            log.info("--------------------------------------------------");
            if (errors.isEmpty()) {
                log.info("共 {} 个实体，全部映射正确", passCount);
                log.info("================ ✅ 实体映射无误 ================");
            } else {
                log.error("发现 {} 处映射错误：", errors.size());
                for (String e : errors) {
                    log.error("  ✗ {}", e);
                }
                log.error("================ ❌ 实体映射有误 ================");
            }
        } catch (Throwable t) {
            log.error("================ ❌ 实体映射自检异常 ================");
            log.error("异常类型 : {}", t.getClass().getName());
            log.error("异常信息 : {}", t.getMessage());
        }
    }

    /** 读取当前库所有表及其真实列名（全部转小写便于比对）。 */
    private Map<String, Set<String>> loadActualColumns() throws Exception {
        Map<String, Set<String>> result = new HashMap<>();
        String sql = "SELECT table_name, column_name FROM information_schema.columns "
                   + "WHERE table_schema = DATABASE()";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String table = rs.getString(1).toLowerCase();
                String column = rs.getString(2).toLowerCase();
                result.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(column);
            }
        }
        return result;
    }
}
