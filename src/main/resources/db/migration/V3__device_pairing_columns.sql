-- 卡 0.10（待決 #22）：device 補上最後使用時間與配對碼欄位。
-- 裝置建立時還沒有憑證（配對成功才產生），所以 credential_hash 改為可空（唯一索引允許多個 NULL）。
ALTER TABLE device
    MODIFY credential_hash VARCHAR(64) NULL,
    ADD COLUMN last_used_at        DATETIME(6) NULL,
    ADD COLUMN pairing_code_hash   VARCHAR(64) NULL,
    ADD COLUMN pairing_expires_at  DATETIME(6) NULL;

CREATE UNIQUE INDEX uk_device_pairing_code_hash ON device (pairing_code_hash);
