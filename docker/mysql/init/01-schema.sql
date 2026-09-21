-- ============================================================
-- 营销平台初始化脚本（docker/mysql/init 首次启动自动执行）
-- 四个业务库 + 公共幂等/本地消息表 + 演示种子数据
-- 账号：marketing / marketing123（compose 已建，这里补齐其余库授权）
-- ============================================================

-- ---------- 1. 建库 ----------
CREATE DATABASE IF NOT EXISTS marketing_coupon  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS marketing_discount DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS marketing_seckill  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON marketing_activity.* TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_coupon.*  TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_discount.* TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_seckill.*  TO 'marketing'@'%';
-- Docker Desktop 的宿主机端口映射会被 MySQL 解析为 localhost 来源，补一个同名本地账号
CREATE USER IF NOT EXISTS 'marketing'@'localhost' IDENTIFIED WITH mysql_native_password BY 'marketing123';
GRANT ALL PRIVILEGES ON marketing_activity.* TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_coupon.*  TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_discount.* TO 'marketing'@'localhost';
GRANT ALL PRIVILEGES ON marketing_seckill.*  TO 'marketing'@'localhost';
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
    UNIQUE KEY uk_biz_key (biz_key),
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
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/CONFIRMED/DEAD',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL COMMENT '下次补偿扫描时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '本地消息表（事务消息最终一致）';

-- 种子：一个在线活动
INSERT INTO activity (activity_no, name, status, start_time, end_time, budget_amount, used_amount, remark)
SELECT 'ACT2026001', '2026 秋季大促', 'ONLINE', NOW() - INTERVAL 7 DAY, NOW() + INTERVAL 365 DAY, 1000000.00, 0.00, '脚手架演示活动'
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
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/CONFIRMED/DEAD',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME     NOT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key),
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
    UNIQUE KEY uk_biz_key (biz_key),
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
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB COMMENT '本地消息表';

-- 种子：一个在线秒杀活动（200 件 / 16 桶）
INSERT INTO seckill_activity (activity_no, item_id, item_name, seckill_price, total_stock, sold_stock,
                              buckets, status, start_time, end_time)
SELECT 'SK2026001', 10001, '旗舰手机 秒杀特惠', 1999.00, 200, 0, 16, 'ONLINE',
       NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY
WHERE NOT EXISTS (SELECT 1 FROM seckill_activity WHERE activity_no = 'SK2026001');
