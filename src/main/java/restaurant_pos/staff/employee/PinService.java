package restaurant_pos.staff.employee;

import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.DeviceContext;
import restaurant_pos.shared.security.FailureLimiter;
import restaurant_pos.shared.security.HmacHasher;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.security.VerifiedEmployee;

/**
 * PIN 驗證元件（§4.5）：HMAC 比對只比對在職員工；停用員工回「PIN 錯誤」；
 * 防暴力以裝置計算：同裝置 5 分鐘內錯 5 次 → 該裝置鎖 5 分鐘（含正確 PIN 也回「嘗試次數過多」）；
 * 成功不清計數；計數在記憶體；每次鎖定寫稽核（另開交易，不受呼叫者回滾影響）。
 * 本類別不記錄任何 PIN 值。
 */
@Service
public class PinService implements PinVerifier {

    private static final Pattern PIN_FORMAT = Pattern.compile("^[0-9]{4,6}$");
    private static final String NO_DEVICE_KEY = "no-device";

    private final EmployeeRepository employees;
    private final HmacHasher hasher;
    private final AuditService audit;
    private final FailureLimiter limiter;

    public PinService(EmployeeRepository employees, HmacHasher hasher, AuditService audit, Clock clock) {
        this.employees = employees;
        this.hasher = hasher;
        this.audit = audit;
        this.limiter = new FailureLimiter(5, Duration.ofMinutes(5), Duration.ofMinutes(5), clock);
    }

    public static boolean isValidFormat(String pin) {
        return pin != null && PIN_FORMAT.matcher(pin).matches();
    }

    @Override
    public VerifiedEmployee verify(String pin) {
        if (!isValidFormat(pin)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String key = DeviceContext.current().map(d -> String.valueOf(d.deviceId())).orElse(NO_DEVICE_KEY);
        if (limiter.isLocked(key)) {
            throw new ApiException(ErrorCode.PIN_LOCKED);
        }
        return employees.findActiveByPinHash(hasher.pin(pin))
                .map(e -> new VerifiedEmployee(e.getId(), e.getName(), e.isManager()))
                .orElseGet(() -> {
                    boolean lockedNow = limiter.recordFailure(key);
                    if (lockedNow) {
                        audit.recordInNewTransaction(AuditEntry.of("PIN_LOCKED", "device",
                                DeviceContext.current().map(d -> d.deviceId()).orElse(null)));
                    }
                    throw new ApiException(ErrorCode.INVALID_PIN);
                });
    }

    /** 清除防暴力狀態（模擬重啟與測試用）。 */
    public void resetLimiter() {
        limiter.clear();
    }
}
