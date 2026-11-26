package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 卡 5.4：送出草稿（01-PLAN S5、§4.6 冪等與併發保證）。 */
class SubmitTest extends OrderTestBase {

    // ---- 成功 ----

    @Test
    void 送出草稿_轉為正式單_回批次號與品項數() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 2, noodleId, 1);
        addDraft(s, sideId, 1);
        MvcResult r = submit(s, "req-1", "不要香菜");
        assertThat(status(r)).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("batchNo").asInt()).isEqualTo(1);
        assertThat(b.get("itemCount").asInt()).isEqualTo(2);
        assertThat(b.get("replayed").asBoolean()).isFalse();

        Map<String, Object> order = jdbc.queryForMap("SELECT status, batch_no, draft_session_id, request_id, source, note, version "
                + "FROM orders WHERE id = ?", b.get("orderId").asLong());
        assertThat(order).containsEntry("status", "SUBMITTED").containsEntry("batch_no", 1)
                .containsEntry("draft_session_id", null).containsEntry("request_id", "req-1")
                .containsEntry("source", "STAFF").containsEntry("note", "不要香菜");
        assertThat(jdbc.queryForList("SELECT status FROM order_item WHERE session_id = ?", String.class, s))
                .containsOnly("PENDING");
        assertThat(jdbc.queryForObject("SELECT submitted_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, b.get("orderId").asLong())).isTrue();
    }

    @Test
    void 送出後寫歷史並發佈訂單送出事件() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        long orderId = body(submit(s, "req-1", null)).get("orderId").asLong();
        Map<String, Object> history = jdbc.queryForMap("SELECT action, order_id, order_item_id, after_summary FROM order_history");
        assertThat(history).containsEntry("action", "SUBMITTED").containsEntry("order_id", orderId);
        assertThat(history.get("after_summary").toString()).contains("批次 1").contains("牛肉麵");
        assertThat(orderEvents.events).containsExactly("SUBMITTED:" + orderId + ":1");
    }

    @Test
    void 送出後草稿消失_再加品項會建立新草稿() throws Exception {
        long s = openSession();
        long draftId = body(addDraft(s, beefId, 1)).get("orderId").asLong();
        submit(s, "req-1", null);
        assertThat(detail(s).get("draft").isNull()).isTrue();
        long newDraft = body(addDraft(s, sideId, 1)).get("orderId").asLong();
        assertThat(newDraft).isNotEqualTo(draftId);
        MvcResult second = submit(s, "req-2", null);
        assertThat(body(second).get("batchNo").asInt()).isEqualTo(2);
    }

    @Test
    void 批次號是該用餐紀錄最大批次加1_已取消的批次仍佔號() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        long first = body(submit(s, "r1", null)).get("orderId").asLong();
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", first);
        addDraft(s, beefId, 1);
        assertThat(body(submit(s, "r2", null)).get("batchNo").asInt()).isEqualTo(2);
        long other = openSession();
        addDraft(other, beefId, 1);
        assertThat(body(submit(other, "r1", null)).get("batchNo").asInt()).isEqualTo(1); // 別桌從 1 開始
    }

    @Test
    void 送出時價格重新以最新菜單為準() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1, noodleId, 1);
        jdbc.update("UPDATE menu_item SET price = 180 WHERE id = ?", beefId);
        jdbc.update("UPDATE modifier SET price_delta = 30 WHERE id = ?", noodleId);
        submit(s, "r1", null);
        JsonNode item = detail(s).get("orders").get(0).get("items").get(0);
        assertThat(item.get("basePrice").asLong()).isEqualTo(180);
        assertThat(item.get("unitPrice").asLong()).isEqualTo(210);
    }

    @Test
    void 送出後菜單再改價不影響既有訂單() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        submit(s, "r1", null);
        jdbc.update("UPDATE menu_item SET price = 999, name = '改名了' WHERE id = ?", beefId);
        JsonNode item = detail(s).get("orders").get(0).get("items").get(0);
        assertThat(item.get("name").asText()).isEqualTo("牛肉麵");
        assertThat(item.get("unitPrice").asLong()).isEqualTo(150);
    }

    // ---- 請求編號 ----

    @Test
    void 請求編號格式() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        for (String bad : new String[] {"", "a b", "中文", "x".repeat(65), "a/b", "a.b"}) {
            MvcResult r = submit(s, bad, null);
            assertThat(status(r)).as(bad).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pos/sessions/" + s + "/draft/submit"), "{}"))).isEqualTo(400);
        assertThat(status(submit(s, "A_b-9".repeat(12) + "abcd", null))).isEqualTo(201); // 64 字
    }

    @Test
    void 備註超過255字回VALIDATION_ERROR() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        assertThat(errorCode(submit(s, "r1", "x".repeat(256)))).isEqualTo("VALIDATION_ERROR");
        assertThat(detail(s).get("draft").get("items")).hasSize(1);
    }

    // ---- 冪等重播 ----

    @Test
    void 同請求編號重播回原結果_不論內容() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 2);
        JsonNode first = body(submit(s, "req-1", "第一次的備註"));
        addDraft(s, sideId, 5); // 新草稿，內容不同
        MvcResult again = submit(s, "req-1", "完全不同的備註");
        assertThat(status(again)).isEqualTo(200);
        JsonNode b = body(again);
        assertThat(b.get("replayed").asBoolean()).isTrue();
        assertThat(b.get("orderId").asLong()).isEqualTo(first.get("orderId").asLong());
        assertThat(b.get("batchNo").asInt()).isEqualTo(1);
        assertThat(b.get("itemCount").asInt()).isEqualTo(1);
        assertThat(orderCount(s)).isEqualTo(2); // 一張正式單＋仍在的新草稿
        assertThat(orderEvents.events).hasSize(1);
        assertThat(detail(s).get("draft").get("items")).hasSize(1); // 重播不吃掉新草稿
    }

    @Test
    void 冪等檢查在結帳旗標與用餐狀態之前() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        submit(s, "req-1", null);
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", s);
        MvcResult replay = submit(s, "req-1", null);
        assertThat(status(replay)).isEqualTo(200);
        assertThat(body(replay).get("replayed").asBoolean()).isTrue();
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, status = 'CLOSED' WHERE id = ?", s);
        assertThat(status(submit(s, "req-1", null))).isEqualTo(200);
        assertThat(errorCode(submit(s, "req-2", null))).isEqualTo("SESSION_NOT_OPEN");
    }

    @Test
    void 請求編號只在同一用餐紀錄內唯一() throws Exception {
        long a = openSession();
        long b = openSession();
        addDraft(a, beefId, 1);
        addDraft(b, beefId, 1);
        assertThat(status(submit(a, "same", null))).isEqualTo(201);
        assertThat(status(submit(b, "same", null))).isEqualTo(201);
    }

    // ---- 拒絕 ----

    @Test
    void 結帳中與用餐狀態限制() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", s);
        assertThat(errorCode(submit(s, "r1", null))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, pending_recheckout = TRUE WHERE id = ?", s);
        assertThat(errorCode(submit(s, "r1", null))).isEqualTo("SESSION_PENDING_RECHECKOUT");
        jdbc.update("UPDATE table_session SET pending_recheckout = FALSE, status = 'CANCELLED' WHERE id = ?", s);
        assertThat(errorCode(submit(s, "r1", null))).isEqualTo("SESSION_NOT_OPEN");
        assertThat(errorCode(submit(999999, "r1", null))).isEqualTo("SESSION_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE batch_no IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void 沒有草稿或空草稿回EMPTY_DRAFT() throws Exception {
        long s = openSession();
        MvcResult none = submit(s, "r1", null);
        assertThat(status(none)).isEqualTo(409);
        assertThat(errorCode(none)).isEqualTo("EMPTY_DRAFT");
        long itemId = body(addDraft(s, beefId, 1)).get("items").get(0).get("id").asLong();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/pos/sessions/" + s + "/draft/items/" + itemId));
        assertThat(errorCode(submit(s, "r2", null))).isEqualTo("EMPTY_DRAFT");
    }

    @Test
    void 任一品項不可售整張失敗_草稿保持不變() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        addDraft(s, sideId, 1);
        jdbc.update("UPDATE menu_item SET active = FALSE WHERE id = ?", sideId);
        MvcResult r = submit(s, "r1", null);
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("MENU_ITEM_NOT_SELLABLE");
        assertThat(detail(s).get("draft").get("items")).hasSize(2);
        assertThat(detail(s).get("orders")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_history", Integer.class)).isZero();
        assertThat(orderEvents.events).isEmpty();
        // 之後恢復可售就能出單，批次號仍是 1
        jdbc.update("UPDATE menu_item SET active = TRUE WHERE id = ?", sideId);
        assertThat(body(submit(s, "r1", null)).get("batchNo").asInt()).isEqualTo(1);
    }

    @Test
    void 品項被刪除或選項規則改變_送出時重新檢查() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1, noodleId, 1);
        jdbc.update("UPDATE modifier SET deleted_at = NOW(6) WHERE id = ?", noodleId);
        assertThat(errorCode(submit(s, "r1", null))).isEqualTo("INVALID_MODIFIER_SELECTION");
        jdbc.update("UPDATE modifier SET deleted_at = NULL WHERE id = ?", noodleId);
        jdbc.update("UPDATE modifier_group SET min_select = 2, max_select = 2 WHERE id = ?", toppingGroupId);
        assertThat(errorCode(submit(s, "r1", null))).isEqualTo("MODIFIER_GROUP_SELECTION_OUT_OF_RANGE");
        jdbc.update("UPDATE menu_item SET deleted_at = NOW(6) WHERE id = ?", beefId);
        assertThat(errorCode(submit(s, "r1", null))).isEqualTo("MENU_ITEM_NOT_FOUND");
        assertThat(detail(s).get("draft").get("items")).hasSize(1);
    }

    // ---- 併發保證 ----

    @Test
    void 同時出單只有一個成功且批次號不重複() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String requestId = "req-" + i;
            results.add(pool.submit(() -> {
                start.await();
                return submit(s, requestId, null);
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            if (status(r) == 201) {
                created++;
            } else {
                assertThat(status(r)).isEqualTo(409);
                assertThat(errorCode(r)).isEqualTo("EMPTY_DRAFT");
            }
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE session_id = ? AND batch_no IS NOT NULL", Integer.class, s)).isEqualTo(1);
        assertThat(orderEvents.events).hasSize(1);
    }

    @Test
    void 同請求編號同時送出_只建立一張單_其餘重播() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return submit(s, "same-request", null);
            }));
        }
        start.countDown();
        Set<Long> orderIds = new HashSet<>();
        int created = 0;
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            assertThat(status(r)).as(r.getResponse().getContentAsString()).isIn(200, 201);
            if (status(r) == 201) {
                created++;
            }
            orderIds.add(body(r).get("orderId").asLong());
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(orderIds).hasSize(1);
    }

    @Test
    void 多桌同時出單互不影響() throws Exception {
        int tables = 4;
        List<Long> sessions = new ArrayList<>();
        for (int i = 0; i < tables; i++) {
            long s = openSession();
            addDraft(s, beefId, 1);
            sessions.add(s);
        }
        ExecutorService pool = Executors.newFixedThreadPool(tables);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (long s : sessions) {
            results.add(pool.submit(() -> {
                start.await();
                return submit(s, "r1", null);
            }));
        }
        start.countDown();
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            assertThat(status(r)).isEqualTo(201);
            assertThat(body(r).get("batchNo").asInt()).isEqualTo(1);
        }
        pool.shutdown();
    }
}
