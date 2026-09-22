CREATE TABLE IF NOT EXISTS activity (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_no   VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    budget_amount DECIMAL(14, 2) NOT NULL DEFAULT 0,
    used_amount   DECIMAL(14, 2) NOT NULL DEFAULT 0,
    version       INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS budget_flow (
    id           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_no  VARCHAR(64) NOT NULL,
    biz_key      VARCHAR(128) NOT NULL,
    amount_cents BIGINT      NOT NULL,
    type         VARCHAR(16) NOT NULL,
    create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_biz_key UNIQUE (biz_key)
);
