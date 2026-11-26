-- 階段 S3：菜單（docs/plan/01-PLAN.md §4.2、S3）。
-- 站別：種子 K1、K2，不提供新增（D-11）。
CREATE TABLE station (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    code VARCHAR(8)  NOT NULL,
    name VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_station_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO station (code, name) VALUES ('K1', 'K1'), ('K2', 'K2');

-- 分類：單層；軟刪除；上下架。
CREATE TABLE menu_category (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50) NOT NULL,
    display_order INT         NOT NULL,
    active        BOOLEAN     NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    deleted_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_menu_category_order (deleted_at, display_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 品項：價格不得為負（整數元，DECIMAL(12,2) 但值一律整數）；圖片只存鍵；售完營業日；軟刪除。
CREATE TABLE menu_item (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    category_id    BIGINT         NOT NULL,
    name           VARCHAR(80)    NOT NULL,
    price          DECIMAL(12, 2) NOT NULL,
    station_code   VARCHAR(8)     NOT NULL,
    active         BOOLEAN        NOT NULL,
    sold_out_date  DATE           NULL,
    image_key      VARCHAR(64)    NULL,
    display_order  INT            NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    deleted_at     DATETIME(6)    NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_menu_item_price CHECK (price >= 0),
    INDEX idx_menu_item_category (category_id, deleted_at, display_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 選項群組：最少不得大於最多；軟刪除。
CREATE TABLE modifier_group (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    menu_item_id     BIGINT      NOT NULL,
    name             VARCHAR(50) NOT NULL,
    min_select       INT         NOT NULL,
    max_select       INT         NOT NULL,
    allow_duplicate  BOOLEAN     NOT NULL,
    display_order    INT         NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    deleted_at       DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_modifier_group_range CHECK (min_select <= max_select),
    INDEX idx_modifier_group_item (menu_item_id, deleted_at, display_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 選項：加價不得為負；軟刪除。
CREATE TABLE modifier (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    modifier_group_id   BIGINT         NOT NULL,
    name                VARCHAR(50)    NOT NULL,
    price_delta         DECIMAL(12, 2) NOT NULL,
    display_order       INT            NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    deleted_at          DATETIME(6)    NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_modifier_price CHECK (price_delta >= 0),
    INDEX idx_modifier_group (modifier_group_id, deleted_at, display_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
