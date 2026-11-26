package restaurant_pos.shared.security;

import java.util.Optional;

/**
 * 裝置憑證驗證介面。實作在 staff 模組（裝置歸 staff 管）；shared 的過濾器只依賴這個介面，
 * 以符合 §4.1「shared 不依賴任何模組」。
 */
public interface DeviceAuthenticator {

    /**
     * 驗證裝置憑證。憑證不存在與已撤銷**不得區分**（回傳同樣的空值）；通過時順便更新最後使用時間（以分鐘為單位）。
     */
    Optional<DevicePrincipal> authenticate(String credential);
}
