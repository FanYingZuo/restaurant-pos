package restaurant_pos.shared.error;

/**
 * 業務錯誤：由全域處理器轉成「錯誤碼＋固定訊息＋時間戳」。
 * 訊息只含錯誤碼名稱；細節只寫日誌（§4.8）。不收集堆疊以降低成本。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
