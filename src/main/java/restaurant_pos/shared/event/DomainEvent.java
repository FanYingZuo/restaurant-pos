package restaurant_pos.shared.event;

/**
 * 事件介面（docs/plan/01-PLAN.md §4.7）：業務服務不直接列印或推播，一律發佈事件。
 * 事件只帶識別碼與少量欄位，監聽器自己重新讀取。事件類別放發佈者模組，監聽器可放接收者模組。
 *
 * <p>推播監聽器用 {@code @TransactionalEventListener(AFTER_COMMIT)}（例外只記日誌）；
 * 列印監聽器用 {@code @TransactionalEventListener(BEFORE_COMMIT)}（丟例外時整個交易回滾）。
 */
public interface DomainEvent {
}
