package com.example.marketing.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页请求参数：页码从 1 开始，越界一律夹到合法区间（不报错、不静默少给），
 * 夹后的实际 size 会体现在 {@link PageResult#getSize()} 里让调用方看得见。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageQuery {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    private int page;
    private int size;

    public static PageQuery of(Integer page, Integer size) {
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        return new PageQuery(normalizedPage, normalizedSize);
    }

    /** 供 SQL 使用的行偏移；用 long 免得大页码乘法溢出成负数 */
    public long offset() {
        return (long) (page - 1) * size;
    }
}
