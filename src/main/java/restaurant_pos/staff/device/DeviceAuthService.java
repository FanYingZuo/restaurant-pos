package restaurant_pos.staff.device;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.shared.security.DeviceAuthenticator;
import restaurant_pos.shared.security.DevicePrincipal;
import restaurant_pos.shared.security.HmacHasher;
import restaurant_pos.shared.time.BusinessDayService;

/** 裝置憑證驗證（§4.5）：憑證只比對 HMAC 雜湊；不存在與已撤銷同樣回空。 */
@Service
public class DeviceAuthService implements DeviceAuthenticator {

    private final DeviceRepository devices;
    private final HmacHasher hasher;
    private final BusinessDayService time;

    public DeviceAuthService(DeviceRepository devices, HmacHasher hasher, BusinessDayService time) {
        this.devices = devices;
        this.hasher = hasher;
        this.time = time;
    }

    @Override
    @Transactional
    public Optional<DevicePrincipal> authenticate(String credential) {
        Optional<Device> device = devices.findActiveByCredentialHash(hasher.deviceCredential(credential));
        device.ifPresent(d -> {
            Instant minute = time.now().truncatedTo(ChronoUnit.MINUTES);
            devices.touch(d.getId(), minute);
        });
        return device.map(d -> new DevicePrincipal(d.getId(), d.getDeviceType()));
    }

    /** 裝置是否仍有效（已配對且未撤銷）。WebSocket 每次心跳重新檢查（卡 6.3）。 */
    @Override
    @Transactional(readOnly = true)
    public boolean isActive(long deviceId) {
        return devices.findById(deviceId).filter(d -> !d.isRevoked() && d.isPaired()).isPresent();
    }
}
