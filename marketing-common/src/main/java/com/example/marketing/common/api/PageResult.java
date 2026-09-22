package com.example.marketing.common.api;

import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应契约：与 {@link Result} 组合使用，元信息（total/page/size）随数据一起返回。
 *
 * <p>刻意不依赖 MyBatis-Plus —— marketing-common 的 pom 里没有 ORM，
 * 把 IPage 引进来会让网关等无库场景被拖上依赖。各模块自己把查询结果转成这里。</p>
 *
 * @param <T> 行数据类型
 */
@Data
public class PageResult<T> {

    /** 符合条件的总行数 */
    private long total;
    /** 当前页码，从 1 开始 */
    private int page;
    /** 生效的每页条数（可能小于请求值，见 {@link PageQuery}） */
    private int size;
    /** 当页数据 */
    private List<T> records;

    public static <T> PageResult<T> of(long total, int page, int size, List<T> records) {
        if (total < 0) {
            throw new IllegalArgumentException("total 不能为负: " + total);
        }
        PageResult<T> result = new PageResult<>();
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setRecords(records == null ? List.of() : List.copyOf(records));
        return result;
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return of(total, page, size, records.stream().map(mapper).toList());
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}
