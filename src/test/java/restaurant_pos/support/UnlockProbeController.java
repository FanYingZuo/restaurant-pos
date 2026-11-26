package restaurant_pos.support;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.shared.security.UnlockHolder;

/** 測試專用：以 HTTP 呼叫解鎖檢查。只存在於 src/test。 */
@RestController
public class UnlockProbeController {

    private final UnlockGate gate;

    public UnlockProbeController(UnlockGate gate) {
        this.gate = gate;
    }

    @GetMapping("/api/staff/test-unlock")
    public Map<String, Object> check(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @RequestParam UnlockFunction function) {
        UnlockHolder holder = gate.require(token, function);
        return Map.of("employeeId", holder.employeeId());
    }
}
