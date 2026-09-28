package org.example.englishquestionbank.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置 —— 目前只装分页插件。
 *
 * <h2>为什么分页要靠插件，而不是手写 LIMIT</h2>
 * 项目规范（见根目录 {@code AGENTS.md}）把「取前 N 条 / 分页」归为<strong>复杂操作</strong>，
 * 因为构造器只能靠 {@code .last("LIMIT " + n)} 拼字符串，有注入风险。
 * 插件则是在 SQL 解析层改写语句：MyBatis-Plus 自己生成 {@code LIMIT ?, ?} 并用预编译参数，
 * 同时<strong>自动执行一条 COUNT 查询</strong>把总条数带回来 ——
 * 这正是「生词本一共多少个词」这类展示所需要的。
 *
 * <h2>⚠️ 必须同时引入 mybatis-plus-jsqlparser</h2>
 * 分页拦截器靠 JSqlParser 解析并改写 SQL。MyBatis-Plus 从 3.5.9 起把 JSqlParser
 * 拆成了独立 artifact，不再随 starter 传递。少了它会在<strong>运行时</strong>抛
 * {@code NoClassDefFoundError: net/sf/jsqlparser/...} ——
 * 编译期完全看不出来，属于很容易漏的坑。见 {@code pom.xml} 中的说明。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 单页最大条数。
     *
     * <p><b>这是一个安全阀，不是性能调优</b>：接口暴露 {@code ?size=} 参数，
     * 若客户端传 {@code size=1000000}，数据库会真去取一百万行并全部塞进响应体。
     * 限制在 100 之后，无论外面传什么都不可能把库拖垮。
     * 超出时会<strong>自动按 100 处理</strong>（而不是报错），调用方从响应的
     * {@code size} 字段就能看出实际生效的值。
     */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);

        // 请求的页码超出总页数时返回【空列表】，而不是绕回第一页。
        //
        // 为什么要显式声明：overflow=true 时「翻过头了又回到第 1 页」会让前端
        // 陷入死循环（加载更多 → 又拿到第 1 页数据），而空列表能让前端明确知道「到底了」。
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
