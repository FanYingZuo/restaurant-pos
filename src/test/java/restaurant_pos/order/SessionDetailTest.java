package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 卡 5.9：用餐詳情——小計只算正式單未取消品項，不含草稿與服務費（01-PLAN S5）。 */
class SessionDetailTest extends OrderTestBase {

    @Test
    void 剛開桌_沒有草稿沒有訂單_小計0() throws Exception {
        long s = openSession();
        JsonNode d = detail(s);
        assertThat(d.get("session").get("id").asLong()).isEqualTo(s);
        assertThat(d.get("session").get("billNo").asText()).isEqualTo("001");
        assertThat(d.get("draft").isNull()).isTrue();
        assertThat(d.get("orders")).isEmpty();
        assertThat(d.get("subtotal").asLong()).isZero();
    }

    @Test
    void 小計不含草稿() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1"); // 400
        addDraft(s, beefId, 5); // 草稿 750，不計入
        JsonNode d = detail(s);
        assertThat(d.get("subtotal").asLong()).isEqualTo(400);
        assertThat(d.get("draft").get("items")).hasSize(1);
    }

    @Test
    void 小計不含服務費() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        assertThat(detail(s).get("session").get("serviceChargeEnabled").asBoolean()).isTrue();
        assertThat(detail(s).get("subtotal").asLong()).isEqualTo(400); // 不是 440
    }

    @Test
    void 小計不含已取消品項_已取消的訂單仍列出() throws Exception {
        long s = openSession();
        JsonNode first = submitStandardOrder(s, "r1");
        addDraft(s, sideId, 2);
        long secondOrder = body(submit(s, "r2", null)).get("orderId").asLong();
        cancelItem(itemIdByName(first, "小菜"), PIN, "x");
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pos/orders/" + secondOrder + "/cancel"), pinReason(PIN, "全取消"));
        JsonNode d = detail(s);
        assertThat(d.get("orders")).hasSize(2);
        assertThat(d.get("orders").get(1).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(d.get("subtotal").asLong()).isEqualTo(340); // 只剩牛肉麵 170×2
    }

    @Test
    void 訂單依批次號排序_含來源與版本() throws Exception {
        long s = openSession();
        for (int i = 1; i <= 3; i++) {
            addDraft(s, sideId, i);
            submit(s, "r" + i, null);
        }
        JsonNode orders = detail(s).get("orders");
        List<Integer> batches = new ArrayList<>();
        orders.forEach(o -> batches.add(o.get("batchNo").asInt()));
        assertThat(batches).containsExactly(1, 2, 3);
        assertThat(orders.get(0).get("source").asText()).isEqualTo("STAFF");
        assertThat(orders.get(0).get("version").asInt()).isZero();
        assertThat(orders.get(0).get("submittedAt").asText()).isEqualTo("2026-03-02T00:00:00Z");
        assertThat(detail(s).get("subtotal").asLong()).isEqualTo(60 * (1 + 2 + 3));
    }

    @Test
    void 品項含選項快照與狀態() throws Exception {
        long s = openSession();
        JsonNode order = submitStandardOrder(s, "r1");
        JsonNode beef = order.get("items").get(0);
        assertThat(beef.get("name").asText()).isEqualTo("牛肉麵");
        assertThat(beef.get("status").asText()).isEqualTo("PENDING");
        assertThat(beef.get("version").asInt()).isZero();
        assertThat(beef.get("station").asText()).isEqualTo("K1");
        assertThat(beef.get("modifiers").get(0).get("name").asText()).isEqualTo("加麵");
        assertThat(beef.get("cancelReason").isNull()).isTrue();
        assertThat(beef.get("cancelledAt").isNull()).isTrue();
        List<String> fields = new ArrayList<>();
        beef.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "orderId", "menuItemId", "name", "basePrice", "unitPrice",
                "quantity", "lineTotal", "station", "modifiers", "note", "status", "version", "cancelReason", "cancelledAt");
    }

    @Test
    void 任何狀態的用餐紀錄都可查詢() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        for (String status : new String[] {"CLOSED", "CANCELLED"}) {
            jdbc.update("UPDATE table_session SET status = ? WHERE id = ?", status, s);
            JsonNode d = detail(s);
            assertThat(d.get("session").get("status").asText()).isEqualTo(status);
            assertThat(d.get("subtotal").asLong()).isEqualTo(400);
        }
    }

    @Test
    void 不存在回SESSION_NOT_FOUND() throws Exception {
        MvcResult r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/pos/sessions/999999"));
        assertThat(status(r)).isEqualTo(404);
        assertThat(errorCode(r)).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void 不同用餐紀錄的小計互相獨立() throws Exception {
        long a = openSession();
        long b = openSession();
        submitStandardOrder(a, "r1");
        assertThat(detail(a).get("subtotal").asLong()).isEqualTo(400);
        assertThat(detail(b).get("subtotal").asLong()).isZero();
    }

    @Test
    void 修改後小計以新價格計算() throws Exception {
        long s = openSession();
        JsonNode order = submitStandardOrder(s, "r1");
        modify(itemIdByName(order, "小菜"), 0, 3, "");
        assertThat(detail(s).get("subtotal").asLong()).isEqualTo(170 * 2 + 60 * 3);
    }

    @Test
    void 用餐詳情需要POS裝置憑證() throws Exception {
        long s = openSession();
        assertThat(status(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/pos/sessions/" + s)).andReturn())).isEqualTo(401);
    }
}
