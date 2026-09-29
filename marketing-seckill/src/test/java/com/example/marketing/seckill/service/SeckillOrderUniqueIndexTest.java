package com.example.marketing.seckill.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * H7 回归（2026-09-29 架构审查）：seckill_order 的唯一索引语义——
 * <b>一人一张「有效」单</b>，不是一人一场永远一单。
 *
 * <p>超时取消链路会回补库存并删防重购标记（业务上允许重抢），旧索引
 * {@code (activity_no, user_id)} 却把 CANCELLED 行也算进占用：重抢的 insert 必撞旧行，
 * 消费端把已取消单号当 SUCCESS 回放——用户拿到一个永远付不了款的单号。
 * 这里在真 H2 上钉住新索引 {@code (activity_no, user_id, active)} 的行为。</p>
 */
class SeckillOrderUniqueIndexTest {

    private JdbcTemplate jdbc;
    private org.springframework.jdbc.datasource.DriverManagerDataSource ds;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:seckill_uk_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS seckill_order");
        // 与 docker/mysql/init/01-schema.sql 的 seckill_order 同构（新索引形状）
        jdbc.execute("""
                CREATE TABLE seckill_order (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_no    VARCHAR(64) NOT NULL,
                    activity_no VARCHAR(64) NOT NULL,
                    user_id     BIGINT      NOT NULL,
                    item_id     BIGINT      NOT NULL,
                    amount      DECIMAL(10,2) NOT NULL,
                    status      VARCHAR(16) NOT NULL,
                    token       VARCHAR(64) NOT NULL,
                    bucket      INT         NULL,
                    active      TINYINT     NOT NULL DEFAULT 1,
                    pay_time    DATETIME    NULL,
                    version     INT         NOT NULL DEFAULT 0,
                    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_order_no UNIQUE (order_no),
                    CONSTRAINT uk_activity_user UNIQUE (activity_no, user_id, active)
                )""");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS seckill_order");
    }

    private void insertOrder(String orderNo, String status, int active) {
        jdbc.update("INSERT INTO seckill_order (order_no, activity_no, user_id, item_id, amount, "
                        + "status, token, active) VALUES (?,?,?,?,?,?,?,?)",
                orderNo, "SK1", 70001L, 1L, 9.90, status, "tk-" + orderNo, active);
    }

    @Test
    @DisplayName("有效单未取消时，同活动同人再插必被唯一索引拦下（一人一张有效单）")
    void activeOrderBlocksSecondInsert() {
        insertOrder("SK-1", "CREATED", 1);

        assertThrows(DuplicateKeyException.class, () -> insertOrder("SK-2", "CREATED", 1),
                "并发抢购/重复投递的兜底不能弱于改前");
    }

    @Test
    @DisplayName("取消释放占用（active=0）后，重抢的新单可以正常落下")
    void cancelledOrderFreesSlotForRegrab() {
        insertOrder("SK-1", "CANCELLED", 0);

        assertDoesNotThrow(() -> insertOrder("SK-2", "CREATED", 1),
                "旧索引形状下这条 insert 必撞 CANCELLED 行——重抢死单正是这么来的");
    }
}
