package com.example.marketing.admin.observe;

/**
 * 一个可抓取的进程端点。
 *
 * <p>这个 record 本身<b>不做</b>校验：校验集中在 {@link OpsTargets}——启动期校验配置值、
 * 请求期只允许"从已校验的清单里按名字取"。所以生产路径永远拿不到白名单之外的 host/port，
 * 而测试可以直连一个随机回环端口（见 ProxyMeterSourceTest 的分层说明）。</p>
 */
public record TargetRef(String name, String host, int port) {

    /** 常量路径：任何情况下都不接受外部输入的 path */
    public static final String PATH = "/actuator/prometheus";

    public String url() {
        return "http://" + host + ":" + port + PATH;
    }
}
