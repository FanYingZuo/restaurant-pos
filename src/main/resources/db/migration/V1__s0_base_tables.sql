-- 階段 S0 的資料表（草擬，待使用者審定：docs/plan/04-LOG.md 待決 #5 選項 A）
-- 規則：主鍵為整數自動遞增；時間為 UTC 瞬時點 DATETIME(6)；營業日為 DATE；狀態存文字。

CREATE TABLE app_setting (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    setting_key   VARCHAR(100) NOT NULL,
    setting_value VARCHAR(500) NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_setting_key UNIQUE (setting_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at         DATETIME(6)  NOT NULL,
    business_date       DATE         NOT NULL,
    action              VARCHAR(64)  NOT NULL,
    actor_employee_id   BIGINT       NULL,
    actor_device_id     BIGINT       NULL,
    target_type         VARCHAR(64)  NULL,
    target_id           VARCHAR(64)  NULL,
    detail              VARCHAR(1000) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE device (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    device_type     VARCHAR(10)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    credential_hash VARCHAR(64)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    revoked_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_device_credential_hash UNIQUE (credential_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
