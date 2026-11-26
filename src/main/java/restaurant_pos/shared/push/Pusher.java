package restaurant_pos.shared.push;

/**
 * 推播器介面（§4.7）：測試可替換。實作必須吞掉並記錄自己的失敗，不得讓例外影響 API 結果。
 */
public interface Pusher {

    /** 送出訊息到主題（例如 /topic/kds/K1）。訊息會被序列化為 JSON。 */
    void push(String topic, Object message);
}
