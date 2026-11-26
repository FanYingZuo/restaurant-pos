package restaurant_pos.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import restaurant_pos.order.ItemCancelledEvent;
import restaurant_pos.order.ItemCompletedEvent;
import restaurant_pos.order.ItemModifiedEvent;
import restaurant_pos.order.ItemStartedEvent;
import restaurant_pos.order.OrderSubmittedEvent;

/** 測試專用：收集訂單事件（提交後），以「類型:編號」字串記錄。只存在於 src/test。 */
@Component
public class OrderEventProbe {

    public final List<String> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderSubmittedEvent e) {
        events.add("SUBMITTED:" + e.orderId() + ":" + e.batchNo());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ItemModifiedEvent e) {
        events.add("MODIFIED:" + e.orderItemId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ItemStartedEvent e) {
        events.add("STARTED:" + e.orderItemId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ItemCompletedEvent e) {
        events.add("COMPLETED:" + e.orderItemId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ItemCancelledEvent e) {
        events.add("CANCELLED:" + e.orderItemId());
    }

    public void clear() {
        events.clear();
    }
}
