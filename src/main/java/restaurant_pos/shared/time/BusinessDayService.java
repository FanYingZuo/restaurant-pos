package restaurant_pos.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.setting.Setting;

/**
 * 營業日服務（§4.4）：營業日時區預設台北、每天 04:00 切換；「現在」只來自注入的時鐘。
 * 營業日在資料建立時寫入，之後不重算。
 */
@Service
public class BusinessDayService {

    private final Clock clock;
    private final AppSettingService settings;

    public BusinessDayService(Clock clock, AppSettingService settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public Instant now() {
        return clock.instant();
    }

    /** 今天的營業日。 */
    public LocalDate today() {
        return businessDateOf(clock.instant());
    }

    public LocalDate businessDateOf(Instant instant) {
        ZoneId zone = settings.getZone(Setting.BUSINESS_ZONE);
        LocalTime cutover = settings.getTime(Setting.BUSINESS_DAY_CUTOVER);
        ZonedDateTime local = instant.atZone(zone);
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(cutover) ? date.minusDays(1) : date;
    }

    /** 該營業日的起點（含）。 */
    public Instant startOf(LocalDate businessDate) {
        ZoneId zone = settings.getZone(Setting.BUSINESS_ZONE);
        LocalTime cutover = settings.getTime(Setting.BUSINESS_DAY_CUTOVER);
        return businessDate.atTime(cutover).atZone(zone).toInstant();
    }

    /** 該營業日的終點（不含）＝下一個營業日的起點。 */
    public Instant endOf(LocalDate businessDate) {
        return startOf(businessDate.plusDays(1));
    }

    /** 轉成台北（營業日時區）時間，供收據與畫面使用。 */
    public ZonedDateTime inBusinessZone(Instant instant) {
        return instant.atZone(settings.getZone(Setting.BUSINESS_ZONE));
    }
}
