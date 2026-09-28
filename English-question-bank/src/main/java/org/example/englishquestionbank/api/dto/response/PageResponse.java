package org.example.englishquestionbank.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.englishquestionbank.dto.PageResult;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应的统一外壳。
 *
 * <p>所有清单类接口（生词本、错题本、练习历史）共用这一个结构，
 * 前端只需要写一套分页组件。
 *
 * @param records 当前页数据
 * @param total   总条数
 * @param page    当前页码，从 1 开始
 * @param size    本次实际生效的每页条数。<strong>可能小于请求值</strong> ——
 *                服务端有单页上限（见 {@code MybatisPlusConfig}），超限按上限处理，
 *                前端应以本字段为准而不是自己请求的那个值
 * @param pages   总页数
 * @param hasNext 是否还有下一页。前端做「加载更多」时直接看它，
 *                不必自己算 {@code page < pages}（边界容易算错）
 * @param <T>     响应条目类型
 */
@Schema(description = "分页结果")
public record PageResponse<T>(
        List<T> records,
        long total,
        long page,
        long size,
        long pages,
        @Schema(description = "是否还有下一页") boolean hasNext
) {

    /**
     * 把 Service 层的 {@link PageResult} 转成响应体，同时逐条做 DTO 转换。
     *
     * <p>用 {@code Function} 接收转换器而不是让本类认识每个条目类型：
     * 否则每加一种清单接口都要在这里加一个重载。
     *
     * @param result  Service 层分页结果
     * @param mapper  单条记录 → 响应 DTO 的转换函数
     */
    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(
                result.records().stream().map(mapper).toList(),
                result.total(),
                result.page(),
                result.size(),
                result.pages(),
                result.page() < result.pages());
    }
}
