-- ============================================================
-- 营销平台初始化脚本（docker/mysql/init 首次启动自动执行）
-- 四个业务库 + 管理后台库 + 消费者账号库 + 公共幂等/本地消息表 + 演示种子数据
-- 账号：marketing / marketing123（compose 已建，这里补齐其余库授权）
-- ============================================================

-- ---------- 1. 建库 ----------
-- 每个库都要显式 CREATE：GRANT 只写授权表、不会建库，漏一个就会在下面第一个 USE 处报错，
-- 而 docker-entrypoint-initdb.d 里任何一条 SQL 失败都会让 MySQL 容器初始化整体中断。
-- 必须显式声明连接字符集：docker-entrypoint-initdb.d 是用容器里的 mysql 客户端执行的，
-- 而它的默认连接字符集是 latin1 —— 服务端就算配了 utf8mb4，种子中文也会先被按 latin1
-- 转一遍再存成 utf8mb4，落库即成 "2026 秋季大促" -> "2026 ç§‹å­£å¤§ä¿ƒ"（实测在
-- 现有 dev 卷的 activity 种子上就是这个结果）。加这一行才是所见即所得。
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS marketing_activity DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS marketing_coupon  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS marketing_discount DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS marketing_seckill  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS marketing_admin    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- 第六个也要显式 CREATE：漏这行时下面的 USE marketing_account 会直接抛 unknown database，
-- 整个 init 中断（而单库档看不出这个问题——它的三张表建在 marketing 里）。
CREATE DATABASE IF NOT EXISTS marketing_account  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON marketing_activity.* TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_coupon.*  TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_discount.* TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_seckill.*  TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_admin.*    TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_account.*  TO 'marketing'@'%';
-- Docker Desktop 的宿主机端口映射会被 MySQL 解析为 localhost 来源，补一个同名本地账号
CREATE USER IF NOT EXISTS 'marketing'@'localhost' IDENTIFIED WITH mysql_native_password BY 'marketing123';
GRANT ALL PRIVILEGES ON marketing_activity.* TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_coupon.*  TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_discount.* TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_seckill.*  TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_admin.*    TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_account.*  TO 'marketing'@'localhost';
FLUSH PRIVILEGES;

-- ---------- 2. 公共表模板说明 ----------
-- idempotent_record / local_message 每个库都要有一套（各服务只访问自己库），
-- 下方在每个库的段落里重复建表，列定义完全一致。

-- ============================================================
-- 库一：marketing_activity（活动中心）
-- ============================================================
USE marketing_activity;

CREATE TABLE IF NOT EXISTS activity (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_no   VARCHAR(64)  NOT NULL COMMENT '活动编号（业务唯一）',
    name          VARCHAR(128) NOT NULL COMMENT '活动名称',
    status        VARCHAR(16)  NOT NULL COMMENT '状态机：DRAFT/ONLINE/PAUSED/OFFLINE/EXPIRED',
    start_time    DATETIME     NULL COMMENT '生效开始时间',
    end_time      DATETIME     NULL COMMENT '生效结束时间',
    budget_amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '预算总额（元）',
    used_amount   DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '已消耗预算（元，DB 兜底口径）',
    remark        VARCHAR(255) NULL,
    gray_percent  INT          NULL COMMENT '灰度放量百分比 0-100；NULL=未配灰度=全量放行',
    gray_whitelist VARCHAR(255) NULL COMMENT '灰度白名单 userId CSV；NULL 或空=无白名单',
    version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_no (activity_no)
) ENGINE = InnoDB COMMENT '营销活动主表';

CREATE TABLE IF NOT EXISTS budget_flow (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_no  VARCHAR(64)   NOT NULL,
    biz_key      VARCHAR(128)  NOT NULL COMMENT '幂等键（INSERT IGNORE 去重）',
    amount_cents BIGINT        NOT NULL COMMENT '扣减金额（分）',
    type         VARCHAR(16)   NOT NULL COMMENT 'DEDUCT/REFUND',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_biz (activity_no, biz_key),
    KEY idx_activity_no (activity_no)
) ENGINE = InnoDB COMMENT '预算扣减流水（幂等 + 对账）';

CREATE TABLE IF NOT EXISTS idempotent_record (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    biz_key     VARCHAR(128) NOT NULL COMMENT '幂等业务键',
    status      VARCHAR(16)  NOT NULL COMMENT 'INIT/PROCESSING/SUCCESS/FAIL',
    result_json TEXT         NULL COMMENT '成功结果快照（重放直接返回）',
    error_msg   VARCHAR(512) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key)
) ENGINE = InnoDB COMMENT '通用幂等执行记录';

CREATE TABLE IF NOT EXISTS local_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(128) NOT NULL,
    tag             VARCHAR(64)  NOT NULL DEFAULT '',
    biz_key         VARCHAR(128) NOT NULL COMMENT '消息业务键（唯一）',
    payload         TEXT         NOT NULL COMMENT '消息体 JSON',
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/CONFIRMED/FAILED',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL COMMENT '下次补偿扫描时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_biz_key (topic, biz_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '本地消息表（事务消息最终一致）';

-- 种子：一个在线活动（gray_percent=100 与原 yml marketing.gray.ACT2026001.percent 等值，
-- 灰度真值从 yml 搬进列之后，这一行就是链路 0"已配灰度活动命中"断言的唯一支撑）
INSERT INTO activity (activity_no, name, status, start_time, end_time, budget_amount, used_amount, remark, gray_percent)
SELECT 'ACT2026001', '2026 秋季大促', 'ONLINE', NOW() - INTERVAL 7 DAY, NOW() + INTERVAL 365 DAY, 1000000.00, 0.00, '脚手架演示活动', 100
WHERE NOT EXISTS (SELECT 1 FROM activity WHERE activity_no = 'ACT2026001');

-- ============================================================
-- 库二：marketing_coupon（券中心）
-- ============================================================
USE marketing_coupon;

CREATE TABLE IF NOT EXISTS coupon_template (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_no     VARCHAR(64)  NOT NULL COMMENT '模板编号（业务唯一）',
    activity_no     VARCHAR(64)  NOT NULL COMMENT '归属活动',
    name            VARCHAR(128) NOT NULL,
    coupon_type     VARCHAR(32)  NOT NULL COMMENT 'FULL_REDUCTION/DISCOUNT/CASH',
    face_value      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '面额（满减/代金券）或折扣值',
    threshold_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '使用门槛（元）',
    total_stock     INT          NOT NULL DEFAULT 0 COMMENT '总库存',
    per_user_limit  INT          NOT NULL DEFAULT 1 COMMENT '单人限领',
    valid_days      INT          NOT NULL DEFAULT 7 COMMENT '领取后有效天数',
    status          VARCHAR(16)  NOT NULL COMMENT 'ACTIVE/PAUSED/OFFLINE',
    start_time      DATETIME     NULL COMMENT '可领取开始时间',
    end_time        DATETIME     NULL COMMENT '可领取结束时间',
    version         INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_no (template_no),
    KEY idx_activity_no (activity_no)
) ENGINE = InnoDB COMMENT '优惠券模板';

CREATE TABLE IF NOT EXISTS user_coupon (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    coupon_code      VARCHAR(64)   NOT NULL COMMENT '券码（唯一）',
    template_id      BIGINT UNSIGNED NOT NULL,
    template_no      VARCHAR(64)   NOT NULL,
    activity_no      VARCHAR(64)   NOT NULL,
    user_id          BIGINT        NOT NULL,
    status           VARCHAR(16)   NOT NULL COMMENT 'UNUSED/USED/EXPIRED/FROZEN',
    face_value       DECIMAL(10,2) NOT NULL,
    threshold_amount DECIMAL(10,2) NOT NULL,
    coupon_type      VARCHAR(32)   NOT NULL,
    valid_start      DATETIME      NOT NULL,
    expire_at        DATETIME      NOT NULL,
    order_no         VARCHAR(64)   NULL COMMENT '核销订单号',
    request_id       VARCHAR(128)  NOT NULL COMMENT '发券请求号（消费幂等兜底）',
    grant_time       DATETIME      NOT NULL,
    use_time         DATETIME      NULL,
    create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_code (coupon_code),
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_user_status (user_id, status),
    KEY idx_template (template_id)
) ENGINE = InnoDB COMMENT '用户券实例';

CREATE TABLE IF NOT EXISTS idempotent_record (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    biz_key     VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL COMMENT 'INIT/PROCESSING/SUCCESS/FAIL',
    result_json TEXT         NULL,
    error_msg   VARCHAR(512) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key)
) ENGINE = InnoDB COMMENT '通用幂等执行记录';

CREATE TABLE IF NOT EXISTS local_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(128) NOT NULL,
    tag             VARCHAR(64)  NOT NULL DEFAULT '',
    biz_key         VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/CONFIRMED/FAILED',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_biz_key (topic, biz_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '本地消息表';

-- 种子：两张券模板（无门槛 5 元券 / 满 100 减 20）
INSERT INTO coupon_template (template_no, activity_no, name, coupon_type, face_value, threshold_amount,
                             total_stock, per_user_limit, valid_days, status, start_time, end_time)
SELECT 'CT2026001', 'ACT2026001', '5元无门槛券', 'CASH', 5.00, 0.00, 100000, 1, 7, 'ACTIVE',
       NOW() - INTERVAL 7 DAY, NOW() + INTERVAL 365 DAY
WHERE NOT EXISTS (SELECT 1 FROM coupon_template WHERE template_no = 'CT2026001');

INSERT INTO coupon_template (template_no, activity_no, name, coupon_type, face_value, threshold_amount,
                             total_stock, per_user_limit, valid_days, status, start_time, end_time)
SELECT 'CT2026002', 'ACT2026001', '满100减20券', 'FULL_REDUCTION', 20.00, 100.00, 50000, 2, 14, 'ACTIVE',
       NOW() - INTERVAL 7 DAY, NOW() + INTERVAL 365 DAY
WHERE NOT EXISTS (SELECT 1 FROM coupon_template WHERE template_no = 'CT2026002');

-- ============================================================
-- 库三：marketing_discount（优惠计算引擎）
-- ============================================================
USE marketing_discount;

CREATE TABLE IF NOT EXISTS promo_rule (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_no     VARCHAR(64)  NOT NULL COMMENT '规则编号（业务唯一）',
    name        VARCHAR(128) NOT NULL,
    activity_no VARCHAR(64)  NOT NULL COMMENT '绑定活动（活动下线规则失效）',
    rule_type   VARCHAR(32)  NOT NULL COMMENT 'FULL_REDUCTION/DISCOUNT/LADDER',
    mutex_group VARCHAR(64)  NULL COMMENT '互斥组：同组至多命中一条',
    priority    INT          NOT NULL DEFAULT 0 COMMENT '组内竞争优先级，大者优先',
    status      VARCHAR(16)  NOT NULL COMMENT 'ENABLED/DISABLED',
    rule_json   TEXT         NOT NULL COMMENT '规则 DSL（PromoRuleDsl JSON）',
    version     INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_no (rule_no),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT '促销规则（DSL 存储）';

CREATE TABLE IF NOT EXISTS idempotent_record (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    biz_key     VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    result_json TEXT         NULL,
    error_msg   VARCHAR(512) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key)
) ENGINE = InnoDB COMMENT '通用幂等执行记录';

CREATE TABLE IF NOT EXISTS local_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(128) NOT NULL,
    tag             VARCHAR(64)  NOT NULL DEFAULT '',
    biz_key         VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_biz_key (topic, biz_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '本地消息表';

-- 种子：三条规则（满减 / 8.5 折 / 阶梯满减），满减与阶梯互斥
INSERT INTO promo_rule (rule_no, name, activity_no, rule_type, mutex_group, priority, status, rule_json)
SELECT 'PR2026001', '满200减30', 'ACT2026001', 'FULL_REDUCTION', 'PRICE_CUT', 100, 'ENABLED',
       '{"ruleNo":"PR2026001","name":"满200减30","type":"FULL_REDUCTION","activityNo":"ACT2026001","requiredTags":[],"excludeTags":["GIFT"],"threshold":200,"discountValue":30,"mutexGroup":"PRICE_CUT","priority":100,"perUserLimit":0}'
WHERE NOT EXISTS (SELECT 1 FROM promo_rule WHERE rule_no = 'PR2026001');

INSERT INTO promo_rule (rule_no, name, activity_no, rule_type, mutex_group, priority, status, rule_json)
SELECT 'PR2026002', '数码品类8.5折', 'ACT2026001', 'DISCOUNT', NULL, 90, 'ENABLED',
       '{"ruleNo":"PR2026002","name":"数码品类8.5折","type":"DISCOUNT","activityNo":"ACT2026001","requiredTags":["DIGITAL"],"excludeTags":[],"threshold":0,"discountRate":8.5,"mutexGroup":null,"priority":90,"perUserLimit":0}'
WHERE NOT EXISTS (SELECT 1 FROM promo_rule WHERE rule_no = 'PR2026002');

INSERT INTO promo_rule (rule_no, name, activity_no, rule_type, mutex_group, priority, status, rule_json)
SELECT 'PR2026003', '阶梯满减（满300减40/满500减80）', 'ACT2026001', 'LADDER', 'PRICE_CUT', 110, 'ENABLED',
       '{"ruleNo":"PR2026003","name":"阶梯满减","type":"LADDER","activityNo":"ACT2026001","requiredTags":[],"excludeTags":["GIFT"],"threshold":0,"ladderSteps":[{"threshold":300,"discountValue":40},{"threshold":500,"discountValue":80}],"mutexGroup":"PRICE_CUT","priority":110,"perUserLimit":0}'
WHERE NOT EXISTS (SELECT 1 FROM promo_rule WHERE rule_no = 'PR2026003');

-- ============================================================
-- 库四：marketing_seckill（秒杀中心）
-- ============================================================
USE marketing_seckill;

CREATE TABLE IF NOT EXISTS seckill_activity (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_no   VARCHAR(64)   NOT NULL COMMENT '秒杀活动编号',
    item_id       BIGINT        NOT NULL COMMENT '秒杀商品 ID',
    item_name     VARCHAR(128)  NOT NULL,
    seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价（元）',
    total_stock   INT           NOT NULL COMMENT '总库存',
    sold_stock    INT           NOT NULL DEFAULT 0 COMMENT '已售（消费建单时原子递增）',
    buckets       INT           NOT NULL DEFAULT 16 COMMENT 'Redis 分桶数',
    status        VARCHAR(16)   NOT NULL COMMENT 'DRAFT/ONLINE/FINISHED',
    start_time    DATETIME      NULL,
    end_time      DATETIME      NULL,
    version       INT           NOT NULL DEFAULT 0,
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_no (activity_no)
) ENGINE = InnoDB COMMENT '秒杀活动';

CREATE TABLE IF NOT EXISTS seckill_order (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64) NOT NULL COMMENT '订单号',
    activity_no VARCHAR(64) NOT NULL,
    user_id     BIGINT      NOT NULL,
    item_id     BIGINT      NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      VARCHAR(16) NOT NULL COMMENT 'CREATED/PAID/CANCELLED',
    token       VARCHAR(64) NOT NULL COMMENT '抢购排队 token',
    bucket      INT         NULL COMMENT '命中的库存分桶号',
    pay_time    DATETIME    NULL,
    version     INT         NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_activity_user (activity_no, user_id) COMMENT '防重复购买兜底（一人一单）',
    KEY idx_status_create (status, create_time)
) ENGINE = InnoDB COMMENT '秒杀订单';

CREATE TABLE IF NOT EXISTS idempotent_record (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    biz_key     VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    result_json TEXT         NULL,
    error_msg   VARCHAR(512) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key)
) ENGINE = InnoDB COMMENT '通用幂等执行记录';

CREATE TABLE IF NOT EXISTS local_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(128) NOT NULL,
    tag             VARCHAR(64)  NOT NULL DEFAULT '',
    biz_key         VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_biz_key (topic, biz_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '本地消息表';

-- 种子：一个在线秒杀活动（200 件 / 16 桶）
INSERT INTO seckill_activity (activity_no, item_id, item_name, seckill_price, total_stock, sold_stock,
                              buckets, status, start_time, end_time)
SELECT 'SK2026001', 10001, '旗舰手机 秒杀特惠', 1999.00, 5000, 0, 16, 'ONLINE',
       NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY
WHERE NOT EXISTS (SELECT 1 FROM seckill_activity WHERE activity_no = 'SK2026001');

-- ============================================================
-- 库五：marketing_admin（管理后台：账号 / 会话 / 审计）
-- ============================================================

USE marketing_admin;

CREATE TABLE IF NOT EXISTS admin_user (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL COMMENT 'BCrypt，绝不存明文',
    display_name    VARCHAR(64)  NOT NULL DEFAULT '',
    role            VARCHAR(32)  NOT NULL DEFAULT 'read-only' COMMENT 'admin / operator / read-only',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    pwd_version     INT          NOT NULL DEFAULT 1 COMMENT '改密/停用时 +1：token 里的 ver 与之不符即整号失效',
    fail_count      INT          NOT NULL DEFAULT 0,
    lock_until      DATETIME     NULL COMMENT '到点自动放行，不需要人工解锁任务',
    last_login_time DATETIME     NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '管理后台账号';

-- 会话以这张表为准，Redis 的 admin:session:{jti} 只是在线列表快路径、admin:revoked:{jti} 只是吊销位；
-- Redis 被清空时最坏是在线列表短暂无数据，不会把已吊销的 token 放回登录态。
CREATE TABLE IF NOT EXISTS admin_session (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    jti           VARCHAR(64)  NOT NULL COMMENT 'token 唯一标识',
    user_id       BIGINT UNSIGNED NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    login_ip      VARCHAR(64)  NOT NULL DEFAULT '',
    user_agent    VARCHAR(255) NOT NULL DEFAULT '',
    expire_at     DATETIME     NOT NULL,
    revoke_reason VARCHAR(32)  NULL COMMENT 'PASSWORD_CHANGED / DISABLED / FORCE_LOGOUT / LOGOUT',
    revoked_at    DATETIME     NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jti (jti),
    KEY idx_user_active (user_id, revoked_at, expire_at)
) ENGINE = InnoDB COMMENT '管理后台会话';

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_id        BIGINT UNSIGNED NULL COMMENT '登录失败时可为空',
    actor_name      VARCHAR(64)  NOT NULL DEFAULT '',
    role            VARCHAR(32)  NOT NULL DEFAULT '',
    action          VARCHAR(64)  NOT NULL COMMENT '如 login / user.disable / cache.reheat',
    resource_type   VARCHAR(64)  NOT NULL DEFAULT '',
    resource_id     VARCHAR(128) NOT NULL DEFAULT '',
    method          VARCHAR(16)  NOT NULL DEFAULT '',
    path            VARCHAR(255) NOT NULL DEFAULT '',
    request_summary VARCHAR(512) NOT NULL DEFAULT '' COMMENT '只存脱敏摘要，不存原始 body',
    result_code     INT          NOT NULL DEFAULT 0,
    error_msg       VARCHAR(512) NOT NULL DEFAULT '',
    ip              VARCHAR(64)  NOT NULL DEFAULT '',
    cost_ms         BIGINT       NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_actor_time (actor_id, create_time),
    KEY idx_resource (resource_type, resource_id)
) ENGINE = InnoDB COMMENT '管理后台审计日志';

-- 种子账号（BCrypt strength=10）。口令是演示用弱口令，README 已公示并提示改密；
-- 生产部署第一件事就是把这两行的口令换掉，或直接 UPDATE 后再启用后台路由。
INSERT INTO admin_user (username, password_hash, display_name, role, status, pwd_version)
SELECT 'admin', '$2a$10$yUj2rKW4h20DTn/0kkAHHeXLX/cKitjpFFwzhne3if9gm9jZDiDUy', '系统管理员', 'admin', 'ACTIVE', 1
WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE username = 'admin');

INSERT INTO admin_user (username, password_hash, display_name, role, status, pwd_version)
SELECT 'operator', '$2a$10$o1dcGQ41rEUu7qCLu68A/uftVufxdN6jObAgQtcyea5eU8NqykrYC', '运营值班', 'operator', 'ACTIVE', 1
WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE username = 'operator');

INSERT INTO admin_user (username, password_hash, display_name, role, status, pwd_version)
SELECT 'viewer', '$2a$10$o1dcGQ41rEUu7qCLu68A/uftVufxdN6jObAgQtcyea5eU8NqykrYC', '只读访客', 'read-only', 'ACTIVE', 1
WHERE NOT EXISTS (SELECT 1 FROM admin_user WHERE username = 'viewer');

-- 在线配置真值。只有被代码里 ConfigDefinitionProvider 声明过的键才会被读方采纳：
-- "不一致时代码赢"，DB 里的陈旧行既不生效也不报错，由 ④ 的 ORPHAN 清单显式暴露。
-- 删行 = 恢复出厂（不是写回原值，否则 yml 改了会被一行陈旧 DB 值永远压住）。
CREATE TABLE IF NOT EXISTS admin_config (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    cfg_key     VARCHAR(64)  NOT NULL COMMENT '参数键，与 ConfigDefinition.key 一致',
    form        VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/LITE/FULL/DEV',
    cfg_value   VARCHAR(255) NOT NULL COMMENT '按声明的 type 解析；越界或类型不符时读方逐条忽略',
    version     BIGINT       NOT NULL DEFAULT 0 COMMENT '写入时 mkt:cfg:seq 的值，仅用于展示第几版生效',
    updated_by  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '最后一次写的后台账号',
    remark      VARCHAR(255) NOT NULL DEFAULT '',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_form (cfg_key, form)
) ENGINE = InnoDB COMMENT '在线配置真值（删行即恢复出厂）';

-- ============================================================
-- 库六：marketing_account（消费者账号：账号 / 会话 / 身份事件）
-- ============================================================

USE marketing_account;

CREATE TABLE IF NOT EXISTS consumer_user (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    identifier      VARCHAR(64)  NOT NULL COMMENT '登录名；将来接手机号只是换一个值域，名字不绑死渠道',
    password_hash   VARCHAR(100) NOT NULL COMMENT 'BCrypt，绝不存明文',
    nickname        VARCHAR(64)  NOT NULL DEFAULT '',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED（停用不抹行：券与订单要能追到人）',
    pwd_version     INT          NOT NULL DEFAULT 1 COMMENT '改密即 +1 的记账位；作废会话由 consumer:bump:{uid} 承担',
    fail_count      INT          NOT NULL DEFAULT 0,
    lock_until      DATETIME     NULL COMMENT '到点自动放行，不需要人工解锁任务',
    last_login_time DATETIME     NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_identifier (identifier)
) ENGINE = InnoDB COMMENT '消费者账号';

-- 一行 = 一次登录，同时承载 access 的 jti 与配对的 refresh。
-- refresh 存 SHA-256 摘要而非明文：这张表一旦被读走（备份/从库/报表），
-- 明文 refresh 等于 30 天的完整账号访问权；access 短寿命且可吊销，才允许只存 jti。
CREATE TABLE IF NOT EXISTS consumer_session (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    jti               VARCHAR(64)  NOT NULL COMMENT 'access token 的会话 ID，吊销按它拉黑',
    user_id           BIGINT UNSIGNED NOT NULL,
    identifier        VARCHAR(64)  NOT NULL DEFAULT '',
    refresh_hash      CHAR(64)     NOT NULL COMMENT 'refresh token 的 SHA-256（hex），不落明文',
    login_ip          VARCHAR(64)  NOT NULL DEFAULT '',
    user_agent        VARCHAR(256) NOT NULL DEFAULT '',
    expire_at         DATETIME     NOT NULL COMMENT 'access 到期时刻',
    refresh_expire_at DATETIME     NOT NULL COMMENT 'refresh 到期时刻；到点必须重新登录',
    rotated_at        DATETIME     NULL COMMENT '最近一次轮换时刻，null = 从未轮换',
    revoke_reason     VARCHAR(32)  NULL COMMENT 'PASSWORD_CHANGED/DISABLED/FORCE_LOGOUT/LOGOUT/REFRESH_REUSED/REFRESH_EXPIRED/SESSION_QUOTA，null = 仍有效',
    revoked_at        DATETIME     NULL,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jti (jti),
    UNIQUE KEY uk_refresh_hash (refresh_hash) COMMENT '轮换后旧值进 Redis 黑名单，表里只留当前值',
    KEY idx_user_active (user_id, revoked_at, expire_at)
) ENGINE = InnoDB COMMENT '消费者会话（DB 为准，Redis 只放吊销位与整号作废时刻）';

-- 身份事件独立成表，不并进 admin_audit_log：那条跨进程总线 MAXLEN 满则丢最旧，
-- 是按"人肉点出来的后台写入"量级设计的，登录事件是机器量级，灌进去会静默挤掉更早的审计。
CREATE TABLE IF NOT EXISTS consumer_event_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    action      VARCHAR(32) NOT NULL COMMENT 'REGISTER/LOGIN/LOGIN_FAILED/REFRESH/REFRESH_REUSED/LOGOUT/PASSWORD_CHANGED',
    user_id     BIGINT UNSIGNED NULL COMMENT '注册失败或账号不存在时没有 uid',
    identifier  VARCHAR(64)  NOT NULL DEFAULT '',
    jti         VARCHAR(64)  NOT NULL DEFAULT '',
    ip          VARCHAR(64)  NOT NULL DEFAULT '',
    user_agent  VARCHAR(256) NOT NULL DEFAULT '',
    result      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT 'SUCCESS 或失败原因，不含口令与 token',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, create_time),
    KEY idx_action_time (action, create_time)
) ENGINE = InnoDB COMMENT '消费者身份事件流水';

-- 演示账号 demo / demo123456，固定占 70001 这个 id：
-- user_coupon / seckill_order 里的 user_id 今天是没有父表的自由整数，
-- 冒烟与 H5 演示都在用 70001，让它第一次有一个真实账号可指，历史券数据不至于变成孤儿。
INSERT INTO consumer_user (id, identifier, password_hash, nickname, status, pwd_version)
SELECT 70001, 'demo', '$2a$10$sFlfmM0L8Vd7kCpBmDnVV.kwWPNAef6Nr3x/94mYj8NMlfRNWccAm', '演示消费者', 'ACTIVE', 1
WHERE NOT EXISTS (SELECT 1 FROM consumer_user WHERE identifier = 'demo');
