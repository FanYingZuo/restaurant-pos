package restaurant_pos.shared.security;

/**
 * 管理者解鎖檢查介面（§4.5）。實作在 staff 模組；table、menu 等只依賴這個介面。
 */
public interface UnlockGate {

    /** 檢查 {@code X-Unlock-Token}：缺少、無效、已失效或功能不符一律回 {@code FORBIDDEN}。 */
    UnlockHolder require(String unlockToken, UnlockFunction function);
}
