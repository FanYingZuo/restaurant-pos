package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.setting.Setting;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 0.6：注入式時鐘、營業日服務、app_setting 讀取（01-PLAN §4.4、§4.9）。 */
class BusinessDayAndSettingTest extends AbstractIntegrationTest {

    @Autowired
    BusinessDayService businessDay;

    @Autowired
    AppSettingService settings;

    private ListAppender<ILoggingEvent> appender;
    private Logger settingLogger;

    @BeforeEach
    void captureLogs() {
        settingLogger = (Logger) LoggerFactory.getLogger(AppSettingService.class);
        appender = new ListAppender<>();
        appender.start();
        settingLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        settingLogger.detachAppender(appender);
    }

    private void putSetting(String key, String value) {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES (?, ?, NOW(6))", key, value);
    }

    private long warningsFor(String key) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(key)).count();
    }

    // ---- 營業日（預設台北、04:00 切換） ----

    @Test
    void 台北0359_59屬於前一個營業日() {
        // 台北 2026-03-02 03:59:59 ＝ UTC 2026-03-01 19:59:59
        assertThat(businessDay.businessDateOf(Instant.parse("2026-03-01T19:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void 台北0400整屬於當天營業日() {
        assertThat(businessDay.businessDateOf(Instant.parse("2026-03-01T20:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void 午夜零點屬於前一個營業日_中午屬於當天() {
        // 台北 2026-03-02 00:00 ＝ UTC 2026-03-01 16:00；台北 12:00 ＝ UTC 04:00
        assertThat(businessDay.businessDateOf(Instant.parse("2026-03-01T16:00:00Z"))).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(businessDay.businessDateOf(Instant.parse("2026-03-02T04:00:00Z"))).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void 今天營業日來自注入的時鐘() {
        clock.set(Instant.parse("2026-03-01T19:59:59Z"));
        assertThat(businessDay.today()).isEqualTo(LocalDate.of(2026, 3, 1));
        clock.set(Instant.parse("2026-03-01T20:00:00Z"));
        assertThat(businessDay.today()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void 營業日起訖時間以切換點計算() {
        LocalDate day = LocalDate.of(2026, 3, 2);
        assertThat(businessDay.startOf(day)).isEqualTo(Instant.parse("2026-03-01T20:00:00Z"));
        assertThat(businessDay.endOf(day)).isEqualTo(Instant.parse("2026-03-02T20:00:00Z"));
    }

    @Test
    void 切換點可由設定修改() {
        putSetting("business.day.cutover", "05:30");
        assertThat(businessDay.businessDateOf(Instant.parse("2026-03-01T21:29:00Z"))).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(businessDay.businessDateOf(Instant.parse("2026-03-01T21:30:00Z"))).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    // ---- app_setting：預設值與不合法值 ----

    @Test
    void 沒有設定時使用規格預設值且不警告() {
        assertThat(settings.getZone(Setting.BUSINESS_ZONE)).isEqualTo(ZoneId.of("Asia/Taipei"));
        assertThat(settings.getTime(Setting.BUSINESS_DAY_CUTOVER)).isEqualTo(LocalTime.of(4, 0));
        assertThat(settings.getPositiveInt(Setting.CLOCK_MIN_INTERVAL_SECONDS)).isEqualTo(60);
        assertThat(settings.getPositiveInt(Setting.UNLOCK_IDLE_MINUTES)).isEqualTo(30);
        assertThat(settings.getPositiveInt(Setting.QR_VALIDITY_HOURS)).isEqualTo(4);
        assertThat(settings.getString(Setting.SERVICE_CHARGE_NAME)).isEqualTo("服務費");
        assertThat(settings.getRate(Setting.SERVICE_CHARGE_RATE)).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(settings.getBoolean(Setting.SERVICE_CHARGE_DEFAULT_ENABLED)).isTrue();
        assertThat(settings.getString(Setting.STORE_NAME)).isEqualTo("餐廳");
        assertThat(settings.getPositiveInt(Setting.CUSTOMER_ORDER_MIN_INTERVAL_SECONDS)).isEqualTo(1);
        assertThat(settings.getPositiveInt(Setting.SHIFT_CORRECTION_MAX_DAYS)).isEqualTo(31);
        assertThat(settings.getPositiveInt(Setting.DEVICE_PAIRING_MINUTES)).isEqualTo(10);
        assertThat(settings.getPositiveInt(Setting.KDS_REFRESH_SECONDS)).isEqualTo(60);
        assertThat(settings.getPositiveInt(Setting.PRINT_RETRY_MAX)).isEqualTo(5);
        assertThat(settings.getPositiveInt(Setting.PRINT_POLL_SECONDS)).isEqualTo(2);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void 合法的設定值會被採用() {
        putSetting("clock.min.interval.seconds", "90");
        putSetting("service_charge.rate", "0.15");
        putSetting("service_charge.default_enabled", "FALSE");
        putSetting("store.name", "阿財麵館");
        assertThat(settings.getPositiveInt(Setting.CLOCK_MIN_INTERVAL_SECONDS)).isEqualTo(90);
        assertThat(settings.getRate(Setting.SERVICE_CHARGE_RATE)).isEqualByComparingTo(new BigDecimal("0.15"));
        assertThat(settings.getBoolean(Setting.SERVICE_CHARGE_DEFAULT_ENABLED)).isFalse();
        assertThat(settings.getString(Setting.STORE_NAME)).isEqualTo("阿財麵館");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void 不合法的營業日設定用預設值並記警告() {
        putSetting("business.day.cutover", "25:99");
        putSetting("business.zone", "Mars/Phobos");
        assertThat(settings.getTime(Setting.BUSINESS_DAY_CUTOVER)).isEqualTo(LocalTime.of(4, 0));
        assertThat(settings.getZone(Setting.BUSINESS_ZONE)).isEqualTo(ZoneId.of("Asia/Taipei"));
        assertThat(warningsFor("business.day.cutover")).isEqualTo(1);
        assertThat(warningsFor("business.zone")).isEqualTo(1);
        // 營業日仍依預設值（台北 04:00）計算
        assertThat(businessDay.businessDateOf(Instant.parse("2026-03-01T20:00:00Z"))).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void 不合法的數值布林費率用預設值並記警告() {
        putSetting("clock.min.interval.seconds", "abc");
        putSetting("unlock.idle.minutes", "0");
        putSetting("qr.validity.hours", "-5");
        putSetting("service_charge.rate", "1.5");
        putSetting("service_charge.default_enabled", "yes");
        putSetting("store.name", "   ");
        assertThat(settings.getPositiveInt(Setting.CLOCK_MIN_INTERVAL_SECONDS)).isEqualTo(60);
        assertThat(settings.getPositiveInt(Setting.UNLOCK_IDLE_MINUTES)).isEqualTo(30);
        assertThat(settings.getPositiveInt(Setting.QR_VALIDITY_HOURS)).isEqualTo(4);
        assertThat(settings.getRate(Setting.SERVICE_CHARGE_RATE)).isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(settings.getBoolean(Setting.SERVICE_CHARGE_DEFAULT_ENABLED)).isTrue();
        assertThat(settings.getString(Setting.STORE_NAME)).isEqualTo("餐廳");
        for (String key : new String[] {"clock.min.interval.seconds", "unlock.idle.minutes", "qr.validity.hours",
                "service_charge.rate", "service_charge.default_enabled", "store.name"}) {
            assertThat(warningsFor(key)).as(key).isEqualTo(1);
        }
    }

    @Test
    void 警告訊息不含設定值() {
        putSetting("clock.min.interval.seconds", "secret-looking-value");
        settings.getPositiveInt(Setting.CLOCK_MIN_INTERVAL_SECONDS);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain("secret-looking-value");
    }
}
