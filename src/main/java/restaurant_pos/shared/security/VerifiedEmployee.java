package restaurant_pos.shared.security;

/** 通過 PIN 驗證的在職員工（只含識別碼、名稱與是否管理者）。 */
public record VerifiedEmployee(long id, String name, boolean manager) {
}
