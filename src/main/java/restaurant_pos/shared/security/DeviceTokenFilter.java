package restaurant_pos.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;
import restaurant_pos.shared.error.ErrorCode;

/**
 * 裝置憑證過濾器（§4.5）：所有店內 API（/api/pos、/api/kds、/api/staff）都必須帶 {@code X-Device-Token}。
 * 唯一例外是配對 API（不在這三個前置詞底下）。
 * <ul>
 *   <li>缺憑證、憑證無效、已撤銷 → 401 DEVICE_TOKEN_INVALID（不區分原因）</li>
 *   <li>裝置類型不符 → 403 DEVICE_TYPE_FORBIDDEN：/api/pos、/api/staff 只限 POS；/api/kds 只限 KDS（S1 決定 #11）</li>
 * </ul>
 * 本類別刻意不是 Spring Bean，由 SecurityConfig 建立並加入安全過濾鏈，避免被重複註冊。
 */
public class DeviceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Token";
    /** 憑證為 32 位元組的網址安全 base64（約 43 字元）；過長的值直接視為無效，避免做無謂的雜湊。 */
    private static final int MAX_TOKEN_LENGTH = 256;

    private final DeviceAuthenticator authenticator;
    private final SecurityErrorWriter errorWriter;

    public DeviceTokenFilter(DeviceAuthenticator authenticator, SecurityErrorWriter errorWriter) {
        this.authenticator = authenticator;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return requiredType(request.getRequestURI()) == null;
    }

    private static DeviceType requiredType(String path) {
        if (matches(path, "/api/kds")) {
            return DeviceType.KDS;
        }
        if (matches(path, "/api/pos") || matches(path, "/api/staff")) {
            return DeviceType.POS;
        }
        return null;
    }

    private static boolean matches(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            errorWriter.write(response, ErrorCode.DEVICE_TOKEN_INVALID);
            return;
        }
        Optional<DevicePrincipal> principal = authenticator.authenticate(token.trim());
        if (principal.isEmpty()) {
            errorWriter.write(response, ErrorCode.DEVICE_TOKEN_INVALID);
            return;
        }
        if (principal.get().type() != requiredType(request.getRequestURI())) {
            errorWriter.write(response, ErrorCode.DEVICE_TYPE_FORBIDDEN);
            return;
        }
        request.setAttribute(DeviceContext.ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }
}
