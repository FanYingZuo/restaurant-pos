-- 階段 S4：桌位與用餐紀錄（docs/plan/01-PLAN.md §4.2、S4）。
-- 桌位：軟刪除；桌號唯一（不分大小寫、去空白後）由程式檢查。
CREATE TABLE dining_table (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    table_no   VARCHAR(20) NOT NULL,
    capacity   INT         NOT NULL,
    shape      VARCHAR(16) NOT NULL,
    width      INT         NOT NULL,
    height     INT         NOT NULL,
    x          INT         NOT NULL,
    y          INT         NOT NULL,
    area       VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 每日帳單號流水：營業日為主鍵，悲觀鎖遞增（§4.2）。
CREATE TABLE daily_sequence (
    business_date DATE NOT NULL,
    last_no       INT  NOT NULL,
    PRIMARY KEY (business_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 用餐紀錄：token、（營業日，帳單號）、目前占用桌位皆唯一。
-- occupying_table_id 只在「使用中且占桌」時有值（其餘為 NULL），其唯一索引保證每桌同時最多一筆占桌的使用中紀錄。
-- token 為顧客 QR 用的機密，API 回應一律不含；重印 QR 需要它，所以存明碼。
CREATE TABLE table_session (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    table_id               BIGINT      NOT NULL,
    business_date          DATE        NOT NULL,
    bill_no                INT         NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    occupying_table_id     BIGINT      NULL,
    guest_count            INT         NOT NULL,
    token                  VARCHAR(64) NOT NULL,
    qr_expires_at          DATETIME(6) NOT NULL,
    service_charge_enabled BOOLEAN     NOT NULL,
    checkout_in_progress   BOOLEAN     NOT NULL,
    pending_recheckout     BOOLEAN     NOT NULL,
    opened_at              DATETIME(6) NOT NULL,
    closed_at              DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_table_session_token UNIQUE (token),
    CONSTRAINT uk_table_session_bill UNIQUE (business_date, bill_no),
    CONSTRAINT uk_table_session_occupying UNIQUE (occupying_table_id),
    INDEX idx_table_session_status (status, business_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
