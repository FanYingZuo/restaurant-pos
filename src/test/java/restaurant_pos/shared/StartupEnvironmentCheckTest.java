package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import restaurant_pos.shared.config.StartupEnvironmentCheck;

/** 卡 0.4：預設正式；正式缺密鑰／基底網址／資料庫拒絕啟動（01-PLAN §4.9）。 */
class StartupEnvironmentCheckTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";

    private final StartupEnvironmentCheck check = new StartupEnvironmentCheck();

    private MockEnvironment prodWithAll() {
        return new MockEnvironment()
                .withProperty("restaurantpos.base-url", "https://pos.example.test")
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/x")
                .withProperty("restaurantpos.pin-secret", SECRET_32);
    }

    @Test
    void 沒有設定任何環境時視為正式_缺必要設定拒絕啟動() {
        MockEnvironment env = new MockEnvironment();
        assertThatThrownBy(() -> check.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 正式環境設定齊全可以啟動() {
        assertThatCode(() -> check.postProcessEnvironment(prodWithAll(), null)).doesNotThrowAnyException();
    }

    @Test
    void 正式環境缺PIN密鑰拒絕啟動() {
        MockEnvironment env = prodWithAll().withProperty("restaurantpos.pin-secret", "");
        assertThatThrownBy(() -> check.postProcessEnvironment(env, null))
                .hasMessageContaining("POS_PIN_SECRET");
    }

    @Test
    void 正式環境PIN密鑰不足32字元拒絕啟動() {
        MockEnvironment env = prodWithAll().withProperty("restaurantpos.pin-secret", SECRET_32.substring(1));
        assertThatThrownBy(() -> check.postProcessEnvironment(env, null))
                .hasMessageContaining("32");
    }

    @Test
    void 正式環境缺基底網址拒絕啟動() {
        MockEnvironment env = prodWithAll().withProperty("restaurantpos.base-url", " ");
        assertThatThrownBy(() -> check.postProcessEnvironment(env, null))
                .hasMessageContaining("POS_BASE_URL");
    }

    @Test
    void 正式環境缺資料庫連線拒絕啟動() {
        MockEnvironment env = prodWithAll().withProperty("spring.datasource.url", "");
        assertThatThrownBy(() -> check.postProcessEnvironment(env, null))
                .hasMessageContaining("POS_DB_URL");
    }

    @Test
    void 錯誤訊息不含任何設定值() {
        MockEnvironment env = prodWithAll().withProperty("restaurantpos.pin-secret", "short-secret-value");
        assertThatThrownBy(() -> check.postProcessEnvironment(env, null))
                .hasMessageNotContaining("short-secret-value");
    }

    @Test
    void 開發環境不要求任何必要設定() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        assertThatCode(() -> check.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }
}
