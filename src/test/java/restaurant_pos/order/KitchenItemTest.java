package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.model.Station;

/** 卡 5.8：廚房品項狀態服務——檢查六步依序、只發事件（01-PLAN S5）。 */
class KitchenItemTest extends OrderTestBase {

    @Autowired
    KitchenItemService kitchen;

    private long session;
    private long orderId;
    private long beefItem; // K1
    private long sideItem; // K2

    @BeforeEach
    void submitOne() throws Exception {
        session = openSession();
        JsonNode order = submitStandardOrder(session, "req-1");
        orderId = order.get("id").asLong();
        beefItem = itemIdByName(order, "牛肉麵");
        sideItem = itemIdByName(order, "小菜");
        orderEvents.clear();
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(expected);
    }

    private String orderStatus() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    // ---- 成功 ----

    @Test
    void 開始製作_待處理變製作中_版本加1_訂單變製作中() {
        KitchenItemService.Result r = kitchen.start(Station.K1, beefItem, 0);
        assertThat(r.status()).isEqualTo(ItemStatus.IN_PROGRESS);
        assertThat(r.version()).isEqualTo(1);
        assertThat(orderStatus()).isEqualTo("IN_PROGRESS");
        assertThat(orderEvents.events).containsExactly("STARTED:" + beefItem);
    }

    @Test
    void 完成_製作中變已完成_發佈品項完成事件() {
        kitchen.start(Station.K1, beefItem, 0);
        KitchenItemService.Result r = kitchen.complete(Station.K1, beefItem, 1);
        assertThat(r.status()).isEqualTo(ItemStatus.DONE);
        assertThat(r.version()).isEqualTo(2);
        assertThat(orderStatus()).isEqualTo("PARTIALLY_SERVED");
        assertThat(orderEvents.events).containsExactly("STARTED:" + beefItem, "COMPLETED:" + beefItem);
    }

    @Test
    void 待處理可以直接完成() {
        KitchenItemService.Result r = kitchen.complete(Station.K2, sideItem, 0);
        assertThat(r.status()).isEqualTo(ItemStatus.DONE);
        assertThat(orderStatus()).isEqualTo("PARTIALLY_SERVED");
    }

    @Test
    void 全部完成後訂單為已全部上餐() {
        kitchen.complete(Station.K1, beefItem, 0);
        kitchen.complete(Station.K2, sideItem, 0);
        assertThat(orderStatus()).isEqualTo("SERVED");
    }

    @Test
    void 只發事件_不寫歷史() {
        kitchen.start(Station.K1, beefItem, 0);
        kitchen.complete(Station.K1, beefItem, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_history WHERE action <> 'SUBMITTED'", Integer.class)).isZero();
    }

    @Test
    void 訂單版本隨品項狀態變更遞增() throws Exception {
        int before = detail(session).get("orders").get(0).get("version").asInt();
        kitchen.start(Station.K1, beefItem, 0);
        assertThat(detail(session).get("orders").get(0).get("version").asInt()).isEqualTo(before + 1);
    }

    // ---- 檢查依序：品項存在 → 站別 → 用餐狀態 → 未取消 → 版本 → 轉換 ----

    @Test
    void 步驟一_品項不存在() {
        assertCode(() -> kitchen.start(Station.K1, 999999, 0), ErrorCode.KDS_ITEM_NOT_FOUND);
    }

    @Test
    void 草稿品項廚房看不到() throws Exception {
        addDraft(session, sideId, 1);
        long draftItem = detail(session).get("draft").get("items").get(0).get("id").asLong();
        assertCode(() -> kitchen.start(Station.K2, draftItem, 0), ErrorCode.KDS_ITEM_NOT_FOUND);
    }

    @Test
    void 步驟二_站別不符() {
        assertCode(() -> kitchen.start(Station.K2, beefItem, 0), ErrorCode.KDS_STATION_FORBIDDEN);
        assertCode(() -> kitchen.complete(Station.K1, sideItem, 0), ErrorCode.KDS_STATION_FORBIDDEN);
    }

    @Test
    void 步驟三_用餐紀錄已取消拒絕_使用中與已結束都可以() {
        jdbc.update("UPDATE table_session SET status = 'CLOSED' WHERE id = ?", session);
        assertThat(kitchen.start(Station.K1, beefItem, 0).status()).isEqualTo(ItemStatus.IN_PROGRESS); // 先付後吃
        jdbc.update("UPDATE table_session SET status = 'CANCELLED' WHERE id = ?", session);
        assertCode(() -> kitchen.complete(Station.K1, beefItem, 1), ErrorCode.SESSION_NOT_OPEN);
    }

    @Test
    void 不檢查結帳旗標() {
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", session);
        assertThat(kitchen.start(Station.K1, beefItem, 0).status()).isEqualTo(ItemStatus.IN_PROGRESS);
        jdbc.update("UPDATE table_session SET pending_recheckout = TRUE WHERE id = ?", session);
        assertThat(kitchen.complete(Station.K1, beefItem, 1).status()).isEqualTo(ItemStatus.DONE);
    }

    @Test
    void 步驟四_品項已取消() {
        setItemStatus(beefItem, "CANCELLED");
        assertCode(() -> kitchen.start(Station.K1, beefItem, 1), ErrorCode.ORDER_ITEM_CANCELLED);
        assertCode(() -> kitchen.complete(Station.K1, beefItem, 99), ErrorCode.ORDER_ITEM_CANCELLED); // 先於版本檢查
    }

    @Test
    void 步驟五_版本不符() {
        assertCode(() -> kitchen.start(Station.K1, beefItem, 5), ErrorCode.CONCURRENT_MODIFICATION);
        assertCode(() -> kitchen.start(Station.K1, beefItem, null), ErrorCode.VALIDATION_ERROR);
        assertThat(jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String.class, beefItem)).isEqualTo("PENDING");
    }

    @Test
    void 步驟六_轉換不合法() {
        kitchen.start(Station.K1, beefItem, 0);
        assertCode(() -> kitchen.start(Station.K1, beefItem, 1), ErrorCode.INVALID_ORDER_ITEM_TRANSITION); // 製作中不能再開始
        kitchen.complete(Station.K1, beefItem, 1);
        assertCode(() -> kitchen.start(Station.K1, beefItem, 2), ErrorCode.INVALID_ORDER_ITEM_TRANSITION); // 已完成是終點
        assertCode(() -> kitchen.complete(Station.K1, beefItem, 2), ErrorCode.INVALID_ORDER_ITEM_TRANSITION);
    }

    @Test
    void 檢查順序_站別優先於版本() {
        assertCode(() -> kitchen.start(Station.K2, beefItem, 99), ErrorCode.KDS_STATION_FORBIDDEN);
    }

    @Test
    void 失敗時不改任何資料也不發事件() {
        assertCode(() -> kitchen.start(Station.K1, beefItem, 7), ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(orderStatus()).isEqualTo("SUBMITTED");
        assertThat(orderEvents.events).isEmpty();
    }

    // ---- 併發 ----

    @Test
    void 兩個廚房同時以相同版本開始製作_只有一個成功() throws Exception {
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    kitchen.start(Station.K1, beefItem, 0);
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
                    return false;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM order_item WHERE id = ?", Integer.class, beefItem)).isEqualTo(1);
    }
}
