package restaurant_pos.shared.setting;

/** app_setting 設定鍵與預設值（docs/plan/01-PLAN.md §4.9）。不在此表的鍵不得讀取。 */
public enum Setting {

    BUSINESS_ZONE("business.zone", "Asia/Taipei"),
    BUSINESS_DAY_CUTOVER("business.day.cutover", "04:00"),
    CLOCK_MIN_INTERVAL_SECONDS("clock.min.interval.seconds", "60"),
    UNLOCK_IDLE_MINUTES("unlock.idle.minutes", "30"),
    QR_VALIDITY_HOURS("qr.validity.hours", "4"),
    SERVICE_CHARGE_NAME("service_charge.name", "服務費"),
    SERVICE_CHARGE_RATE("service_charge.rate", "0.10"),
    SERVICE_CHARGE_DEFAULT_ENABLED("service_charge.default_enabled", "true"),
    STORE_NAME("store.name", "餐廳"),
    CUSTOMER_ORDER_MIN_INTERVAL_SECONDS("customer.order.min.interval.seconds", "1"),
    SHIFT_CORRECTION_MAX_DAYS("shift.correction.max.days", "31"),
    DEVICE_PAIRING_MINUTES("device.pairing.minutes", "10"),
    KDS_REFRESH_SECONDS("kds.refresh.seconds", "60"),
    PRINT_RETRY_MAX("print.retry.max", "5"),
    PRINT_POLL_SECONDS("print.poll.seconds", "2");

    private final String key;
    private final String defaultValue;

    Setting(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }
}
