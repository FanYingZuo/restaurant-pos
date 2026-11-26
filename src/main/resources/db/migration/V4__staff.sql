-- 階段 S2：員工、出勤（docs/plan/01-PLAN.md §4.2、S2）。
-- 員工：PIN 雜湊唯一（只比對在職員工——停用時雜湊清空為 NULL，唯一索引允許多個 NULL）；停用為軟刪除。
CREATE TABLE employee (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(50)  NOT NULL,
    role           VARCHAR(16)  NOT NULL,
    pin_hash       VARCHAR(64)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    deactivated_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_employee_pin_hash UNIQUE (pin_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 班次：上下班至少一個有值；「已更正」標記。營業日為上班營業日（只有下班時為下班的營業日）。
CREATE TABLE work_shift (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    employee_id   BIGINT      NOT NULL,
    business_date DATE        NOT NULL,
    clock_in_at   DATETIME(6) NULL,
    clock_out_at  DATETIME(6) NULL,
    corrected     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_work_shift_has_time CHECK (clock_in_at IS NOT NULL OR clock_out_at IS NOT NULL),
    INDEX idx_work_shift_employee_date (employee_id, business_date),
    INDEX idx_work_shift_date (business_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 出勤更正歷史：只新增。
CREATE TABLE work_shift_history (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    shift_id          BIGINT       NOT NULL,
    actor_employee_id BIGINT       NOT NULL,
    changed_at        DATETIME(6)  NOT NULL,
    reason            VARCHAR(255) NOT NULL,
    before_clock_in   DATETIME(6)  NULL,
    before_clock_out  DATETIME(6)  NULL,
    after_clock_in    DATETIME(6)  NULL,
    after_clock_out   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_work_shift_history_shift (shift_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
