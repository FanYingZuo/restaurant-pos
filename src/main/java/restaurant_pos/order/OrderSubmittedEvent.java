package restaurant_pos.order;

import restaurant_pos.shared.event.DomainEvent;

/** 訂單送出（§4.7）：推播每個品項新增或更新、列印依站別的廚房單。 只帶識別碼，監聽器自己重新讀取。 */
public record OrderSubmittedEvent(long orderId, long sessionId, int batchNo) implements DomainEvent {
}
