package restaurant_pos.shared.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * 啟動檢查（§4.9）：正式環境缺 PIN 密鑰（≥32 字元）、公開基底網址或資料庫連線時拒絕啟動。
 * 在建立任何 Bean 之前執行；錯誤訊息只寫設定名稱，不含任何值。
 */
public class StartupEnvironmentCheck implements EnvironmentPostProcessor, Ordered {

    static final int MIN_SECRET_LENGTH = 32;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        require(environment.getProperty("restaurantpos.base-url"), "POS_BASE_URL（公開基底網址）");
        require(environment.getProperty("spring.datasource.url"), "POS_DB_URL（資料庫連線）");
        String secret = environment.getProperty("restaurantpos.pin-secret");
        require(secret, "POS_PIN_SECRET（PIN 密鑰）");
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("正式環境的 POS_PIN_SECRET 長度必須至少 " + MIN_SECRET_LENGTH + " 字元");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("正式環境必須設定 " + name + "，拒絕啟動");
        }
    }

    /** 必須在設定檔載入之後執行。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
