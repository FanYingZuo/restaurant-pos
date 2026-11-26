package restaurant_pos.order;

import restaurant_pos.shared.event.DomainEvent;

/** 品項取消（§4.7）：推播移除、列印取消單。 只帶識別碼，監聽器自己重新讀取。 */
public record ItemCancelledEvent(long orderItemId, long sessionId) implements DomainEvent {
}
