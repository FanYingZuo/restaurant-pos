package restaurant_pos.shared.config;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PIN 密鑰（§4.5）：正式環境必填（啟動檢查已保證）；開發未設定時使用開發密鑰並印警告。
 * 密鑰無法輪替。本類別不提供任何會輸出密鑰值的方法。
 */
@Component
public class PosSecrets {

    private static final Logger log = LoggerFactory.getLogger(PosSecrets.class);
    /** 開發專用假密鑰，不可用於正式環境。 */
    static final String DEV_SECRET = "dev-only-pin-secret-not-for-production-0123456789";

    private final byte[] pinSecret;

    public PosSecrets(PosProperties properties, AppEnvironment environment) {
        String configured = properties.pinSecret();
        if (configured == null || configured.isBlank()) {
            if (!environment.isDev()) {
                throw new IllegalStateException("正式環境必須設定 POS_PIN_SECRET");
            }
            log.warn("開發環境未設定 POS_PIN_SECRET，使用開發密鑰（不可用於正式環境）");
            configured = DEV_SECRET;
        }
        this.pinSecret = configured.getBytes(StandardCharsets.UTF_8);
    }

    /** 回傳內部位元組副本。 */
    public byte[] pinSecret() {
        return pinSecret.clone();
    }

    @Override
    public String toString() {
        return "PosSecrets[***]";
    }
}
