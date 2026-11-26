package restaurant_pos.staff.device;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.DeviceContext;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.staff.device.DeviceAdminService.DeviceDto;
import restaurant_pos.staff.device.DeviceAdminService.DevicePairingDto;

/** 裝置管理 API（合約 listDevices、createDevice、regeneratePairingCode、revokeDevice）。 */
@RestController
@RequestMapping("/api/staff/devices")
public class DeviceController {

    public record CreateRequest(String name, DeviceType deviceType) {
    }

    private final DeviceAdminService service;
    private final UnlockGate unlockGate;

    public DeviceController(DeviceAdminService service, UnlockGate unlockGate) {
        this.service = service;
        this.unlockGate = unlockGate;
    }

    private long unlocked(String token) {
        return unlockGate.require(token, UnlockFunction.DEVICE_MANAGE).employeeId();
    }

    @GetMapping
    public List<DeviceDto> list(@RequestHeader(value = "X-Unlock-Token", required = false) String token) {
        unlocked(token);
        return service.list();
    }

    @PostMapping
    public ResponseEntity<DevicePairingDto> create(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @RequestBody CreateRequest request) {
        long actor = unlocked(token);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(request.name(), request.deviceType(), actor));
    }

    @PostMapping("/{deviceId}/pairing-code")
    public ResponseEntity<DevicePairingDto> regenerate(
            @RequestHeader(value = "X-Unlock-Token", required = false) String token, @PathVariable long deviceId) {
        unlocked(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.regenerate(deviceId));
    }

    @PostMapping("/{deviceId}/revoke")
    public DeviceDto revoke(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @PathVariable long deviceId) {
        long actor = unlocked(token);
        long calling = DeviceContext.current().map(d -> d.deviceId())
                .orElseThrow(() -> new ApiException(ErrorCode.DEVICE_TOKEN_INVALID));
        return service.revoke(deviceId, calling, actor);
    }
}
