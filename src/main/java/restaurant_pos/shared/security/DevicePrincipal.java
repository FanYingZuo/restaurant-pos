package restaurant_pos.shared.security;

/** 已通過裝置憑證驗證的裝置。只含識別碼與類型，不含憑證。 */
public record DevicePrincipal(long deviceId, DeviceType type) {
}
