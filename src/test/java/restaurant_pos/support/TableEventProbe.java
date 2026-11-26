package restaurant_pos.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import restaurant_pos.table.SessionCancelledEvent;
import restaurant_pos.table.SessionStartedEvent;

/** 測試專用：收集用餐開始與用餐取消事件（提交後）。只存在於 src/test。 */
@Component
public class TableEventProbe {

    public final List<Long> started = new CopyOnWriteArrayList<>();
    public final List<Long> cancelled = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStarted(SessionStartedEvent event) {
        started.add(event.sessionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelled(SessionCancelledEvent event) {
        cancelled.add(event.sessionId());
    }

    public void clear() {
        started.clear();
        cancelled.clear();
    }
}
