package org.example.englishquestionbank.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库连通性自检。
 *
 * <p>实现 {@link CommandLineRunner} 后，Spring Boot 会在「容器启动完成」这个时机
 * 自动调用一次 {@link #run}，不需要你手动触发。
 *
 * <p>这里分四层逐级确认数据库是真的通了，而不是"看起来像通了"：
 * <ol>
 *   <li>从连接池借出一个 Connection —— 验证账号密码 / 网络 / 库名</li>
 *   <li>读元数据（产品名、版本、地址、当前库）—— 验证连的是你以为的那个库</li>
 *   <li>真的执行一条 SQL（SELECT 1）—— 能建连接不代表语句一定能跑</li>
 *   <li>查 information_schema，列出当前库所有表 —— 验证 Navicat 建的表读得到</li>
 * </ol>
 *
 * <p>这是开发期辅助类，项目稳定后可以直接删掉，或加
 * {@code @Profile("dev")} 限制只在开发环境生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseConnectionChecker implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        log.info("================ 数据库连接自检开始 ================");
        try (Connection conn = dataSource.getConnection()) {

            log.info("[1/4] 从连接池借到 Connection ............ 通过");

            var meta = conn.getMetaData();
            log.info("[2/4] 数据库产品 : {} {}", meta.getDatabaseProductName(), meta.getDatabaseProductVersion());
            log.info("      连接地址   : {}", meta.getURL());
            log.info("      登录用户   : {}", meta.getUserName());
            log.info("      当前数据库 : {}", conn.getCatalog());

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
                log.info("[3/4] 执行 SELECT 1 ............ 返回 {}，SQL 可正常执行", rs.getInt(1));
            }

            List<String> tables = new ArrayList<>();
            String sql = "SELECT table_name FROM information_schema.tables "
                       + "WHERE table_schema = DATABASE() ORDER BY table_name";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            log.info("[4/4] 当前库共 {} 张表 :", tables.size());
            for (String t : tables) {
                log.info("        - {}", t);
            }

            if (tables.isEmpty()) {
                log.warn("       ⚠ 库是通的，但没有表：确认 Navicat 里建表时选中的是 english_question_bank 这个库");
            }

            log.info("================ ✅ 数据库连接成功 ================");

        } catch (Exception e) {
            log.error("================ ❌ 数据库连接失败 ================");
            log.error("异常类型 : {}", e.getClass().getName());
            log.error("异常信息 : {}", e.getMessage());
            log.error("排查清单 :");
            log.error("  1) application.yml 的 spring.datasource.password 是否已改成真实密码");
            log.error("     （若仍是占位符「你的密码」，报错会是 Access denied）");
            log.error("  2) 库名是否与 Navicat 里完全一致：english_question_bank");
            log.error("  3) Windows 服务 MySQL80 是否处于「正在运行」");
            log.error("  4) 端口是否为 3306（见 D:\\MySQL\\MySQL Server 8.0\\my.ini）");
            log.error("  5) 报 'Public Key Retrieval is not allowed' 说明 URL 里少了 allowPublicKeyRetrieval=true");
            log.error("  6) 报 'Unknown database' 说明库名写错，或表建在了别的库里");
            log.error("==================================================");
            // 这里故意不抛异常：让应用继续启动，这样你还能访问
            // http://localhost:8080/actuator/health 看到 db 组件的 DOWN 及详细原因。
        }
    }
}
