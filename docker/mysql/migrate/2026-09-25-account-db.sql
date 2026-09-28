-- ============================================================
-- 修复：每服务一库档缺 marketing_account 这个库（消费者账号三张表）
-- 日期：2026-09-25
-- 背景：账号体系上线时在 docker/mysql/init/01-schema.sql 末尾加了"库六"段落，
--       但只在开头 CREATE 了五个库，**没有 CREATE DATABASE marketing_account**。
--       新卷初始化时会在 USE marketing_account 处抛 unknown database、整个 init 中断
--       （init 文件已补建库 + 授权）；已经建好的卷则是那五库在、第六库根本不存在。
--       而 MYSQL_DB_PER_SERVICE=1 时 start-all.sh 会把 account 指到 marketing_account，
--       表现为"账号服务连不上库"，五形态矩阵里的隔离档跑不动。
-- 单库档不受影响：consumer_* 三张表建在 marketing 里（init-lite 的并入段），本机已存在。
-- 幂等：CREATE DATABASE / TABLE 全带 IF NOT EXISTS，INSERT 带 WHERE NOT EXISTS，
--       GRANT 天然可重复 —— 重复执行是 no-op。
-- 前置：要用 root 跑（建库 + 授权）。库里若已有 marketing 那份 consumer_*，
--       本脚本**不搬数据**——两套布局本来就各自独立，搬了反而制造双份真值。
-- 同步：三张表的 DDL 与 docker/mysql/init/01-schema.sql 的"库六"段落逐字一致，
--       改那一处必须同时改这里（与 init-lite 的并入段同一条纪律，仓内已有三处副本）。
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS marketing_account
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON marketing_account.* TO 'marketing'@'%';
GRANT ALL PRIVILEGES ON marketing_account.* TO 'marketing'@'localhost';
FLUSH PRIVILEGES;

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

INSERT INTO consumer_user (id, identifier, password_hash, nickname, status, pwd_version)
SELECT 70001, 'demo', '$2a$10$sFlfmM0L8Vd7kCpBmDnVV.kwWPNAef6Nr3x/94mYj8NMlfRNWccAm', '演示消费者', 'ACTIVE', 1
WHERE NOT EXISTS (SELECT 1 FROM consumer_user WHERE identifier = 'demo');
