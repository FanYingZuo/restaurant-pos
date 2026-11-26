package restaurant_pos.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import restaurant_pos.shared.config.PosProperties;

/**
 * 來源 IP（§4.5）：只在請求來自「信任代理」時才採信 X-Forwarded-For，並從右往左略過信任代理取第一個非信任位址。
 * 來源 IP 只用於日誌與配對防暴力。
 */
@Component
public class ClientIpResolver {

    private static final Pattern ADDRESS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final Set<String> trustedProxies;

    public ClientIpResolver(PosProperties properties) {
        this.trustedProxies = Set.copyOf(properties.trustedProxies().stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!trustedProxies.contains(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        List<String> hops = Arrays.stream(forwarded.split(",")).map(String::trim).toList();
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!ADDRESS.matcher(hop).matches()) {
                return remote;
            }
            if (!trustedProxies.contains(hop)) {
                return hop;
            }
        }
        return remote;
    }
}
