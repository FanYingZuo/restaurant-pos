package restaurant_pos.staff.unlock;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.HmacHasher;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.shared.security.UnlockHolder;
import restaurant_pos.shared.security.VerifiedEmployee;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.setting.Setting;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.staff.employee.Employee;
import restaurant_pos.staff.employee.EmployeeRepository;

/**
 * 管理者解鎖（§4.5）：管理者 PIN＋功能代碼 → 32 位元組隨機憑證（網址安全 base64），存記憶體、只對該功能有效。
 * 失效：呼叫釋放 API、閒置 30 分鐘（設定 unlock.idle.minutes）、解鎖者停用或不再是管理者、服務重啟。
 * 記憶體只存憑證的雜湊，不存明碼；本類別不記錄任何憑證值。
 */
@Service
public class UnlockService implements UnlockGate {

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(UnlockFunction function, long employeeId, Instant lastUsed) {
    }

    /** 解鎖結果：{@code token} 為明碼憑證，只回傳這一次。 */
    public record Unlocked(String token, UnlockFunction function) {
        @Override
        public String toString() {
            return "Unlocked[function=" + function + "]";
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final PinVerifier pins;
    private final EmployeeRepository employees;
    private final HmacHasher hasher;
    private final BusinessDayService time;
    private final AppSettingService settings;
    private final AuditService audit;

    public UnlockService(PinVerifier pins, EmployeeRepository employees, HmacHasher hasher, BusinessDayService time,
            AppSettingService settings, AuditService audit) {
        this.pins = pins;
        this.employees = employees;
        this.hasher = hasher;
        this.time = time;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional
    public Unlocked unlock(String pin, UnlockFunction function) {
        VerifiedEmployee employee = pins.verify(pin);
        if (!employee.manager()) {
            throw new ApiException(ErrorCode.NOT_MANAGER);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        entries.put(hasher.unlockToken(token), new Entry(function, employee.id(), time.now()));
        audit.record(AuditEntry.of("ADMIN_UNLOCKED", "unlock", function.name()).byEmployee(employee.id()));
        return new Unlocked(token, function);
    }

    @Override
    @Transactional(readOnly = true)
    public UnlockHolder require(String unlockToken, UnlockFunction function) {
        if (unlockToken == null || unlockToken.isBlank() || unlockToken.length() > 256) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String key = hasher.unlockToken(unlockToken.trim());
        Entry entry = entries.get(key);
        if (entry == null) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        Instant now = time.now();
        Duration idle = Duration.ofMinutes(settings.getPositiveInt(Setting.UNLOCK_IDLE_MINUTES));
        if (!now.isBefore(entry.lastUsed().plus(idle))) {
            entries.remove(key);
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (entry.function() != function) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        Employee holder = employees.findById(entry.employeeId()).orElse(null);
        if (holder == null || !holder.isActive() || !holder.isManager()) {
            entries.remove(key);
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        entries.put(key, new Entry(entry.function(), entry.employeeId(), now));
        return new UnlockHolder(entry.employeeId());
    }

    /** 釋放憑證（冪等：不存在也不報錯）。 */
    public void release(String unlockToken) {
        if (unlockToken != null && !unlockToken.isBlank() && unlockToken.length() <= 256) {
            entries.remove(hasher.unlockToken(unlockToken.trim()));
        }
    }

    /** 清除全部解鎖（模擬服務重啟與測試用）。 */
    public void clear() {
        entries.clear();
    }
}
