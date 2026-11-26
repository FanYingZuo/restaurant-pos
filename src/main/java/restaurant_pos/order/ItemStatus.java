package restaurant_pos.order;

/** 品項狀態：待處理、製作中、已完成、已取消。已完成與已取消是終點。 */
public enum ItemStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    CANCELLED
}
