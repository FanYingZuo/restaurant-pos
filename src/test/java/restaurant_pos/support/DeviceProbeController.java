package restaurant_pos.support;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.security.DeviceContext;

/** 測試專用：各前置詞底下的探針端點，驗證裝置憑證過濾器。只存在於 src/test。 */
@RestController
public class DeviceProbeController {

    private Map<String, Object> who() {
        return DeviceContext.current()
                .<Map<String, Object>>map(d -> Map.of("deviceId", d.deviceId(), "type", d.type().name()))
                .orElse(Map.of("deviceId", -1, "type", "NONE"));
    }

    @GetMapping("/api/pos/test-ping")
    public Map<String, Object> pos() {
        return who();
    }

    @GetMapping("/api/kds/test-ping")
    public Map<String, Object> kds() {
        return who();
    }

    @GetMapping("/api/staff/test-ping")
    public Map<String, Object> staff() {
        return who();
    }
}
