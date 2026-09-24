package com.example.marketing.admin.observe;

/** 抓不到就是一个异常 + 一句原因，绝不返回空列表冒充"这个进程没有指标"。 */
public class ScrapeException extends RuntimeException {

    public ScrapeException(String message) {
        super(message);
    }

    public ScrapeException(String message, Throwable cause) {
        super(message, cause);
    }
}
