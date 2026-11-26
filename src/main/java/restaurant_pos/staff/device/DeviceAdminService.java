package restaurant_pos.staff.device;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 裝置管理（DEVICE_MANAGE，S2）：列表、建立並取得配對碼、重新產生配對碼、撤銷（立即生效、不可撤銷自己）。
 * 回應不含憑證、憑證雜湊或配對碼雜湊；配對碼明碼只在建立與重新產生時回傳一次。
 */
@Service
public class DeviceAdminService {

    public record DeviceDto(long id, String name, DeviceType deviceType, Instant createdAt, Instant lastUsedAt,
            Instant revokedAt, boolean paired) {
        static DeviceDto of(Device d) {
            return new DeviceDto(d.getId(), d.getName(), d.getDeviceType(), d.getCreatedAt(), d.getLastUsedAt(),
                    d.getRevokedAt(), d.isPaired());
        }
    }

    /** 建立或重新產生配對碼的結果；{@code pairingCode} 為明碼，只回傳這一次。 */
    public record DevicePairingDto(DeviceDto device, String pairingCode, Instant pairingExpiresAt) {
        @Override
        public String toString() {
            return "DevicePairingDto[device=" + device + "]";
        }
    }

    private final DeviceRepository devices;
    private final PairingService pairing;
    private final AuditService audit;
    private final BusinessDayService time;

    public DeviceAdminService(DeviceRepository devices, PairingService pairing, AuditService audit,
            BusinessDayService time) {
        this.devices = devices;
        this.pairing = pairing;
        this.audit = audit;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public List<DeviceDto> list() {
        return devices.findAll().stream().sorted((a, b) -> Long.compare(a.getId(), b.getId())).map(DeviceDto::of).toList();
    }

    @Transactional
    public DevicePairingDto create(String name, DeviceType type, long actorEmployeeId) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 100 || type == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        PairingService.Issued issued = pairing.createDevice(clean, type, actorEmployeeId);
        return new DevicePairingDto(DeviceDto.of(issued.device()), issued.pairingCode(),
                issued.device().getPairingExpiresAt());
    }

    @Transactional
    public DevicePairingDto regenerate(long deviceId) {
        Device device = devices.lockById(deviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (device.isRevoked()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        PairingService.Issued issued = pairing.regenerateCode(device);
        return new DevicePairingDto(DeviceDto.of(issued.device()), issued.pairingCode(),
                issued.device().getPairingExpiresAt());
    }

    /** 撤銷：立即生效；不可撤銷自己正在用的裝置；已撤銷則原樣回傳（冪等）。 */
    @Transactional
    public DeviceDto revoke(long deviceId, long callingDeviceId, long actorEmployeeId) {
        if (deviceId == callingDeviceId) {
            throw new ApiException(ErrorCode.DEVICE_SELF_REVOKE);
        }
        Device device = devices.lockById(deviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!device.isRevoked()) {
            device.revoke(time.now());
            devices.saveAndFlush(device);
            audit.record(AuditEntry.of("DEVICE_REVOKED", "device", device.getId()).byEmployee(actorEmployeeId));
        }
        return DeviceDto.of(device);
    }
}
