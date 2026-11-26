package restaurant_pos.shared.security;

/** 管理者解鎖的功能代碼（§4.5）：解鎖憑證只對單一功能有效。 */
public enum UnlockFunction {
    EMPLOYEE_MANAGE,
    TABLE_LAYOUT,
    DEVICE_MANAGE
}
