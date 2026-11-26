package restaurant_pos.table;

import restaurant_pos.shared.event.DomainEvent;

/** 用餐取消（含結束不收費，§4.7）：KDS 兩站整板重新載入。 */
public record SessionCancelledEvent(long sessionId) implements DomainEvent {
}
