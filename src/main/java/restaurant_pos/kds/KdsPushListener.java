package restaurant_pos.kds;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import restaurant_pos.order.ItemCancelledEvent;
import restaurant_pos.order.ItemCompletedEvent;
import restaurant_pos.order.ItemModifiedEvent;
import restaurant_pos.order.ItemStartedEvent;
import restaurant_pos.order.KitchenQueryService;
import restaurant_pos.order.KitchenQueryService.StationItem;
import restaurant_pos.order.OrderSubmittedEvent;
import restaurant_pos.shared.model.Station;
import restaurant_pos.shared.push.Pusher;
import restaurant_pos.table.SessionCancelledEvent;

/**
 * 推播監聽器（§4.7，S6 卡 6.4）：交易<b>提交後</b>執行，重新讀取資料，例外只記日誌、不影響 API 結果。
 *
 * <pre>
 * 訂單送出             → 每個品項新增或更新（送到該品項的站別）
 * 品項修改、開始製作   → 新增或更新；讀不到（已不在清單上）就改送移除
 * 品項完成、取消       → 移除
 * 用餐取消（含結束不收費）→ 兩站整板重新載入
 * 結帳完成、結帳作廢   → 不推播
 * </pre>
 */
@Component
public class KdsPushListener {

    private static final Logger log = LoggerFactory.getLogger(KdsPushListener.class);

    private final KitchenQueryService kitchen;
    private final Pusher pusher;

    public KdsPushListener(KitchenQueryService kitchen, Pusher pusher) {
        this.kitchen = kitchen;
        this.pusher = pusher;
    }

    static String topic(Station station) {
        return "/topic/kds/" + station.name();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(OrderSubmittedEvent event) {
        safely("訂單送出", () -> kitchen.findOnBoardByOrder(event.orderId()).forEach(this::upsert));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModified(ItemModifiedEvent event) {
        safely("品項修改", () -> upsertOrRemove(event.orderItemId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStarted(ItemStartedEvent event) {
        safely("品項開始製作", () -> upsertOrRemove(event.orderItemId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(ItemCompletedEvent event) {
        safely("品項完成", () -> removeEverywhere(event.orderItemId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelled(ItemCancelledEvent event) {
        safely("品項取消", () -> removeEverywhere(event.orderItemId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionCancelled(SessionCancelledEvent event) {
        safely("用餐取消", () -> {
            for (Station station : Station.values()) {
                pusher.push(topic(station), KdsMessage.BoardReload.create());
            }
        });
    }

    private void upsert(StationItem found) {
        pusher.push(topic(found.station()), KdsMessage.ItemUpsert.of(found.item()));
    }

    private void upsertOrRemove(long orderItemId) {
        Optional<StationItem> item = kitchen.findOnBoard(orderItemId);
        if (item.isPresent()) {
            upsert(item.get());
        } else {
            removeEverywhere(orderItemId); // 讀不到就改送移除
        }
    }

    /** 移除訊息只帶編號、不帶站別，所以兩站都送；「移除一律套用」，不屬於該站的編號用戶端會忽略。 */
    private void removeEverywhere(long orderItemId) {
        for (Station station : Station.values()) {
            pusher.push(topic(station), KdsMessage.ItemRemove.of(orderItemId));
        }
    }


    /** 推播失敗只記日誌（不含訊息內容）。 */
    private void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("推播失敗（{}）：{}", what, e.getClass().getSimpleName());
        }
    }
}
