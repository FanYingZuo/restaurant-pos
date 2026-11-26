package restaurant_pos.support;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.security.VerifiedEmployee;

/** 測試專用：以 HTTP 呼叫 PIN 驗證元件。只存在於 src/test。 */
@RestController
public class PinProbeController {

    public record PinBody(String pin) {
    }

    private final PinVerifier verifier;

    public PinProbeController(PinVerifier verifier) {
        this.verifier = verifier;
    }

    @PostMapping("/api/pos/test-pin")
    public Map<String, Object> verify(@RequestBody PinBody body) {
        VerifiedEmployee employee = verifier.verify(body.pin());
        return Map.of("id", employee.id(), "name", employee.name(), "manager", employee.manager());
    }
}
