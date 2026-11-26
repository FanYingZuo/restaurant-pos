package restaurant_pos.order;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.model.Station;
import restaurant_pos.table.SessionStatus;
import restaurant_pos.table.SessionView;
import restaurant_pos.table.TableSessionService;

/**
 * 廚房品項狀態服務（S5 卡 5.8）：開始製作、完成。
 * 檢查依序：品項存在 → 站別符合 → 用餐紀錄使用中或已結束 → 未取消 → 版本相符 → 轉換合法；
 * <b>不檢查結帳旗標</b>（用餐紀錄已取消才拒絕）；只發事件（推播），不寫歷史。
 * 供 KDS 模組呼叫，KDS 不重複實作這些規則。
 */
@Service
public class KitchenItemService {

    /** 變更結果。 */
    public record Result(long orderItemId, ItemStatus status, int version) {
    }

    private final TableSessionService sessions;
    private final DiningOrderRepository orders;
    private final OrderItemRepository items;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public KitchenItemService(TableSessionService sessions, DiningOrderRepository orders, OrderItemRepository items,
            ApplicationEventPublisher events, TransactionTemplate tx) {
        this.sessions = sessions;
        this.orders = orders;
        this.items = items;
        this.events = events;
        this.tx = tx;
    }

    public Result start(Station station, long orderItemId, Integer expectedVersion) {
        return transition(station, orderItemId, expectedVersion, ItemStatus.IN_PROGRESS);
    }

    public Result complete(Station station, long orderItemId, Integer expectedVersion) {
        return transition(station, orderItemId, expectedVersion, ItemStatus.DONE);
    }

    private Result transition(Station station, long orderItemId, Integer expectedVersion, ItemStatus target) {
        if (expectedVersion == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        // 先（不鎖）查出用餐紀錄與訂單，鎖住後重新載入再檢查
        OrderItem peek = items.findById(orderItemId).orElseThrow(() -> new ApiException(ErrorCode.KDS_ITEM_NOT_FOUND));
        return tx.execute(status -> {
            SessionView view = sessions.lockById(peek.getSessionId());
            DiningOrder order = orders.lockById(peek.getOrderId())
                    .orElseThrow(() -> new ApiException(ErrorCode.KDS_ITEM_NOT_FOUND));
            OrderItem item = items.lockById(orderItemId)
                    .orElseThrow(() -> new ApiException(ErrorCode.KDS_ITEM_NOT_FOUND));

            if (order.isDraft()) {
                throw new ApiException(ErrorCode.KDS_ITEM_NOT_FOUND); // 草稿品項廚房看不到
            }
            if (item.getStation() != station) {
                throw new ApiException(ErrorCode.KDS_STATION_FORBIDDEN);
            }
            if (view.status() == SessionStatus.CANCELLED) {
                throw new ApiException(ErrorCode.SESSION_NOT_OPEN);
            }
            if (item.getStatus() == ItemStatus.CANCELLED) {
                throw new ApiException(ErrorCode.ORDER_ITEM_CANCELLED);
            }
            if (item.getVersion() != expectedVersion) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            if (!OrderRules.isLegalTransition(item.getStatus(), target)) {
                throw new ApiException(ErrorCode.INVALID_ORDER_ITEM_TRANSITION);
            }

            item.moveTo(target);
            items.saveAndFlush(item);
            order.applyStatus(OrderRules.recalculate(items.findByOrder(order.getId()).stream().map(OrderItem::getStatus).toList()));
            orders.saveAndFlush(order);
            events.publishEvent(target == ItemStatus.IN_PROGRESS
                    ? new ItemStartedEvent(item.getId(), order.getSessionId())
                    : new ItemCompletedEvent(item.getId(), order.getSessionId()));
            return new Result(item.getId(), item.getStatus(), item.getVersion());
        });
    }
}
