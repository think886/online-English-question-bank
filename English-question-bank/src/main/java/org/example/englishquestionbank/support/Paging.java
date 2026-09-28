package org.example.englishquestionbank.support;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.englishquestionbank.dto.PageResult;

/**
 * 分页参数归一化与结果转换。
 *
 * <h2>为什么必须有这一层（不是为了省几行代码）</h2>
 * MyBatis-Plus 的 {@code Page} 有一个<strong>危险行为</strong>：
 * 当 {@code size <= 0} 时它<strong>不做分页</strong>，直接返回全表数据。
 * 于是 {@code GET /api/me/vocabulary?size=0} 会把这个用户的全部生词一次性吐出来 ——
 * 而调用方看起来只是"传了个奇怪的值"，属于典型的「参数校验漏洞」。
 * 把 clamp 收敛到一处，就不会有哪个接口忘了防。
 *
 * <p>上界交给分页插件（{@code MybatisPlusConfig} 里的 {@code maxLimit}）兜底，
 * 这里只防下界与缺省；两处都做，是因为它们的失效方式不同：
 * 插件管「传得太大」，本类管「传得太小或没传」。
 */
public final class Paging {

    /** 调用方没指定每页条数时用多少。 */
    public static final long DEFAULT_SIZE = 20L;

    /** 每页条数上界，与 {@code MybatisPlusConfig} 里的 {@code maxLimit} 保持一致。 */
    public static final long MAX_SIZE = 100L;

    private Paging() {
    }

    /**
     * 构造一个已归一化的分页参数。
     *
     * @param page 页码，从 1 开始；小于 1 时按 1 处理
     * @param size 每页条数；小于 1 时按 {@link #DEFAULT_SIZE} 处理，大于 {@link #MAX_SIZE} 时按上限处理
     * @return 可直接交给 Mapper 的分页对象
     */
    public static <T> Page<T> of(long page, long size) {
        long safePage = page < 1L ? 1L : page;
        long safeSize = size < 1L ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new Page<>(safePage, safeSize);
    }

    /**
     * 把 MyBatis-Plus 的分页结果转成 Service 层的中立类型。
     *
     * <p>转换放在这里而不是让 Service 直接返回 {@code IPage}：
     * 那会把持久层框架的类型泄漏到 API 层（见 {@link PageResult} 的说明）。
     *
     * @param page 分页插件回填过 {@code total} 的对象
     * @return 只含 JDK 类型的分页结果
     */
    public static <T> PageResult<T> toResult(IPage<T> page) {
        return new PageResult<>(
                page.getRecords(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                page.getPages());
    }
}
