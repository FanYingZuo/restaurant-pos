package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 卡 5.6、5.7：取消品項、取消整單、取消開桌（PIN，01-PLAN S5）。 */
class OrderCancelTest extends OrderTestBase {

    private long session;
    private JsonNode order;
    private long orderId;
    private long beefItem;
    private long sideItem;

    @BeforeEach
    void submitOne() throws Exception {
        session = openSession();
        order = submitStandardOrder(session, "req-1");
        orderId = order.get("id").asLong();
        beefItem = itemIdByName(order, "牛肉麵");
        sideItem = itemIdByName(order, "小菜");
        orderEvents.clear();
    }

    private MvcResult cancelOrder(long id, String pin, String reason) throws Exception {
        return call(post("/api/pos/orders/" + id + "/cancel"), pinReason(pin, reason));
    }

    private MvcResult cancelSession(long id, String pin, String reason) throws Exception {
        return call(post("/api/pos/sessions/" + id + "/cancel"), pinReason(pin, reason));
    }

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String.class, itemId);
    }

    // ---- 5.6 取消品項 ----

    @Test
    void 取消品項_記理由與操作員工_版本加1() throws Exception {
        MvcResult r = cancelItem(sideItem, PIN, "客人不要了");
        assertThat(status(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(b.get("cancelReason").asText()).isEqualTo("客人不要了");
        assertThat(b.get("cancelledAt").asText()).isEqualTo("2026-03-02T00:00:00Z");
        assertThat(b.get("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cancelled_by_employee FROM order_item WHERE id = ?", Long.class, sideItem)).isEqualTo(employeeId);
    }

    @Test
    void 取消品項_寫歷史_發佈品項取消事件_重算訂單狀態() throws Exception {
        cancelItem(sideItem, PIN, "點錯");
        Map<String, Object> h = jdbc.queryForMap("SELECT order_item_id, employee_id, reason, before_summary FROM order_history WHERE action = 'ITEM_CANCELLED'");
        assertThat(h).containsEntry("order_item_id", sideItem).containsEntry("employee_id", employeeId).containsEntry("reason", "點錯");
        assertThat(h.get("before_summary").toString()).contains("小菜");
        assertThat(orderEvents.events).containsExactly("CANCELLED:" + sideItem);
        // 還有未取消品項，訂單維持已送出
        assertThat(detail(session).get("orders").get(0).get("status").asText()).isEqualTo("SUBMITTED");
        cancelItem(beefItem, PIN, "全部不要");
        assertThat(detail(session).get("orders").get(0).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void 取消品項後小計不含該品項() throws Exception {
        assertThat(detail(session).get("subtotal").asLong()).isEqualTo(400); // 170×2 ＋ 60
        cancelItem(sideItem, PIN, "x");
        assertThat(detail(session).get("subtotal").asLong()).isEqualTo(340);
    }

    @Test
    void 已完成不可取消() throws Exception {
        setItemStatus(beefItem, "DONE");
        MvcResult r = cancelItem(beefItem, PIN, "x");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("ORDER_ITEM_ALREADY_READY");
        assertThat(itemStatus(beefItem)).isEqualTo("DONE");
    }

    @Test
    void 製作中可以取消() throws Exception {
        setItemStatus(beefItem, "IN_PROGRESS");
        assertThat(status(cancelItem(beefItem, PIN, "x"))).isEqualTo(200);
    }

    @Test
    void 已取消的品項不可再取消() throws Exception {
        cancelItem(sideItem, PIN, "x");
        assertThat(errorCode(cancelItem(sideItem, PIN, "y"))).isEqualTo("ORDER_ITEM_CANCELLED");
    }

    @Test
    void 狀態檢查之後才驗PIN_避免用錯誤PIN探測資料狀態() throws Exception {
        setItemStatus(beefItem, "DONE");
        setItemStatus(sideItem, "CANCELLED");
        assertThat(errorCode(cancelItem(beefItem, "0000", "x"))).isEqualTo("ORDER_ITEM_ALREADY_READY");
        assertThat(errorCode(cancelItem(sideItem, "0000", "x"))).isEqualTo("ORDER_ITEM_CANCELLED");
        addDraft(session, sideId, 1);
        long draftItem = detail(session).get("draft").get("items").get(0).get("id").asLong();
        assertThat(errorCode(cancelItem(draftItem, "0000", "x"))).isEqualTo("ORDER_NOT_EDITABLE");
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", session);
        assertThat(errorCode(cancelItem(beefItem, "0000", "x"))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, status = 'CLOSED' WHERE id = ?", session);
        assertThat(errorCode(cancelItem(beefItem, "0000", "x"))).isEqualTo("SESSION_NOT_OPEN");
        assertThat(errorCode(cancelItem(999999, "0000", "x"))).isEqualTo("ORDER_ITEM_NOT_FOUND");
    }

    @Test
    void 狀態正常時錯誤PIN回INVALID_PIN_資料不變() throws Exception {
        MvcResult r = cancelItem(sideItem, "0000", "x");
        assertThat(status(r)).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("INVALID_PIN");
        assertThat(itemStatus(sideItem)).isEqualTo("PENDING");
        assertThat(orderEvents.events).isEmpty();
    }

    @Test
    void PIN被鎖定回PIN_LOCKED() throws Exception {
        for (int i = 0; i < 5; i++) {
            cancelItem(sideItem, "0000", "x");
        }
        MvcResult r = cancelItem(sideItem, PIN, "x");
        assertThat(status(r)).isEqualTo(429);
        assertThat(errorCode(r)).isEqualTo("PIN_LOCKED");
    }

    @Test
    void 理由必填且先格式後業務() throws Exception {
        for (String reason : new String[] {null, "", "   ", "x".repeat(256)}) {
            MvcResult r = cancelItem(sideItem, PIN, reason);
            assertThat(status(r)).as(String.valueOf(reason)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
        for (String pin : new String[] {null, "12", "abcd", "1234567"}) {
            assertThat(errorCode(cancelItem(sideItem, pin, "x"))).isEqualTo("VALIDATION_ERROR");
        }
        // 格式錯誤先於狀態檢查
        setItemStatus(sideItem, "DONE");
        assertThat(errorCode(cancelItem(sideItem, "12", "x"))).isEqualTo("VALIDATION_ERROR");
        assertThat(itemStatus(sideItem)).isEqualTo("DONE");
    }

    // ---- 5.6 取消整單 ----

    @Test
    void 取消整單_逐項取消並各自寫歷史發事件() throws Exception {
        MvcResult r = cancelOrder(orderId, PIN, "客人離開");
        assertThat(status(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("status").asText()).isEqualTo("CANCELLED");
        for (JsonNode i : b.get("items")) {
            assertThat(i.get("status").asText()).isEqualTo("CANCELLED");
            assertThat(i.get("cancelReason").asText()).isEqualTo("客人離開");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_history WHERE action = 'ITEM_CANCELLED'", Integer.class)).isEqualTo(2);
        assertThat(orderEvents.events).containsExactlyInAnyOrder("CANCELLED:" + beefItem, "CANCELLED:" + sideItem);
        assertThat(detail(session).get("subtotal").asLong()).isZero();
    }

    @Test
    void 取消整單_已取消的品項不重複處理() throws Exception {
        cancelItem(sideItem, PIN, "先取消小菜");
        orderEvents.clear();
        cancelOrder(orderId, PIN, "再取消整單");
        assertThat(orderEvents.events).containsExactly("CANCELLED:" + beefItem);
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM order_item WHERE id = ?", String.class, sideItem)).isEqualTo("先取消小菜");
    }

    @Test
    void 有任何已完成品項不可取消整單() throws Exception {
        setItemStatus(sideItem, "DONE");
        MvcResult r = cancelOrder(orderId, PIN, "x");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("ORDER_ITEM_ALREADY_READY");
        assertThat(itemStatus(beefItem)).isEqualTo("PENDING"); // 沒有部分取消
        assertThat(orderEvents.events).isEmpty();
    }

    @Test
    void 取消整單_狀態先於PIN() throws Exception {
        setItemStatus(sideItem, "DONE");
        assertThat(errorCode(cancelOrder(orderId, "0000", "x"))).isEqualTo("ORDER_ITEM_ALREADY_READY");
        setItemStatus(sideItem, "PENDING");
        assertThat(errorCode(cancelOrder(orderId, "0000", "x"))).isEqualTo("INVALID_PIN");
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", session);
        assertThat(errorCode(cancelOrder(orderId, "0000", "x"))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, status = 'CLOSED' WHERE id = ?", session);
        assertThat(errorCode(cancelOrder(orderId, "0000", "x"))).isEqualTo("SESSION_NOT_OPEN");
    }

    @Test
    void 取消整單的其他錯誤() throws Exception {
        assertThat(errorCode(cancelOrder(999999, PIN, "x"))).isEqualTo("ORDER_NOT_FOUND");
        addDraft(session, sideId, 1);
        long draftId = detail(session).get("draft").get("orderId").asLong();
        assertThat(errorCode(cancelOrder(draftId, PIN, "x"))).isEqualTo("ORDER_NOT_EDITABLE");
        assertThat(errorCode(cancelOrder(orderId, PIN, ""))).isEqualTo("VALIDATION_ERROR");
    }

    // ---- 5.7 取消開桌 ----

    @Test
    void 有未取消的正式單品項不可取消開桌() throws Exception {
        MvcResult r = cancelSession(session, PIN, "x");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("SESSION_HAS_SUBMITTED_ORDERS");
        assertThat(sessionStatus(session)).isEqualTo("OPEN");
        // 狀態先於 PIN
        assertThat(errorCode(cancelSession(session, "0000", "x"))).isEqualTo("SESSION_HAS_SUBMITTED_ORDERS");
    }

    @Test
    void 只有草稿_取消開桌後草稿實體刪除_用餐已取消_桌位回空桌() throws Exception {
        long fresh = openSession();
        addDraft(fresh, beefId, 1, noodleId, 1);
        long tableId = jdbc.queryForObject("SELECT table_id FROM table_session WHERE id = ?", Long.class, fresh);
        MvcResult r = cancelSession(fresh, PIN, "客人沒來");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body(r).get("occupiesTable").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE session_id = ?", Integer.class, fresh)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE session_id = ?", Integer.class, fresh)).isZero();
        assertThat(jdbc.queryForObject("SELECT occupying_table_id FROM table_session WHERE id = ?", Object.class, fresh)).isNull();
        assertThat(tableEvents.cancelled).contains(fresh);
        assertThat(status(call(post("/api/pos/tables/" + tableId + "/open"), "{\"guestCount\":2}"))).isEqualTo(201); // 桌位可再開
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_employee_id, reason FROM audit_log WHERE action = 'SESSION_CANCELLED'");
        assertThat(audit).containsEntry("actor_employee_id", employeeId).containsEntry("reason", "客人沒來");
    }

    @Test
    void 正式單品項全部取消後可以取消開桌() throws Exception {
        cancelOrder(orderId, PIN, "全部取消");
        MvcResult r = cancelSession(session, PIN, "不用餐了");
        assertThat(status(r)).isEqualTo(200);
        assertThat(sessionStatus(session)).isEqualTo("CANCELLED");
        // 已取消的正式單仍保留
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE session_id = ? AND batch_no IS NOT NULL", Integer.class, session)).isEqualTo(1);
    }

    @Test
    void 空的用餐紀錄可以取消() throws Exception {
        long fresh = openSession();
        assertThat(status(cancelSession(fresh, PIN, "x"))).isEqualTo(200);
    }

    @Test
    void 取消開桌的錯誤() throws Exception {
        long fresh = openSession();
        assertThat(errorCode(cancelSession(999999, PIN, "x"))).isEqualTo("SESSION_NOT_FOUND");
        assertThat(errorCode(cancelSession(fresh, "0000", "x"))).isEqualTo("INVALID_PIN");
        assertThat(sessionStatus(fresh)).isEqualTo("OPEN");
        assertThat(errorCode(cancelSession(fresh, PIN, ""))).isEqualTo("VALIDATION_ERROR");
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", fresh);
        assertThat(errorCode(cancelSession(fresh, PIN, "x"))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, pending_recheckout = TRUE WHERE id = ?", fresh);
        assertThat(errorCode(cancelSession(fresh, PIN, "x"))).isEqualTo("SESSION_PENDING_RECHECKOUT");
        jdbc.update("UPDATE table_session SET pending_recheckout = FALSE, status = 'CLOSED' WHERE id = ?", fresh);
        assertThat(errorCode(cancelSession(fresh, PIN, "x"))).isEqualTo("SESSION_NOT_OPEN");
        assertThat(List.of(1)).hasSize(1);
    }

    @Test
    void 取消開桌時回滾不留下半套資料() throws Exception {
        long fresh = openSession();
        addDraft(fresh, beefId, 1);
        cancelSession(fresh, "0000", "x"); // PIN 錯誤
        assertThat(detail(fresh).get("draft").get("items")).hasSize(1);
        assertThat(tableEvents.cancelled).doesNotContain(fresh);
    }
}
