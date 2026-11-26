-- 卡 0.8（待決 #22）：audit_log 補上 §4.5 規定的前值、後值、理由欄位；
-- V1 的 detail 欄位沒有被規格採用，移除。V1 已執行，不修改 V1 本身。
ALTER TABLE audit_log
    ADD COLUMN before_value VARCHAR(1000) NULL AFTER target_id,
    ADD COLUMN after_value  VARCHAR(1000) NULL AFTER before_value,
    ADD COLUMN reason       VARCHAR(255)  NULL AFTER after_value,
    DROP COLUMN detail;

CREATE INDEX idx_audit_log_business_date ON audit_log (business_date, action);
