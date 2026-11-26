package restaurant_pos.order;

import restaurant_pos.shared.event.DomainEvent;

/** 品項完成（§4.7）：推播移除。 只帶識別碼，監聽器自己重新讀取。 */
public record ItemCompletedEvent(long orderItemId, long sessionId) implements DomainEvent {
}
