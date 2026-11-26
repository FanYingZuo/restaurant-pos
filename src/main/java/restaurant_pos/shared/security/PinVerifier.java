package restaurant_pos.shared.security;

/**
 * PIN 驗證介面（§4.5）：「所有用 PIN 找員工」都經過同一個驗證元件。實作在 staff 模組；
 * 其他模組（table、order、checkout、report）只依賴這個介面，符合 §4.1 的依賴方向。
 *
 * <p>防暴力以「目前請求的裝置」計算：同裝置 5 分鐘內錯 5 次 → 鎖 5 分鐘（含正確 PIN 也拒絕）。
 */
public interface PinVerifier {

    /**
     * 驗證 PIN 並回傳在職員工。員工已停用與 PIN 不存在一律回 {@code INVALID_PIN}（不洩漏「已停用」）；
     * 被鎖定回 {@code PIN_LOCKED}。PIN 格式不合法（不是 4～6 位數字）回 {@code VALIDATION_ERROR}。
     */
    VerifiedEmployee verify(String pin);
}
