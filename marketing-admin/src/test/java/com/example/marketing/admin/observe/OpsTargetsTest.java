package com.example.marketing.admin.observe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ④ 是这套系统里第一个"能被指向任意地址"的后台能力，所以这里是一张**正面清单**。
 *
 * <p>黑名单（"排除内网网段"之类）漏一个 127.0.0.1 就完了，而且 MySQL/Redis 就在
 * 127.0.0.1 的常见端口上——本仓库的 compose 就是把它们发在 3307/6379。</p>
 *
 * <p>大小写也拒：`MARKETING-ACTIVITY` 在某些解析器里等价于小写，放行它等于把判断权
 * 交给下游的 host 解析实现。</p>
 */
class OpsTargetsTest {

    @Test
    @DisplayName("清单内的 host:port 正常收下")
    void acceptsWhitelisted() {
        assertEquals("marketing-activity", OpsTargets.parse("activity", "marketing-activity:8081").host());
        assertEquals(8090, OpsTargets.parse("gw", "marketing-gateway:8090").port());
        assertEquals("standalone", OpsTargets.parse("self", "standalone:8085").host());
        assertEquals("127.0.0.1", OpsTargets.parse("self", "127.0.0.1:8085").host());
        assertEquals("mkt-preview-standalone", OpsTargets.parse("c", "mkt-preview-standalone:8085").host());
    }

    @Test
    @DisplayName("数据库与 Redis 的端口必须拒：那是同机上最值钱的两扇门")
    void rejectsDataStorePorts() {
        assertRejected("127.0.0.1:3307");
        assertRejected("127.0.0.1:6379");
        assertRejected("127.0.0.1:2181");
    }

    @Test
    @DisplayName("清单外的 host 一律拒，含 SSRF 常用写法")
    void rejectsForeignHosts() {
        assertRejected("evil.com:8081");
        assertRejected("10.0.0.1:8081");
        assertRejected("169.254.169.254:80");        // 云元数据地址
        assertRejected("a@evil:8081");                // userinfo
        assertRejected("marketing-activity/x:8081");   // path 注入
        assertRejected("marketing-activity:8081/y");
        assertRejected("MARKETING-ACTIVITY:8081");     // 大小写变体
        assertRejected("localhost:8081");              // 不是清单里的字面量
        assertRejected("marketing-activity");          // 少端口
        assertRejected("marketing-activity:9999");     // 端口越界
        assertRejected("marketing-activity:8081:8082");// 多余段
        assertRejected("");
        assertRejected("   ");
    }

    @Test
    @DisplayName("URL 是拼出来的常量，path 不接受任何输入")
    void urlIsBuiltNotGiven() {
        assertEquals("http://marketing-activity:8081/actuator/prometheus",
                OpsTargets.parse("activity", "marketing-activity:8081").url());
    }

    @Test
    @DisplayName("启动期校验整张清单：报错必须点名是哪一条")
    void batchParseNamesTheOffendingEntry() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OpsTargets.parseAll(Map.of("marketing-activity", "marketing-activity:8081",
                        "oops", "127.0.0.1:3307")));
        assertTrue(e.getMessage().contains("oops"), "要指出是哪个配置项: " + e.getMessage());
    }

    @Test
    @DisplayName("key 本身也要合规：它是请求方可控的字符串")
    void targetNameIsValidatedToo() {
        assertThrows(IllegalArgumentException.class,
                () -> OpsTargets.parseAll(Map.of("marketing-activity:8081", "marketing-activity:8081")));
        assertThrows(IllegalArgumentException.class,
                () -> OpsTargets.parseAll(Map.of("", "marketing-activity:8081")));
    }

    private static void assertRejected(String spec) {
        assertThrows(IllegalArgumentException.class, () -> OpsTargets.parse("t", spec),
                "必须被拒: " + spec);
    }
}
