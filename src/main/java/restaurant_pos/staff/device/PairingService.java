package restaurant_pos.staff.device;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.FailureLimiter;
import restaurant_pos.shared.security.HmacHasher;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.setting.Setting;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 裝置配對（§4.5）：配對碼 8 位英數字、預設 10 分鐘有效、一次性；配對成功產生 32 位元組憑證，**只回傳一次**。
 * DB 只存配對碼與憑證的 HMAC 雜湊。配對錯誤以來源 IP 計算：10 分鐘內錯 10 次鎖 10 分鐘（含正確配對碼也拒絕）。
 * 配對碼不存在、已過期、已使用、裝置已撤銷一律回同一個錯誤，不洩漏差異。
 */
@Service
public class PairingService {

    /** 去除容易混淆的 0/O、1/I。 */
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository devices;
    private final HmacHasher hasher;
    private final BusinessDayService time;
    private final AppSettingService settings;
    private final AuditService audit;
    private final FailureLimiter limiter;

    public PairingService(DeviceRepository devices, HmacHasher hasher, BusinessDayService time,
            AppSettingService settings, AuditService audit, Clock clock) {
        this.devices = devices;
        this.hasher = hasher;
        this.time = time;
        this.settings = settings;
        this.audit = audit;
        this.limiter = new FailureLimiter(10, Duration.ofMinutes(10), Duration.ofMinutes(10), clock);
    }

    /** 建立裝置並產生配對碼。回傳的明碼配對碼只此一次可見。 */
    public record Issued(Device device, String pairingCode) {
    }

    /** 配對成功的結果。{@code credential} 為明碼憑證，只回傳這一次。 */
    public record Paired(long deviceId, String name, DeviceType deviceType, String credential) {
        @Override
        public String toString() {
            return "Paired[deviceId=" + deviceId + ", type=" + deviceType + "]";
        }
    }

    @Transactional
    public Issued createDevice(String name, DeviceType type) {
        return createDevice(name, type, null);
    }

    /** 建立裝置並產生配對碼；{@code actorEmployeeId} 為執行解鎖的管理者（首次啟動自動建立時為 null）。 */
    @Transactional
    public Issued createDevice(String name, DeviceType type, Long actorEmployeeId) {
        Device device = devices.save(new Device(type, name, time.now()));
        String code = issueCode(device);
        audit.record(AuditEntry.of("DEVICE_CREATED", "device", device.getId())
                .withChange(null, type.name()).byEmployee(actorEmployeeId));
        return new Issued(device, code);
    }

    /** 重新產生配對碼：舊碼立即失效。 */
    @Transactional
    public Issued regenerateCode(Device device) {
        String code = issueCode(device);
        return new Issued(device, code);
    }

    private String issueCode(Device device) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = randomCode();
            String hash = hasher.pairingCode(code);
            if (devices.findActiveByPairingCodeHash(hash).isEmpty()) {
                int minutes = settings.getPositiveInt(Setting.DEVICE_PAIRING_MINUTES);
                device.issuePairingCode(hash, time.now().plus(Duration.ofMinutes(minutes)));
                devices.saveAndFlush(device);
                return code;
            }
        }
        throw new IllegalStateException("無法產生唯一的配對碼");
    }

    static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 以配對碼換取憑證。{@code clientIp} 用於防暴力，不寫入任何紀錄。 */
    @Transactional
    public Paired pair(String rawCode, String clientIp) {
        if (limiter.isLocked(clientIp)) {
            throw new ApiException(ErrorCode.PAIRING_LOCKED);
        }
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        Optional<Device> found = devices.lockByPairingCodeHash(hasher.pairingCode(code));
        Instant now = time.now();
        if (found.isEmpty() || found.get().getPairingExpiresAt() == null
                || !found.get().getPairingExpiresAt().isAfter(now)) {
            limiter.recordFailure(clientIp);
            throw new ApiException(ErrorCode.PAIRING_CODE_INVALID);
        }
        Device device = found.get();
        String credential = newCredential();
        device.completePairing(hasher.deviceCredential(credential));
        devices.saveAndFlush(device);
        audit.record(AuditEntry.of("DEVICE_PAIRED", "device", device.getId()).byDevice(device.getId()));
        return new Paired(device.getId(), device.getName(), device.getDeviceType(), credential);
    }

    private static String newCredential() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 清除防暴力狀態（模擬重啟與測試用）。 */
    public void resetLimiter() {
        limiter.clear();
    }
}
