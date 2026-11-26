package restaurant_pos.shared.image;

/**
 * 圖片儲存介面（docs/plan/01-PLAN.md §4.10）。實作負責驗證、重新編碼與檔名產生；呼叫者只拿到「圖片鍵」。
 */
public interface ImageStorage {

    /**
     * 儲存新圖並（成功後才）刪除舊圖。新圖不合法時丟 {@code INVALID_IMAGE}，舊圖不受影響；
     * 刪舊圖失敗只記日誌，不影響結果。
     *
     * @param content 上傳的原始位元組
     * @param oldKey  舊圖片鍵，可為 null
     * @return 新圖片鍵
     */
    String replace(byte[] content, String oldKey);

    /** 讀取圖片。鍵格式不合法或檔案不存在一律丟 {@code NOT_FOUND}（不洩漏差異）。 */
    StoredImage load(String key);

    /** 刪除圖片。鍵格式不合法時不動作；刪檔失敗只記日誌。 */
    void delete(String key);
}
