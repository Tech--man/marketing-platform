package com.example.marketing.common.message;

import com.example.marketing.common.mq.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地消息表的 topic 隔离（地雷 D）。
 *
 * <p>biz_key 原来是<b>全局</b>唯一索引，而 bizKey 的取值由调用方决定：
 * {@code /api/coupon/grant} 的 requestId 是外部传入的字符串，只要有人传
 * {@code seckill:xxx} 就会撞上秒杀消息的键形 —— 结果券那条被 INSERT IGNORE 静默丢掉、
 * publish 又去命中秒杀那行，<b>接口返回 ACCEPTED 但券永远不会发</b>。
 * 所以唯一键与所有读写都必须带 topic。</p>
 */
class LocalMessageServiceTest {

    private static final String COUPON_TOPIC = "MKT_COUPON_GRANT";
    private static final String SECKILL_TOPIC = "MKT_SECKILL_ORDER";
    /** 外部可控的 requestId，故意写成秒杀键的形状 */
    private static final String COLLIDING_KEY = "seckill:abc";

    private JdbcTemplate jdbc;
    private RecordingPublisher publisher;
    private LocalMessageService service;

    /** 记录投递过的 (topic, bizKey, payload)，用于断言"发的是哪条" */
    private static class RecordingPublisher implements EventPublisher {
        final List<String> sent = new ArrayList<>();
        boolean succeed = true;

        @Override
        public boolean publish(String topic, String tag, String bizKey, String payload) {
            sent.add(topic + "|" + bizKey + "|" + payload);
            return succeed;
        }
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:local_message_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS local_message");
        jdbc.execute("""
                CREATE TABLE local_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    topic VARCHAR(128) NOT NULL,
                    tag VARCHAR(64) NOT NULL DEFAULT '',
                    biz_key VARCHAR(128) NOT NULL,
                    payload TEXT NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    retry_count INT NOT NULL DEFAULT 0,
                    next_retry_time TIMESTAMP NOT NULL,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_topic_biz_key UNIQUE (topic, biz_key)
                )""");
        publisher = new RecordingPublisher();
        service = new LocalMessageService(jdbc, publisher);
    }

    private String status(String topic, String bizKey) {
        return jdbc.queryForObject("SELECT status FROM local_message WHERE topic = ? AND biz_key = ?",
                String.class, topic, bizKey);
    }

    @Test
    @DisplayName("同一个 bizKey 在不同 topic 下是两条互不相干的消息")
    void sameBizKeyUnderDifferentTopicsCoexist() {
        assertTrue(service.recordIfAbsent(COUPON_TOPIC, "GRANT", COLLIDING_KEY, "{\"coupon\":1}"));
        assertTrue(service.recordIfAbsent(SECKILL_TOPIC, "ORDER", COLLIDING_KEY, "{\"order\":1}"));

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM local_message", Integer.class));
    }

    @Test
    @DisplayName("重复登记同 topic 同键返回 false，让调用方能区分新建与撞键")
    void duplicateRegistrationIsReported() {
        assertTrue(service.recordIfAbsent(COUPON_TOPIC, "GRANT", "REQ-1", "{}"));
        assertFalse(service.recordIfAbsent(COUPON_TOPIC, "GRANT", "REQ-1", "{}"),
                "第二次登记必须报 false，不能再静默");
    }

    @Test
    @DisplayName("publish 只投递自己 topic 的那条，不会串到同名键的别处")
    void publishTargetsItsOwnTopicRow() {
        service.recordIfAbsent(SECKILL_TOPIC, "ORDER", COLLIDING_KEY, "order-payload");
        service.recordIfAbsent(COUPON_TOPIC, "GRANT", COLLIDING_KEY, "coupon-payload");

        assertTrue(service.publish(COUPON_TOPIC, COLLIDING_KEY));

        assertEquals(List.of(COUPON_TOPIC + "|" + COLLIDING_KEY + "|coupon-payload"), publisher.sent);
        assertEquals(LocalMessageService.STATUS_SENT, status(COUPON_TOPIC, COLLIDING_KEY));
        assertEquals(LocalMessageService.STATUS_PENDING, status(SECKILL_TOPIC, COLLIDING_KEY),
                "秒杀那条不能被券的投递顺带改掉");
    }

    @Test
    @DisplayName("confirm 只关闭自己 topic 的那条")
    void confirmClosesOnlyItsOwnTopicRow() {
        service.recordIfAbsent(SECKILL_TOPIC, "ORDER", COLLIDING_KEY, "order-payload");
        service.recordIfAbsent(COUPON_TOPIC, "GRANT", COLLIDING_KEY, "coupon-payload");

        service.confirm(COUPON_TOPIC, COLLIDING_KEY);

        assertEquals(LocalMessageService.STATUS_CONFIRMED, status(COUPON_TOPIC, COLLIDING_KEY));
        assertEquals(LocalMessageService.STATUS_PENDING, status(SECKILL_TOPIC, COLLIDING_KEY));
    }

    @Test
    @DisplayName("补偿扫描逐条按各自 topic 重投")
    void retryPendingRepublishesEachTopicSeparately() {
        service.recordIfAbsent(COUPON_TOPIC, "GRANT", "REQ-9", "coupon-payload");
        service.recordIfAbsent(SECKILL_TOPIC, "ORDER", "REQ-9", "order-payload");

        // 只跑一轮：失败的行会被 scheduleRetry 推到未来（退避基数 5s），
        // 想验"失败后仍能各自补发"得先改 next_retry_time，那是另一条断言的事。
        int picked = service.retryPending(10);

        assertEquals(2, picked);
        assertTrue(publisher.sent.contains(COUPON_TOPIC + "|REQ-9|coupon-payload"), () -> publisher.sent.toString());
        assertTrue(publisher.sent.contains(SECKILL_TOPIC + "|REQ-9|order-payload"), () -> publisher.sent.toString());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM local_message WHERE status = 'SENT'", Integer.class));
    }

    @Test
    @DisplayName("投递失败只退避自己那一行，不动另一个 topic 的同名键")
    void failedRetryOnlyTouchesOwnRow() {
        service.recordIfAbsent(COUPON_TOPIC, "GRANT", "REQ-10", "coupon-payload");
        service.recordIfAbsent(SECKILL_TOPIC, "ORDER", "REQ-10", "order-payload");
        publisher.succeed = false;

        service.publish(COUPON_TOPIC, "REQ-10");

        assertEquals(1, jdbc.queryForObject(
                "SELECT retry_count FROM local_message WHERE topic = ? AND biz_key = ?",
                Integer.class, COUPON_TOPIC, "REQ-10"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT retry_count FROM local_message WHERE topic = ? AND biz_key = ?",
                Integer.class, SECKILL_TOPIC, "REQ-10"), "秒杀那行的重试次数不能被带着走");
    }

    @Test
    @DisplayName("FAILED 死信可计数（对账兜底信号）且重驱动后回到补偿链路")
    void failedDeadLetterIsCountedAndRedrivable() {
        service.recordIfAbsent(COUPON_TOPIC, "GRANT", "REQ-DEAD", "coupon-payload");
        jdbc.update("UPDATE local_message SET status = 'FAILED', retry_count = 10 "
                + "WHERE topic = ? AND biz_key = ?", COUPON_TOPIC, "REQ-DEAD");
        service.recordIfAbsent(SECKILL_TOPIC, "ORDER", "REQ-ALIVE", "order-payload");

        assertEquals(1, service.countFailed(), "死信计数是 ④/告警的唯一入口，数错就是看不见");

        int driven = service.redriveFailed(COUPON_TOPIC);

        assertEquals(1, driven);
        assertEquals("PENDING", status(COUPON_TOPIC, "REQ-DEAD"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT retry_count FROM local_message WHERE topic = ? AND biz_key = ?",
                Integer.class, COUPON_TOPIC, "REQ-DEAD"), "重试计数必须清零，否则马上又撞上限");
        // topic 隔离：另一个 topic 的活消息不受影响
        assertEquals("PENDING", status(SECKILL_TOPIC, "REQ-ALIVE"));
    }

    @Test
    @DisplayName("重驱动只认自己的 topic：别的 topic 的死信不动")
    void redriveOnlyTouchesOwnTopic() {
        service.recordIfAbsent(COUPON_TOPIC, "GRANT", "REQ-D1", "p");
        service.recordIfAbsent(SECKILL_TOPIC, "ORDER", "REQ-D2", "p");
        jdbc.update("UPDATE local_message SET status = 'FAILED' WHERE status = 'PENDING'");

        int driven = service.redriveFailed(COUPON_TOPIC);

        assertEquals(1, driven);
        assertEquals("FAILED", status(SECKILL_TOPIC, "REQ-D2"), "跨 topic 误驱会把别人的死信也重投");
    }
}
