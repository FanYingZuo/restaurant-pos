package restaurant_pos.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.error.ErrorResponse;

/** 在過濾器層（Controller 之外）寫出與全域處理器相同格式的錯誤回應。 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SecurityErrorWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(code, clock.instant())));
    }
}
