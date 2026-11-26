package restaurant_pos.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** 目前請求的裝置（由裝置憑證過濾器放入請求屬性）。沒有請求或未驗證時為空。 */
public final class DeviceContext {

    public static final String ATTRIBUTE = DeviceContext.class.getName() + ".principal";

    private DeviceContext() {
    }

    public static Optional<DevicePrincipal> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof DevicePrincipal principal ? Optional.of(principal) : Optional.empty();
    }

    public static Optional<DevicePrincipal> from(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof DevicePrincipal principal ? Optional.of(principal) : Optional.empty();
    }
}
