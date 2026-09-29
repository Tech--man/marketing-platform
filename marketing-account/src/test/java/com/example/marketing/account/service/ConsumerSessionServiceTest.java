package com.example.marketing.account.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.marketing.account.config.AccountProperties;
import com.example.marketing.account.infrastructure.entity.ConsumerSessionEntity;
import com.example.marketing.account.infrastructure.mapper.ConsumerSessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H2 回归（2026-09-29 架构审查）：整号作废的覆盖范围按 <b>refresh 寿命</b>算。
 *
 * <p>原实现按 activeSessions（access 未过期）遍历——而任一时刻大多数活跃会话的
 * access 窗口都已过去、30 天 refresh 仍活着。改密/停用若杀不掉这批会话，
 * 攻击者手里的 refresh 照常换出新凭证，"作废全部会话"只剩注释。</p>
 *
 * <p>走真 H2 + 真 mapper（MyBatis-Plus 生成 WHERE），钉的是查询语义本身；
 * 吊销写回（update）也顺带在真库上验。</p>
 */
class ConsumerSessionServiceTest {

    private SqlSessionFactory factory;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private javax.sql.DataSource dataSource;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ConsumerSessionService service;
    private SqlSession session;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ConsumerSessionEntity.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:consumer_session_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS consumer_session");
        jdbc.execute("""
                CREATE TABLE consumer_session (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    jti VARCHAR(64) NOT NULL,
                    user_id BIGINT NOT NULL,
                    identifier VARCHAR(64) DEFAULT '',
                    refresh_hash CHAR(64) NOT NULL,
                    login_ip VARCHAR(64) DEFAULT '',
                    user_agent VARCHAR(256) DEFAULT '',
                    expire_at TIMESTAMP NOT NULL,
                    refresh_expire_at TIMESTAMP NOT NULL,
                    rotated_at TIMESTAMP NULL,
                    revoke_reason VARCHAR(32) NULL,
                    revoked_at TIMESTAMP NULL,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_jti UNIQUE (jti),
                    CONSTRAINT uk_refresh_hash UNIQUE (refresh_hash)
                )""");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(ConsumerSessionMapper.class);
        factory = new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);

        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new ConsumerSessionService(
                session.getMapper(ConsumerSessionMapper.class), redis, new AccountProperties());
    }

    @AfterEach
    void tearDown() {
        session.close();
        jdbc.execute("DROP TABLE IF EXISTS consumer_session");
    }

    private void insertSession(String jti, String refreshHash, LocalDateTime expireAt,
                               LocalDateTime refreshExpireAt, LocalDateTime revokedAt) {
        jdbc.update("INSERT INTO consumer_session (jti, user_id, refresh_hash, expire_at, "
                        + "refresh_expire_at, revoked_at) VALUES (?,?,?,?,?,?)",
                jti, 70001L, refreshHash, expireAt, refreshExpireAt, revokedAt);
    }

    @Test
    @DisplayName("改密必须杀掉「access 已过期、refresh 存活」的会话——那是大多数活跃会话的常态")
    void revokeAllCoversAccessExpiredRefreshAliveSessions() {
        LocalDateTime now = LocalDateTime.now();
        // 四种形状：access 过期+refresh 活（主角）、access 活+refresh 活、
        // access 过期+refresh 也过期（不该杀）、已吊销（不该重复动）
        insertSession("jti-a", "hash-a", now.minusMinutes(10), now.plusDays(29), null);
        insertSession("jti-b", "hash-b", now.plusMinutes(10), now.plusDays(29), null);
        insertSession("jti-c", "hash-c", now.minusMinutes(10), now.minusMinutes(1), null);
        insertSession("jti-d", "hash-d", now.plusMinutes(10), now.plusDays(29), now.minusMinutes(5));
        jdbc.update("UPDATE consumer_session SET revoke_reason='FORCE_LOGOUT' WHERE jti='jti-d'");

        service.revokeAll(70001L, "PASSWORD_CHANGED");

        assertEquals("PASSWORD_CHANGED", revokedReason("jti-a"),
                "access 过期但 refresh 存活的会话必须被杀——原实现漏的正是这批");
        assertEquals("PASSWORD_CHANGED", revokedReason("jti-b"));
        assertNull(revokedReason("jti-c"), "refresh 已到期的会话不需要杀（refresh() 本就拒它）");
        assertEquals("FORCE_LOGOUT", revokedReason("jti-d"),
                "已吊销过的会话不该被改写吊销原因（markRevoked 只动 revoked_at 为空的行）");
    }

    @Test
    @DisplayName("杀完之后 refreshable 范围为空——同一账号再改密是无操作")
    void revokeAllIsIdempotentOnSecondCall() {
        LocalDateTime now = LocalDateTime.now();
        insertSession("jti-a", "hash-a", now.minusMinutes(10), now.plusDays(29), null);

        service.revokeAll(70001L, "PASSWORD_CHANGED");
        service.revokeAll(70001L, "PASSWORD_CHANGED");

        assertEquals("PASSWORD_CHANGED", revokedReason("jti-a"));
    }

    @Test
    @DisplayName("B4-2：轮换 CAS 于旧摘要——并发双花只有一个赢家，输家拿到 false")
    void rotateIsCasOnOldRefreshHash() {
        insertSession("jti-r", "hash-old", java.time.LocalDateTime.now().plusMinutes(10),
                java.time.LocalDateTime.now().plusDays(29), null);
        com.example.marketing.account.infrastructure.mapper.ConsumerSessionMapper mapper =
                session.getMapper(com.example.marketing.account.infrastructure.mapper.ConsumerSessionMapper.class);
        ConsumerSessionEntity row = mapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ConsumerSessionEntity>lambdaQuery()
                        .eq(ConsumerSessionEntity::getJti, "jti-r"));

        // 赢家：行上还是 hash-old，CAS 命中
        boolean won = service.rotate(row, "hash-new", java.time.LocalDateTime.now().plusDays(30));
        assertEquals(true, won, "持旧摘要的轮换必须赢");

        // 输家：行上已是 hash-new，拿 hash-old 再来一次 CAS 落空（无 CAS 时后写覆盖先写）
        boolean lost = service.rotate(row, "hash-other", java.time.LocalDateTime.now().plusDays(30));
        assertEquals(false, lost, "并发双花的第二人必须输——原实现后写覆盖先写、双双拿有效凭证");

        // 行上留的是赢家的新摘要，不是输家的
        assertEquals("hash-new", jdbc.queryForObject(
                "SELECT refresh_hash FROM consumer_session WHERE jti='jti-r'", String.class));
    }

    private String revokedReason(String jti) {
        return jdbc.queryForObject(
                "SELECT revoke_reason FROM consumer_session WHERE jti=?", String.class, jti);
    }
}
