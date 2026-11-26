package restaurant_pos.order;

import restaurant_pos.shared.event.DomainEvent;

/** 品項開始製作（§4.7）：推播新增或更新。 只帶識別碼，監聽器自己重新讀取。 */
public record ItemStartedEvent(long orderItemId, long sessionId) implements DomainEvent {
}
