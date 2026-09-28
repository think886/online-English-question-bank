package org.example.englishquestionbank.dto;

import java.util.List;

/**
 * 通用分页结果（Service 层出口）。
 *
 * <h2>为什么不直接把 MyBatis-Plus 的 {@code IPage} 返回出去</h2>
 * {@code IPage} 是持久层框架的类型。若 Service 直接返回它，API 层就不得不
 * import MyBatis-Plus —— 于是「换持久层框架」这件事会波及 Web 层，
 * 而 Web 层本不该知道数据是怎么取的。
 * 本 record 只用 JDK 类型，把框架细节挡在 Service 之内。
 *
 * <p>转换在 Service 实现里做（{@code IPage} → 本类），只有几行。
 *
 * @param records 当前页的数据
 * @param total   总记录数（由分页插件自动执行的 COUNT 查询得出）
 * @param page    当前页码，<b>从 1 开始</b>
 * @param size    每页条数。注意它可能<strong>小于</strong>请求里传的值 ——
 *                分页插件有单页上限（{@code MybatisPlusConfig} 里设为 100），
 *                超限时按上限处理。调用方应以本字段为准
 * @param pages   总页数；{@code total = 0} 时为 0
 * @param <T>     记录类型
 */
public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long size,
        long pages
) {
}
