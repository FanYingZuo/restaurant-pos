package restaurant_pos.staff.device;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.ClientIpResolver;
import restaurant_pos.shared.security.DeviceType;

/** 裝置配對 API（合約 pairDevice）：唯一不需要裝置憑證的店內路徑。 */
@RestController
@RequestMapping("/api/pairing")
public class PairingController {

    private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Za-z0-9]{8}$");

    public record PairRequest(String pairingCode) {
        /** 配對碼是機密，不進 toString。 */
        @Override
        public String toString() {
            return "PairRequest[***]";
        }
    }

    public record PairResponse(long deviceId, String name, DeviceType deviceType, String deviceToken) {
        @Override
        public String toString() {
            return "PairResponse[deviceId=" + deviceId + "]";
        }
    }

    private final PairingService pairing;
    private final ClientIpResolver ipResolver;

    public PairingController(PairingService pairing, ClientIpResolver ipResolver) {
        this.pairing = pairing;
        this.ipResolver = ipResolver;
    }

    @PostMapping
    public ResponseEntity<PairResponse> pair(@RequestBody PairRequest request, HttpServletRequest http) {
        if (request == null || request.pairingCode() == null || !CODE_FORMAT.matcher(request.pairingCode().trim()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        PairingService.Paired paired = pairing.pair(request.pairingCode(), ipResolver.resolve(http));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PairResponse(paired.deviceId(), paired.name(), paired.deviceType(), paired.credential()));
    }
}
