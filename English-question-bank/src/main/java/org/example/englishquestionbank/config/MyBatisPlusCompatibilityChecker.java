package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 与 Spring Boot 4.1.1 的兼容性自检。
 *
 * <p>为什么需要这个类：MyBatis-Plus 官方声明支持的是 Spring Boot <b>4.0.0</b>，
 * 而本项目用的是 <b>4.1.1</b>。跨小版本通常没问题，但没有官方保证，
 * 而且 3.5.14 / 3.5.15 确实存在 Boot 4 适配缺陷（GitHub issue #6970）。
 *
 * <p>判断依据很直接：只要能成功注入 {@link SqlSessionFactory}，
 * 就说明 MyBatis-Plus 的自动装配在 Boot 4.1.1 下正常生效了。
 * 如果装配失败，应用压根起不来，会在启动阶段就抛 NoSuchBeanDefinitionException。
 *
 * <p>这是开发期辅助类，兼容性确认无误后可以删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MyBatisPlusCompatibilityChecker implements CommandLineRunner {

    private final SqlSessionFactory sqlSessionFactory;

    @Override
    public void run(String... args) {
        log.info("================ MyBatis-Plus 兼容性自检 ================");
        try {
            var config = sqlSessionFactory.getConfiguration();

            log.info("SqlSessionFactory 实现 : {}", sqlSessionFactory.getClass().getName());
            log.info("MyBatis 配置实现       : {}", config.getClass().getName());

            // 打印 jar 路径，可直接看出实际生效的 MyBatis-Plus 版本号
            var jar = MybatisConfiguration.class.getProtectionDomain().getCodeSource().getLocation();
            log.info("mybatis-plus-core 位置 : {}", jar);

            log.info("下划线转驼峰           : {}", config.isMapUnderscoreToCamelCase());
            log.info("已注册 Mapper 数量     : {}", config.getMappedStatementNames().size());
            log.info("底层数据源             : {}", config.getEnvironment().getDataSource().getClass().getName());

            log.info("================ ✅ MyBatis-Plus 已正常装配 ================");
        } catch (Throwable t) {
            log.error("================ ❌ MyBatis-Plus 装配异常 ================");
            log.error("异常类型 : {}", t.getClass().getName());
            log.error("异常信息 : {}", t.getMessage());
            log.error("排查方向 :");
            log.error("  1) pom 里依赖坐标是否为 mybatis-plus-spring-boot4-starter（不是 boot3）");
            log.error("  2) 版本是否 >= 3.5.16（3.5.14/3.5.15 的 Boot 4 适配有缺陷，见 issue #6970）");
            log.error("  3) 是否存在 mybatis-spring 版本冲突（应为 4.0.0+）");
            log.error("=========================================================");
        }
    }
}
