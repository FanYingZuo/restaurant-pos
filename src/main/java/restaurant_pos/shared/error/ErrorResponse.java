package restaurant_pos.shared.error;

import java.time.Instant;

/** 錯誤回應只含錯誤碼、固定繁體中文訊息、時間戳（§4.8）。 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code, Instant now) {
        return new ErrorResponse(code.name(), code.message(), now);
    }
}
