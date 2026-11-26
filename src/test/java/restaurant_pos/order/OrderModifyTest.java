package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 卡 5.5：修改已送出品項、修改訂單備註（01-PLAN S5）。 */
class OrderModifyTest extends OrderTestBase {

    private long session;
    private JsonNode order;
    private long beefItem;
    private long sideItem;

    @BeforeEach
    void submitOne() throws Exception {
        session = openSession();
        order = submitStandardOrder(session, "req-1"); // 牛肉麵×2（加麵）＋小菜×1
        beefItem = itemIdByName(order, "牛肉麵");
        sideItem = itemIdByName(order, "小菜");
        orderEvents.clear();
    }

    private int versionOf(long itemId) throws Exception {
        return itemOf(session, itemId).get("version").asInt();
    }

    // ---- 成功 ----

    @Test
    void 修改數量_選項_備註_版本加1() throws Exception {
        MvcResult r = modify(beefItem, 0, 3, "少辣", eggId, 1);
        assertThat(status(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("quantity").asInt()).isEqualTo(3);
        assertThat(b.get("note").asText()).isEqualTo("少辣");
        assertThat(b.get("version").asInt()).isEqualTo(1);
        assertThat(b.get("modifiers")).hasSize(1);
        assertThat(b.get("modifiers").get(0).get("name").asText()).isEqualTo("加蛋");
        assertThat(b.get("unitPrice").asLong()).isEqualTo(165); // 150 ＋ 15
        assertThat(b.get("lineTotal").asLong()).isEqualTo(495);
        assertThat(b.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void 以目前菜單重建快照_改價後採用新價格() throws Exception {
        jdbc.update("UPDATE menu_item SET price = 200, name = '紅燒牛肉麵' WHERE id = ?", beefId);
        jdbc.update("UPDATE modifier SET price_delta = 30 WHERE id = ?", noodleId);
        JsonNode b = body(modify(beefItem, 0, 2, "", noodleId, 1));
        assertThat(b.get("name").asText()).isEqualTo("紅燒牛肉麵");
        assertThat(b.get("basePrice").asLong()).isEqualTo(200);
        assertThat(b.get("unitPrice").asLong()).isEqualTo(230);
        // 其他品項的快照不受影響
        assertThat(itemOf(session, sideItem).get("unitPrice").asLong()).isEqualTo(60);
    }

    @Test
    void 製作中的品項修改後保持製作中() throws Exception {
        setItemStatus(beefItem, "IN_PROGRESS");
        JsonNode b = body(modify(beefItem, versionOf(beefItem), 1, "", noodleId, 1));
        assertThat(b.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(b.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void 修改寫前後摘要歷史_發佈品項修改事件() throws Exception {
        modify(beefItem, 0, 3, "少辣", eggId, 1);
        Map<String, Object> history = jdbc.queryForMap("SELECT action, order_item_id, before_summary, after_summary "
                + "FROM order_history WHERE action = 'ITEM_MODIFIED'");
        assertThat(history).containsEntry("order_item_id", beefItem);
        assertThat(history.get("before_summary").toString()).contains("牛肉麵 x2").contains("加麵x1");
        assertThat(history.get("after_summary").toString()).contains("牛肉麵 x3").contains("加蛋x1").contains("備註:少辣");
        assertThat(orderEvents.events).containsExactly("MODIFIED:" + beefItem);
    }

    @Test
    void 修改後訂單版本加1() throws Exception {
        int before = order.get("version").asInt();
        modify(beefItem, 0, 1, "");
        JsonNode after = detail(session).get("orders").get(0);
        assertThat(after.get("version").asInt()).isEqualTo(before + 1);
        assertThat(after.get("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void 連續修改需帶最新版本() throws Exception {
        assertThat(status(modify(beefItem, 0, 1, ""))).isEqualTo(200);
        assertThat(status(modify(beefItem, 1, 2, ""))).isEqualTo(200);
        assertThat(versionOf(beefItem)).isEqualTo(2);
    }

    @Test
    void 下架或售完的品項仍可修改_只要求未刪除() throws Exception {
        jdbc.update("UPDATE menu_item SET active = FALSE, sold_out_date = '2026-03-02' WHERE id = ?", beefId);
        assertThat(status(modify(beefItem, 0, 1, ""))).isEqualTo(200);
    }

    // ---- 拒絕順序 ----

    @Test
    void 版本不符回CONCURRENT_MODIFICATION() throws Exception {
        MvcResult r = modify(beefItem, 5, 1, "");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(versionOf(beefItem)).isZero();
    }

    @Test
    void 拒絕依序_用餐狀態_待重新結帳_結帳中_已取消_已完成_版本() throws Exception {
        // 全部不符時先回最前面的：用餐紀錄不在使用中
        setItemStatus(sideItem, "CANCELLED");
        jdbc.update("UPDATE table_session SET status = 'CLOSED', checkout_in_progress = TRUE, pending_recheckout = TRUE WHERE id = ?", session);
        assertThat(errorCode(modify(sideItem, 99, 1, ""))).isEqualTo("SESSION_NOT_OPEN");
        jdbc.update("UPDATE table_session SET status = 'OPEN' WHERE id = ?", session);
        assertThat(errorCode(modify(sideItem, 99, 1, ""))).isEqualTo("SESSION_PENDING_RECHECKOUT");
        jdbc.update("UPDATE table_session SET pending_recheckout = FALSE WHERE id = ?", session);
        assertThat(errorCode(modify(sideItem, 99, 1, ""))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE WHERE id = ?", session);
        assertThat(errorCode(modify(sideItem, 99, 1, ""))).isEqualTo("ORDER_ITEM_CANCELLED");
        setItemStatus(sideItem, "DONE");
        assertThat(errorCode(modify(sideItem, 99, 1, ""))).isEqualTo("ORDER_ITEM_ALREADY_READY");
        setItemStatus(sideItem, "PENDING");
        assertThat(errorCode(modify(sideItem, 99, 1, ""))).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void 草稿品項不可用這個API修改() throws Exception {
        addDraft(session, sideId, 1);
        long draftItem = detail(session).get("draft").get("items").get(0).get("id").asLong();
        MvcResult r = modify(draftItem, 0, 1, "");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("ORDER_NOT_EDITABLE");
    }

    @Test
    void 已取消與已完成的品項不可修改() throws Exception {
        setItemStatus(beefItem, "DONE");
        assertThat(errorCode(modify(beefItem, versionOf(beefItem), 1, ""))).isEqualTo("ORDER_ITEM_ALREADY_READY");
        setItemStatus(beefItem, "CANCELLED");
        assertThat(errorCode(modify(beefItem, versionOf(beefItem), 1, ""))).isEqualTo("ORDER_ITEM_CANCELLED");
    }

    // ---- 輸入與菜單 ----

    @Test
    void 數量與選項規則同草稿() throws Exception {
        assertThat(errorCode(modify(beefItem, 0, 0, ""))).isEqualTo("INVALID_QUANTITY");
        assertThat(errorCode(modify(beefItem, 0, 100, ""))).isEqualTo("INVALID_QUANTITY");
        assertThat(errorCode(modify(beefItem, 0, 1, "", 999999, 1))).isEqualTo("INVALID_MODIFIER_SELECTION");
        assertThat(errorCode(modify(beefItem, 0, 1, "", noodleId, 2))).isEqualTo("MODIFIER_DUPLICATE_NOT_ALLOWED");
        assertThat(errorCode(modify(beefItem, 0, 1, "", noodleId, 1, eggId, 1, hotId, 1))).isEqualTo("INVALID_MODIFIER_SELECTION");
        assertThat(versionOf(beefItem)).isZero();
    }

    @Test
    void 格式錯誤() throws Exception {
        assertThat(errorCode(modify(beefItem, null, 1, ""))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(modify(beefItem, 0, null, ""))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(modify(beefItem, 0, 1, "x".repeat(256)))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(patch("/api/pos/order-items/" + beefItem), "{\"expectedVersion\":0,\"quantity\":1.5}"))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 品項不存在或菜單品項已刪除() throws Exception {
        MvcResult nf = modify(999999, 0, 1, "");
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("ORDER_ITEM_NOT_FOUND");
        jdbc.update("UPDATE menu_item SET deleted_at = NOW(6) WHERE id = ?", beefId);
        MvcResult deleted = modify(beefItem, 0, 1, "");
        assertThat(status(deleted)).isEqualTo(404);
        assertThat(errorCode(deleted)).isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    @Test
    void 失敗時整筆不變() throws Exception {
        modify(beefItem, 0, 99, "", noodleId, 2);
        JsonNode item = itemOf(session, beefItem);
        assertThat(item.get("quantity").asInt()).isEqualTo(2);
        assertThat(item.get("version").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_history WHERE action = 'ITEM_MODIFIED'", Integer.class)).isZero();
        assertThat(orderEvents.events).isEmpty();
    }

    // ---- 訂單備註 ----

    private MvcResult note(long orderId, Object version, String note) throws Exception {
        String body = version == null ? "{\"note\":\"" + note + "\"}" : "{\"expectedVersion\":" + version + ",\"note\":\"" + note + "\"}";
        return call(patch("/api/pos/orders/" + orderId + "/note"), body);
    }

    @Test
    void 修改訂單備註_只寫歷史_不發事件() throws Exception {
        long orderId = order.get("id").asLong();
        int version = order.get("version").asInt();
        MvcResult r = note(orderId, version, "趕時間");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("note").asText()).isEqualTo("趕時間");
        assertThat(body(r).get("version").asInt()).isEqualTo(version + 1);
        assertThat(body(r).get("items")).hasSize(2);
        Map<String, Object> history = jdbc.queryForMap("SELECT before_summary, after_summary, order_item_id FROM order_history WHERE action = 'ORDER_NOTE_CHANGED'");
        assertThat(history).containsEntry("before_summary", "").containsEntry("after_summary", "趕時間").containsEntry("order_item_id", null);
        assertThat(orderEvents.events).isEmpty();
    }

    @Test
    void 訂單備註的版本與限制() throws Exception {
        long orderId = order.get("id").asLong();
        int version = order.get("version").asInt();
        assertThat(errorCode(note(orderId, version + 5, "x"))).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(errorCode(note(orderId, null, "x"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(note(orderId, version, "x".repeat(256)))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(note(999999, version, "x"))).isEqualTo("ORDER_NOT_FOUND");
        assertThat(status(note(orderId, version, ""))).isEqualTo(200); // 清空備註
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", session);
        assertThat(errorCode(note(orderId, version + 1, "x"))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, status = 'CLOSED' WHERE id = ?", session);
        assertThat(errorCode(note(orderId, version + 1, "x"))).isEqualTo("SESSION_NOT_OPEN");
    }

    @Test
    void 草稿沒有訂單備註這個API() throws Exception {
        addDraft(session, sideId, 1);
        long draftId = detail(session).get("draft").get("orderId").asLong();
        assertThat(errorCode(note(draftId, 0, "x"))).isEqualTo("ORDER_NOT_EDITABLE");
    }
}
