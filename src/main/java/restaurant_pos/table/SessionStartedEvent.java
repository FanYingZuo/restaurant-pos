package restaurant_pos.table;

import restaurant_pos.shared.event.DomainEvent;

/** 用餐開始（§4.7）：列印 QR 單。只帶識別碼，監聽器自己重新讀取。 */
public record SessionStartedEvent(long sessionId) implements DomainEvent {
}
