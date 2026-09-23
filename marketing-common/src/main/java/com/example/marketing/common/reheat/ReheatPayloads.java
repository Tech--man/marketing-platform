package com.example.marketing.common.reheat;

/**
 * 一次跨进程重预热的请求与回执载荷。
 *
 * <p>请求（{@code Request}）由 marketing-admin 投进 {@code mkt:reheat:{type}:pending}，
 * 拥有该 type 的业务服务消费、执行、再把 {@code Ack} 写进 {@code mkt:reheat:{type}:ack:{id}}。</p>
 *
 * <p>为什么要 id 而不是"最后一条"：后台点一次按钮要能回答"我那次刷成功了吗"，
 * 而 FULL 容器形态下同一 type 可能有多个副本在消费（消费组会分给其中一个）。</p>
 *
 * @param status DONE=执行成功；FAILED=执行侧抛了异常（error 里带原因）
 */
public final class ReheatPayloads {

    private ReheatPayloads() {
    }

    /** 一次重预热请求 */
    public record Request(String id, String type, String key, boolean force,
                          String actor, long requestedAt) {
    }

    /** 执行回执 */
    public record Ack(String id, String type, String key, String status,
                      long before, long after, String error, long atEpoch) {

        public static Ack done(Request r, long before, long after) {
            return new Ack(r.id(), r.type(), r.key(), "DONE", before, after, "",
                    System.currentTimeMillis() / 1000);
        }

        public static Ack failed(Request r, String error) {
            return new Ack(r.id(), r.type(), r.key(), "FAILED", -1L, -1L, error,
                    System.currentTimeMillis() / 1000);
        }
    }
}
