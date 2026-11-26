-- 階段 S5：訂單（docs/plan/01-PLAN.md §4.2、S5）。
-- 訂單：草稿唯一（draft_session_id 只在草稿時有值）；批次號與請求編號在用餐紀錄內唯一（NULL 不衝突）。
CREATE TABLE orders (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    session_id        BIGINT       NOT NULL,
    batch_no          INT          NULL,
    status            VARCHAR(20)  NOT NULL,
    draft_session_id  BIGINT       NULL,
    request_id        VARCHAR(64)  NULL,
    content_hash      VARCHAR(64)  NULL,
    source            VARCHAR(10)  NOT NULL,
    note              VARCHAR(255) NOT NULL,
    version           INT          NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    submitted_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_draft UNIQUE (draft_session_id),
    CONSTRAINT uk_orders_batch UNIQUE (session_id, batch_no),
    CONSTRAINT uk_orders_request UNIQUE (session_id, request_id),
    INDEX idx_orders_session (session_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 訂單品項：含價格快照；數量至少 1；版本號。session_id 冗餘存放，供廚房清單與結帳一次查詢。
CREATE TABLE order_item (
    id                      BIGINT         NOT NULL AUTO_INCREMENT,
    order_id                BIGINT         NOT NULL,
    session_id              BIGINT         NOT NULL,
    menu_item_id            BIGINT         NOT NULL,
    name                    VARCHAR(80)    NOT NULL,
    base_price              DECIMAL(12, 2) NOT NULL,
    unit_price              DECIMAL(12, 2) NOT NULL,
    quantity                INT            NOT NULL,
    station_code            VARCHAR(8)     NOT NULL,
    note                    VARCHAR(255)   NOT NULL,
    status                  VARCHAR(16)    NOT NULL,
    version                 INT            NOT NULL,
    cancel_reason           VARCHAR(255)   NULL,
    cancelled_at            DATETIME(6)    NULL,
    cancelled_by_employee   BIGINT         NULL,
    created_at              DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_order_item_quantity CHECK (quantity >= 1),
    INDEX idx_order_item_order (order_id),
    INDEX idx_order_item_session (session_id),
    INDEX idx_order_item_station (station_code, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 選項快照。
CREATE TABLE order_item_modifier (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    order_item_id  BIGINT         NOT NULL,
    modifier_id    BIGINT         NOT NULL,
    name           VARCHAR(50)    NOT NULL,
    price_delta    DECIMAL(12, 2) NOT NULL,
    quantity       INT            NOT NULL,
    sort_order     INT            NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_order_item_modifier_item (order_item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 訂單歷史：只新增（送出、修改、取消、備註變更）；前後摘要為文字。
CREATE TABLE order_history (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    session_id      BIGINT        NOT NULL,
    order_id        BIGINT        NOT NULL,
    order_item_id   BIGINT        NULL,
    action          VARCHAR(32)   NOT NULL,
    before_summary  VARCHAR(1000) NULL,
    after_summary   VARCHAR(1000) NULL,
    employee_id     BIGINT        NULL,
    reason          VARCHAR(255)  NULL,
    created_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_order_history_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
