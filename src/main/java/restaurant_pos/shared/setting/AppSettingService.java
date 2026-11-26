package restaurant_pos.shared.setting;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * app_setting 讀取（§4.9）：設定值不存在用預設值；設定值不合法用預設值並記警告（警告只含鍵，不含值）。
 */
@Service
public class AppSettingService {

    private static final Logger log = LoggerFactory.getLogger(AppSettingService.class);

    private final AppSettingRepository repository;

    public AppSettingService(AppSettingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<String> raw(Setting setting) {
        return repository.findBySettingKey(setting.key()).map(AppSetting::getSettingValue);
    }

    public String getString(Setting setting) {
        String value = raw(setting).map(String::trim).orElse(null);
        if (value == null) {
            return setting.defaultValue();
        }
        if (value.isEmpty()) {
            return invalid(setting);
        }
        return value;
    }

    /** 正整數；不是整數或小於 1 視為不合法。 */
    public int getPositiveInt(Setting setting) {
        String value = raw(setting).map(String::trim).orElse(null);
        if (value == null) {
            return Integer.parseInt(setting.defaultValue());
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= 1) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的警告
        }
        warn(setting);
        return Integer.parseInt(setting.defaultValue());
    }

    /** 0 到 1（含）之間的小數，例如費率。 */
    public BigDecimal getRate(Setting setting) {
        String value = raw(setting).map(String::trim).orElse(null);
        if (value == null) {
            return new BigDecimal(setting.defaultValue());
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() >= 0 && parsed.compareTo(BigDecimal.ONE) <= 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的警告
        }
        warn(setting);
        return new BigDecimal(setting.defaultValue());
    }

    /** 只接受 true／false（不分大小寫）。 */
    public boolean getBoolean(Setting setting) {
        String value = raw(setting).map(String::trim).orElse(null);
        if (value == null) {
            return Boolean.parseBoolean(setting.defaultValue());
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        warn(setting);
        return Boolean.parseBoolean(setting.defaultValue());
    }

    public ZoneId getZone(Setting setting) {
        String value = raw(setting).map(String::trim).orElse(null);
        if (value == null) {
            return ZoneId.of(setting.defaultValue());
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            warn(setting);
            return ZoneId.of(setting.defaultValue());
        }
    }

    /** 格式 HH:mm。 */
    public LocalTime getTime(Setting setting) {
        String value = raw(setting).map(String::trim).orElse(null);
        if (value == null) {
            return LocalTime.parse(setting.defaultValue());
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            warn(setting);
            return LocalTime.parse(setting.defaultValue());
        }
    }

    private String invalid(Setting setting) {
        warn(setting);
        return setting.defaultValue();
    }

    private void warn(Setting setting) {
        log.warn("設定 {} 的值不合法，改用預設值", setting.key());
    }
}
