package com.example.marketing.seckill.service;

import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.redis.LuaScripts;
import com.example.marketing.seckill.config.SeckillRuntimeConfig;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀库存 Redis 操作封装：分桶预热 / Lua 原子抢购 / 回补 / 防重购标记。
 *
 * <p>key 约定（独立 seckill: namespace，与券中心隔离）：</p>
 * <ul>
 *   <li>seckill:stock:{activityNo}:{bucket} —— 分桶剩余库存</li>
 *   <li>seckill:bought:{activityNo}:{userId} —— 防重购标记</li>
 *   <li>seckill:result:{token} —— 抢购结果（前端轮询）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillStockService {

    private final StringRedisTemplate redisTemplate;
    /** 三个 TTL 的唯一取值出口（在线值 > yml）；本类不再直接读 SeckillProperties */
    private final SeckillRuntimeConfig runtime;

    public String stockKey(String activityNo, int bucket) {
        return "seckill:stock:" + activityNo + ":" + bucket;
    }

    public String boughtKey(String activityNo, Long userId) {
        return "seckill:bought:" + activityNo + ":" + userId;
    }

    public String resultKey(String token) {
        return "seckill:result:" + token;
    }

    /** 把 total 均匀分配到 buckets 个桶（余数摊给前几个桶） */
    public List<Integer> allocateBuckets(int total, int buckets) {
        List<Integer> plan = new ArrayList<>(buckets);
        int base = total / buckets;
        int remainder = total % buckets;
        for (int i = 0; i < buckets; i++) {
            plan.add(base + (i < remainder ? 1 : 0));
        }
        return plan;
    }

    /**
     * 预热分桶（SETNX 语义：已存在的桶不覆盖，防止重启重置售卖进度）。
     *
     * @return 实际初始化的桶数
     */
    public int warmUp(SeckillActivityEntity activity, List<Integer> bucketStocks) {
        Duration ttl = bucketTtl(activity);
        int initialized = 0;
        for (int i = 0; i < bucketStocks.size(); i++) {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(stockKey(activity.getActivityNo(), i + 1),
                            String.valueOf(bucketStocks.get(i)), ttl);
            if (Boolean.TRUE.equals(ok)) {
                initialized++;
            }
        }
        log.info("[seckill] 活动 {} 预热完成：{}/{} 个桶初始化（桶 TTL={}s）",
                activity.getActivityNo(), initialized, bucketStocks.size(), ttl.toSeconds());
        return initialized;
    }

    /**
     * 覆盖式重建分桶：逐桶 DEL 后按新配额 SET。
     *
     * <p>只在运营显式改了 total_stock 之后由 {@code reheat(force=true)} 触发 —— 它等于重新开闸，
     * 所以调用方必须先确认活动仍在 ONLINE 窗口内（见 SeckillWarmUpService 的守卫）。</p>
     */
    public int resetBuckets(SeckillActivityEntity activity, List<Integer> bucketStocks) {
        Duration ttl = bucketTtl(activity);
        for (int i = 0; i < bucketStocks.size(); i++) {
            String key = stockKey(activity.getActivityNo(), i + 1);
            redisTemplate.delete(key);
            redisTemplate.opsForValue().set(key, String.valueOf(bucketStocks.get(i)), ttl);
        }
        log.info("[seckill] 活动 {} 覆盖重建 {} 个桶（桶 TTL={}s）",
                activity.getActivityNo(), bucketStocks.size(), ttl.toSeconds());
        return bucketStocks.size();
    }

    /**
     * 分桶 TTL（P2，2026-09-30 第二轮复审）：<b>与防重标记 TTL 彻底解耦</b>。
     * 原实现复用 {@code boughtMarkTtlSeconds}（默认 24h）——种子活动窗口 31 天，
     * 次日桶整体过期、grab 全量"未预热"拒绝；且那是个<b>在线可调</b>参数（合法下限
     * 60s），运营把防重标记调小做灰度，库存桶 60 秒后被连带放倒，秒杀直接停摆。
     * 桶的寿命应该由活动自己决定：覆盖到 endTime + 1h 缓冲；无 endTime 的长尾活动
     * 给 30 天；下限 1h（活动将结束时新建/重建的桶不至于秒过期）。
     */
    static Duration bucketTtl(SeckillActivityEntity activity) {
        if (activity == null || activity.getEndTime() == null) {
            return Duration.ofDays(30);
        }
        Duration untilEnd = Duration.between(java.time.LocalDateTime.now(), activity.getEndTime())
                .plusHours(1);
        return untilEnd.getSeconds() < 3600 ? Duration.ofHours(1) : untilEnd;
    }

    /**
     * 原子抢购：先 SETNX 防重购标记，再 Lua 扣减（定位桶 + 借桶）。
     *
     * @return 命中的桶号（1-based，超时回补需要）
     * @throws BizException 重复参与 / 售罄 / 未预热
     */
    public int grab(String activityNo, Long userId, int buckets) {
        String bought = boughtKey(activityNo, userId);
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(bought, "1", Duration.ofSeconds(runtime.boughtMarkTtlSeconds()));
        if (!Boolean.TRUE.equals(first)) {
            throw BizException.of(ErrorCode.BIZ_ERROR, "您已参与过该场秒杀");
        }
        List<String> keys = new ArrayList<>(buckets);
        for (int i = 1; i <= buckets; i++) {
            keys.add(stockKey(activityNo, i));
        }
        Long result;
        try {
            result = redisTemplate.execute(LuaScripts.ofLong("lua/seckill_grab.lua"), keys,
                    String.valueOf(userId), "1");
        } catch (Exception e) {
            redisTemplate.delete(bought);
            throw BizException.of(ErrorCode.SYSTEM_ERROR);
        }
        long r = result == null ? -2L : result;
        if (r == -2L) {
            redisTemplate.delete(bought);
            throw BizException.of(ErrorCode.ACTIVITY_NOT_ONLINE, "秒杀库存未预热");
        }
        if (r == 0L) {
            redisTemplate.delete(bought);
            throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH, "已售罄");
        }
        return (int) r;
    }

    /** 回补库存到原桶并移除防重购标记（超时取消链路） */
    public boolean refill(String activityNo, Long userId, int bucket, int buckets) {
        List<String> keys = new ArrayList<>(buckets);
        for (int i = 1; i <= buckets; i++) {
            keys.add(stockKey(activityNo, i));
        }
        Long result = redisTemplate.execute(LuaScripts.ofLong("lua/seckill_refill.lua"), keys,
                String.valueOf(bucket), "1");
        redisTemplate.delete(boughtKey(activityNo, userId));
        boolean ok = result != null && result == 1L;
        if (!ok) {
            log.warn("[seckill] 回补未生效（桶 key 已过期）activityNo={}, bucket={}", activityNo, bucket);
        }
        return ok;
    }

    /** 写入抢购结果（前端轮询用） */
    public void saveResult(String token, String value) {
        redisTemplate.opsForValue().set(resultKey(token), value,
                Duration.ofSeconds(runtime.tokenTtlSeconds()));
    }

    /**
     * 受理占位：仅在结果键还没有值时写入。
     *
     * <p>受理态绝不能覆盖已产生的终态——消费端比同步链路先写完时（消费并行度提高后
     * 这变得很常见），无条件 SET 会把 SUCCESS 打回 ACCEPTED，用户就再也轮询不到结果，
     * 而订单其实已经建好。终态之间仍需互相覆盖（失败重试后转成功、支付后转取消），
     * 所以只有受理这一步走 setIfAbsent。</p>
     */
    public void markAccepted(String token) {
        redisTemplate.opsForValue().setIfAbsent(resultKey(token), "ACCEPTED",
                Duration.ofSeconds(runtime.tokenTtlSeconds()));
    }

    public String getResult(String token) {
        return redisTemplate.opsForValue().get(resultKey(token));
    }

    /** 查询各桶实时余量（对账/监控） */
    public List<Long> currentBucketStocks(String activityNo, int buckets) {
        List<String> values = redisTemplate.opsForValue()
                .multiGet(keysOf(activityNo, buckets));
        List<Long> stocks = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                stocks.add(v == null ? -1L : Long.parseLong(v));
            }
        }
        return stocks;
    }

    public List<String> keysOf(String activityNo, int buckets) {
        List<String> keys = new ArrayList<>(buckets);
        for (int i = 1; i <= buckets; i++) {
            keys.add(stockKey(activityNo, i));
        }
        return keys;
    }
}
