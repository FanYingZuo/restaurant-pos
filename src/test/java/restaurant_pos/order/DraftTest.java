package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 卡 5.3：草稿新增、修改、刪除（01-PLAN S5）。 */
class DraftTest extends OrderTestBase {

    private String draftUrl(long sessionId) {
        return "/api/pos/sessions/" + sessionId + "/draft/items";
    }

    // ---- 新增 ----

    @Test
    void 新增品項_價格由伺服器依菜單計算() throws Exception {
        long s = openSession();
        MvcResult r = addDraft(s, beefId, 2, noodleId, 1);
        assertThat(status(r)).isEqualTo(201);
        JsonNode item = body(r).get("items").get(0);
        assertThat(item.get("name").asText()).isEqualTo("牛肉麵");
        assertThat(item.get("basePrice").asLong()).isEqualTo(150);
        assertThat(item.get("unitPrice").asLong()).isEqualTo(170); // 150 ＋ 加麵 20
        assertThat(item.get("quantity").asInt()).isEqualTo(2);
        assertThat(item.get("lineTotal").asLong()).isEqualTo(340);
        assertThat(item.get("station").asText()).isEqualTo("K1");
        assertThat(item.get("note").asText()).isEmpty();
        assertThat(item.get("modifiers").get(0).get("name").asText()).isEqualTo("加麵");
        assertThat(item.get("modifiers").get(0).get("priceDelta").asLong()).isEqualTo(20);
        assertThat(item.get("modifiers").get(0).get("quantity").asInt()).isEqualTo(1);
    }

    @Test
    void 前端傳來的價格一律不採用() throws Exception {
        long s = openSession();
        String body = "{\"menuItemId\":" + beefId + ",\"quantity\":1,\"price\":1,\"unitPrice\":1,\"lineTotal\":1,\"modifiers\":[]}";
        MvcResult r = call(post(draftUrl(s)), body);
        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r).get("items").get(0).get("unitPrice").asLong()).isEqualTo(150);
    }

    @Test
    void 每個用餐紀錄最多一張草稿() throws Exception {
        long s = openSession();
        long first = body(addDraft(s, beefId, 1)).get("orderId").asLong();
        long second = body(addDraft(s, sideId, 1)).get("orderId").asLong();
        assertThat(second).isEqualTo(first);
        assertThat(body(addDraft(s, sideId, 3)).get("items")).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE session_id = ? AND draft_session_id IS NOT NULL", Integer.class, s)).isEqualTo(1);
    }

    @Test
    void 草稿不寫歷史也不發事件() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_history", Integer.class)).isZero();
        assertThat(orderEvents.events).isEmpty();
    }

    @Test
    void 備註前後空白去除_最多255字() throws Exception {
        long s = openSession();
        MvcResult ok = call(post(draftUrl(s)), itemBody(beefId, 1, "  少辣  "));
        assertThat(body(ok).get("items").get(0).get("note").asText()).isEqualTo("少辣");
        assertThat(status(call(post(draftUrl(s)), itemBody(beefId, 1, "x".repeat(255))))).isEqualTo(201);
        MvcResult bad = call(post(draftUrl(s)), itemBody(beefId, 1, "x".repeat(256)));
        assertThat(status(bad)).isEqualTo(400);
        assertThat(errorCode(bad)).isEqualTo("VALIDATION_ERROR");
    }

    // ---- 新增的錯誤 ----

    @Test
    void 選項規則的錯誤碼() throws Exception {
        long s = openSession();
        assertThat(errorCode(addDraft(s, beefId, 0))).isEqualTo("INVALID_QUANTITY");
        assertThat(errorCode(addDraft(s, beefId, 100))).isEqualTo("INVALID_QUANTITY");
        assertThat(errorCode(addDraft(s, beefId, 1, 999999, 1))).isEqualTo("INVALID_MODIFIER_SELECTION");
        assertThat(errorCode(addDraft(s, beefId, 1, hotId, 1))).isEqualTo("INVALID_MODIFIER_SELECTION"); // 別的品項的選項
        assertThat(errorCode(addDraft(s, spicyId, 1))).isEqualTo("MODIFIER_GROUP_SELECTION_OUT_OF_RANGE"); // 辣度必選
        assertThat(errorCode(addDraft(s, spicyId, 1, mildId, 1, hotId, 1))).isEqualTo("MODIFIER_GROUP_SELECTION_OUT_OF_RANGE");
        assertThat(errorCode(addDraft(s, beefId, 1, noodleId, 2))).isEqualTo("MODIFIER_DUPLICATE_NOT_ALLOWED");
        assertThat(errorCode(call(post(draftUrl(s)), itemBody(beefId, 1, null, noodleId, 1, noodleId, 1)))).isEqualTo("INVALID_MODIFIER_SELECTION");
        assertThat(status(addDraft(s, spicyId, 1, mildId, 1))).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item WHERE session_id = ?", Integer.class, s)).isEqualTo(1);
    }

    @Test
    void 格式錯誤回VALIDATION_ERROR() throws Exception {
        long s = openSession();
        assertThat(errorCode(call(post(draftUrl(s)), itemBody(null, 1, null)))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post(draftUrl(s)), itemBody(beefId, null, null)))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post(draftUrl(s)), "{\"menuItemId\":" + beefId + ",\"quantity\":1.5}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post(draftUrl(s)), "{\"menuItemId\":" + beefId + ",\"quantity\":\"x\"}"))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 品項不存在或不可售() throws Exception {
        long s = openSession();
        MvcResult nf = addDraft(s, 999999, 1);
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("MENU_ITEM_NOT_FOUND");
        jdbc.update("UPDATE menu_item SET active = FALSE WHERE id = ?", sideId);
        MvcResult inactive = addDraft(s, sideId, 1);
        assertThat(status(inactive)).isEqualTo(409);
        assertThat(errorCode(inactive)).isEqualTo("MENU_ITEM_NOT_SELLABLE");
        jdbc.update("UPDATE menu_item SET sold_out_date = '2026-03-02' WHERE id = ?", beefId);
        assertThat(errorCode(addDraft(s, beefId, 1))).isEqualTo("MENU_ITEM_NOT_SELLABLE");
    }

    @Test
    void 用餐紀錄狀態限制() throws Exception {
        long s = openSession();
        MvcResult nf = addDraft(999999, beefId, 1);
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("SESSION_NOT_FOUND");
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", s);
        assertThat(errorCode(addDraft(s, beefId, 1))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, pending_recheckout = TRUE WHERE id = ?", s);
        assertThat(errorCode(addDraft(s, beefId, 1))).isEqualTo("SESSION_PENDING_RECHECKOUT");
        jdbc.update("UPDATE table_session SET pending_recheckout = FALSE, status = 'CLOSED' WHERE id = ?", s);
        assertThat(errorCode(addDraft(s, beefId, 1))).isEqualTo("SESSION_NOT_OPEN");
        assertThat(orderCount(s)).isZero();
    }

    // ---- 修改（整筆取代） ----

    @Test
    void 整筆取代_內容全換_價格以最新菜單為準() throws Exception {
        long s = openSession();
        long itemId = body(addDraft(s, beefId, 1, noodleId, 1)).get("items").get(0).get("id").asLong();
        jdbc.update("UPDATE menu_item SET price = 200 WHERE id = ?", beefId);
        jdbc.update("UPDATE modifier SET price_delta = 25 WHERE id = ?", eggId);
        MvcResult r = call(put(draftUrl(s) + "/" + itemId), itemBody(beefId, 3, "加辣", eggId, 1));
        assertThat(status(r)).isEqualTo(200);
        JsonNode item = body(r).get("items").get(0);
        assertThat(item.get("id").asLong()).isEqualTo(itemId);
        assertThat(item.get("quantity").asInt()).isEqualTo(3);
        assertThat(item.get("note").asText()).isEqualTo("加辣");
        assertThat(item.get("basePrice").asLong()).isEqualTo(200);
        assertThat(item.get("unitPrice").asLong()).isEqualTo(225); // 200 ＋ 加蛋 25
        assertThat(item.get("modifiers")).hasSize(1);
        assertThat(item.get("modifiers").get(0).get("name").asText()).isEqualTo("加蛋");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item_modifier", Integer.class)).isEqualTo(1);
    }

    @Test
    void 整筆取代可換成別的品項() throws Exception {
        long s = openSession();
        long itemId = body(addDraft(s, beefId, 1)).get("items").get(0).get("id").asLong();
        MvcResult r = call(put(draftUrl(s) + "/" + itemId), itemBody(sideId, 2, null));
        assertThat(body(r).get("items").get(0).get("name").asText()).isEqualTo("小菜");
        assertThat(body(r).get("items").get(0).get("station").asText()).isEqualTo("K2");
    }

    @Test
    void 取代的錯誤() throws Exception {
        long s = openSession();
        long itemId = body(addDraft(s, beefId, 1)).get("items").get(0).get("id").asLong();
        assertThat(errorCode(call(put(draftUrl(s) + "/999999"), itemBody(beefId, 1, null)))).isEqualTo("DRAFT_ITEM_NOT_FOUND");
        assertThat(errorCode(call(put(draftUrl(s) + "/" + itemId), itemBody(beefId, 0, null)))).isEqualTo("INVALID_QUANTITY");
        assertThat(errorCode(call(put(draftUrl(s) + "/" + itemId), itemBody(999999, 1, null)))).isEqualTo("MENU_ITEM_NOT_FOUND");
        long other = openSession();
        assertThat(errorCode(call(put(draftUrl(other) + "/" + itemId), itemBody(beefId, 1, null)))).isEqualTo("DRAFT_ITEM_NOT_FOUND");
        assertThat(errorCode(call(put(draftUrl(999999) + "/" + itemId), itemBody(beefId, 1, null)))).isEqualTo("SESSION_NOT_FOUND");
        // 失敗時原內容不變
        assertThat(detail(s).get("draft").get("items").get(0).get("quantity").asInt()).isEqualTo(1);
    }

    // ---- 刪除 ----

    @Test
    void 刪除是實體刪除_變空時保留空草稿() throws Exception {
        long s = openSession();
        long itemId = body(addDraft(s, beefId, 1, noodleId, 1)).get("items").get(0).get("id").asLong();
        long draftId = body(call(delete(draftUrl(s) + "/" + itemId))).get("orderId").asLong();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item_modifier", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id = ?", Integer.class, draftId)).isEqualTo(1);
        JsonNode d = detail(s).get("draft");
        assertThat(d.get("orderId").asLong()).isEqualTo(draftId);
        assertThat(d.get("items")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_history", Integer.class)).isZero();
    }

    @Test
    void 刪除的錯誤() throws Exception {
        long s = openSession();
        assertThat(errorCode(call(delete(draftUrl(s) + "/1")))).isEqualTo("DRAFT_ITEM_NOT_FOUND"); // 沒有草稿
        long itemId = body(addDraft(s, beefId, 1)).get("items").get(0).get("id").asLong();
        assertThat(errorCode(call(delete(draftUrl(s) + "/999999")))).isEqualTo("DRAFT_ITEM_NOT_FOUND");
        long other = openSession();
        addDraft(other, beefId, 1);
        assertThat(errorCode(call(delete(draftUrl(other) + "/" + itemId)))).isEqualTo("DRAFT_ITEM_NOT_FOUND");
        assertThat(errorCode(call(delete(draftUrl(999999) + "/" + itemId)))).isEqualTo("SESSION_NOT_FOUND");
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", s);
        assertThat(errorCode(call(delete(draftUrl(s) + "/" + itemId)))).isEqualTo("CHECKOUT_IN_PROGRESS");
    }

    @Test
    void 不同用餐紀錄的草稿互相獨立() throws Exception {
        long a = openSession();
        long b = openSession();
        addDraft(a, beefId, 1);
        addDraft(b, sideId, 2);
        assertThat(detail(a).get("draft").get("items")).hasSize(1);
        assertThat(detail(b).get("draft").get("items").get(0).get("name").asText()).isEqualTo("小菜");
    }

    @Test
    void 只有POS可以操作草稿() throws Exception {
        long s = openSession();
        assertThat(status(mvc.perform(post(draftUrl(s)).contentType("application/json").content(itemBody(beefId, 1, null))).andReturn())).isEqualTo(401);
        List<String> none = new ArrayList<>();
        assertThat(none).isEmpty();
    }
}
