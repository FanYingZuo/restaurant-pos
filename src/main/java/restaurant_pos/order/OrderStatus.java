package restaurant_pos.order;

/**
 * 訂單狀態。{@code DRAFT} 只存在於內部（每個用餐紀錄最多一張草稿，對外合約不出現）；
 * 其餘為正式單狀態，只看未取消品項重算（見 {@link OrderRules#recalculate}）。
 */
public enum OrderStatus {
    DRAFT,
    SUBMITTED,
    IN_PROGRESS,
    PARTIALLY_SERVED,
    SERVED,
    CANCELLED
}
