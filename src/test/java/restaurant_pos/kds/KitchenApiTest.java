package restaurant_pos.kds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.order.OrderTestBase;
import restaurant_pos.shared.security.DeviceType;

/** 卡 6.1、6.2：站別清單查詢與狀態變更 API（01-PLAN S6）。 */
class KitchenApiTest extends OrderTestBase {

    @Autowired
    EntityManagerFactory emf;

    private TestDevice kds;

    @BeforeEach
    void registerKds() {
        kds = registerDevice(DeviceType.KDS);
    }

    private MvcResult kds(MockHttpServletRequestBuilder request, String body) throws Exception {
        request = request.header("X-Device-Token", kds.token());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode board(String station) throws Exception {
        MvcResult r = kds(get("/api/kds/stations/" + station + "/items"), null);
        assertThat(status(r)).isEqualTo(200);
        return body(r);
    }

    private JsonNode items(String station) throws Exception {
        return board(station).get("items");
    }

    private MvcResult start(String station, long itemId, Object version) throws Exception {
        return kds(post("/api/kds/stations/" + station + "/items/" + itemId + "/start"),
                version == null ? "{}" : "{\"expectedVersion\":" + version + "}");
    }

    private MvcResult complete(String station, long itemId, Object version) throws Exception {
        return kds(post("/api/kds/stations/" + station + "/items/" + itemId + "/complete"),
                version == null ? "{}" : "{\"expectedVersion\":" + version + "}");
    }

    // ---- 6.1 清單規則 ----

    @Test
    void 依站別分開列出() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        JsonNode k1 = items("K1");
        JsonNode k2 = items("K2");
        assertThat(k1).hasSize(1);
        assertThat(k1.get(0).get("name").asText()).isEqualTo("牛肉麵");
        assertThat(k2).hasSize(1);
        assertThat(k2.get(0).get("name").asText()).isEqualTo("小菜");
    }

    @Test
    void 清單內容欄位() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 2, noodleId, 1);
        call(post("/api/pos/sessions/" + s + "/draft/items"), itemBody(beefId, 1, "少辣"));
        submit(s, "r1", "不要香菜");
        JsonNode board = board("K1");
        assertThat(board.get("station").asText()).isEqualTo("K1");
        assertThat(board.get("generatedAt").asText()).isEqualTo("2026-03-02T00:00:00Z");
        JsonNode first = board.get("items").get(0);
        assertThat(first.get("tableNo").asText()).isEqualTo("T1");
        assertThat(first.get("batchNo").asInt()).isEqualTo(1);
        assertThat(first.get("quantity").asInt()).isEqualTo(2);
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("version").asInt()).isZero();
        assertThat(first.get("orderNote").asText()).isEqualTo("不要香菜");
        assertThat(first.get("submittedAt").asText()).isEqualTo("2026-03-02T00:00:00Z");
        assertThat(first.get("modifiers").get(0).get("name").asText()).isEqualTo("加麵");
        assertThat(first.get("modifiers").get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(board.get("items").get(1).get("note").asText()).isEqualTo("少辣");
        List<String> fields = new ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("orderItemId", "orderId", "sessionId", "tableNo", "batchNo", "name",
                "quantity", "note", "modifiers", "status", "version", "submittedAt", "orderNote");
    }

    @Test
    void 依品項編號遞增() throws Exception {
        long a = openSession();
        long b = openSession();
        addDraft(b, beefId, 1);
        submit(b, "r1", null);
        addDraft(a, beefId, 1);
        submit(a, "r1", null);
        JsonNode list = items("K1");
        assertThat(list.get(0).get("orderItemId").asLong()).isLessThan(list.get(1).get("orderItemId").asLong());
        assertThat(list.get(0).get("sessionId").asLong()).isEqualTo(b);
    }

    @Test
    void 只列待處理與製作中_已完成與已取消不列() throws Exception {
        long s = openSession();
        for (int i = 0; i < 4; i++) {
            addDraft(s, beefId, 1);
        }
        submit(s, "r1", null);
        List<Long> ids = new ArrayList<>();
        items("K1").forEach(n -> ids.add(n.get("orderItemId").asLong()));
        setItemStatus(ids.get(1), "IN_PROGRESS");
        setItemStatus(ids.get(2), "DONE");
        setItemStatus(ids.get(3), "CANCELLED");
        JsonNode list = items("K1");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(list.get(1).get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void 草稿不列() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        assertThat(items("K1")).isEmpty();
    }

    @Test
    void 使用中與今天已結束的用餐紀錄列出_先付後吃() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        jdbc.update("UPDATE table_session SET status = 'CLOSED' WHERE id = ?", s);
        assertThat(items("K1")).hasSize(1);
        assertThat(items("K2")).hasSize(1);
    }

    @Test
    void 前一營業日已結束的用餐紀錄不列() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        jdbc.update("UPDATE table_session SET status = 'CLOSED' WHERE id = ?", s);
        clock.set(Instant.parse("2026-03-02T20:00:00Z")); // 台北 03-03 04:00，已是下一個營業日
        assertThat(items("K1")).isEmpty();
    }

    @Test
    void 已取消的用餐紀錄不列() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        jdbc.update("UPDATE table_session SET status = 'CANCELLED' WHERE id = ?", s);
        assertThat(items("K1")).isEmpty();
    }

    @Test
    void 結帳中的用餐紀錄仍列出() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", s);
        assertThat(items("K1")).hasSize(1);
    }

    // ---- 6.1 單一查詢 ----

    @Test
    void 清單查詢次數固定_不隨品項數增加() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        long s = openSession();
        submitStandardOrder(s, "r1");
        items("K1"); // 暖機
        stats.clear();
        items("K1");
        long small = stats.getPrepareStatementCount();

        for (int i = 0; i < 6; i++) {
            long other = openSession();
            for (int k = 0; k < 4; k++) {
                addDraft(other, beefId, 1, noodleId, 1);
            }
            submit(other, "r1", null);
        }
        stats.clear();
        JsonNode big = items("K1");
        long large = stats.getPrepareStatementCount();
        assertThat(big.size()).isGreaterThan(20);
        assertThat(large).as("品項增加後 SQL 數量仍相同").isEqualTo(small);
        assertThat(large).as("含裝置驗證在內的固定上限").isLessThanOrEqualTo(10);
    }

    // ---- 權限與站別 ----

    @Test
    void 站別不存在回404() throws Exception {
        for (String bad : new String[] {"K3", "k1", "XX", "0"}) {
            MvcResult r = kds(get("/api/kds/stations/" + bad + "/items"), null);
            assertThat(status(r)).as(bad).isEqualTo(404);
            assertThat(errorCode(r)).isEqualTo("NOT_FOUND");
        }
        long s = openSession();
        submitStandardOrder(s, "r1");
        long itemId = items("K1").get(0).get("orderItemId").asLong();
        assertThat(errorCode(start("K9", itemId, 0))).isEqualTo("NOT_FOUND");
    }

    @Test
    void KDS路徑只限KDS裝置() throws Exception {
        assertThat(status(mvc.perform(get("/api/kds/stations/K1/items")).andReturn())).isEqualTo(401);
        MvcResult pos = mvc.perform(get("/api/kds/stations/K1/items").header("X-Device-Token", this.pos.token())).andReturn();
        assertThat(status(pos)).isEqualTo(403);
        assertThat(errorCode(pos)).isEqualTo("DEVICE_TYPE_FORBIDDEN");
    }

    // ---- 6.2 狀態變更 API ----

    @Test
    void 開始製作與完成_帶預期版本() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        long beef = items("K1").get(0).get("orderItemId").asLong();
        MvcResult started = start("K1", beef, 0);
        assertThat(status(started)).isEqualTo(200);
        assertThat(body(started).get("orderItemId").asLong()).isEqualTo(beef);
        assertThat(body(started).get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(body(started).get("version").asInt()).isEqualTo(1);
        assertThat(items("K1").get(0).get("status").asText()).isEqualTo("IN_PROGRESS");

        MvcResult done = complete("K1", beef, 1);
        assertThat(body(done).get("status").asText()).isEqualTo("DONE");
        assertThat(body(done).get("version").asInt()).isEqualTo(2);
        assertThat(items("K1")).isEmpty(); // 完成後離開清單
    }

    @Test
    void 待處理可直接完成() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        long side = items("K2").get(0).get("orderItemId").asLong();
        assertThat(body(complete("K2", side, 0)).get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void 規則沿用品項狀態服務_錯誤碼() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        long beef = items("K1").get(0).get("orderItemId").asLong();
        MvcResult wrongStation = start("K2", beef, 0);
        assertThat(status(wrongStation)).isEqualTo(403);
        assertThat(errorCode(wrongStation)).isEqualTo("KDS_STATION_FORBIDDEN");
        MvcResult missing = start("K1", 999999, 0);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(errorCode(missing)).isEqualTo("KDS_ITEM_NOT_FOUND");
        assertThat(errorCode(start("K1", beef, 7))).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(errorCode(start("K1", beef, null))).isEqualTo("VALIDATION_ERROR");
        start("K1", beef, 0);
        assertThat(errorCode(start("K1", beef, 1))).isEqualTo("INVALID_ORDER_ITEM_TRANSITION");
        jdbc.update("UPDATE table_session SET status = 'CANCELLED' WHERE id = ?", s);
        assertThat(errorCode(complete("K1", beef, 1))).isEqualTo("SESSION_NOT_OPEN");
    }

    @Test
    void 已取消的品項不能操作() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        long beef = items("K1").get(0).get("orderItemId").asLong();
        cancelItem(beef, PIN, "客人不要了");
        MvcResult r = start("K1", beef, 1);
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("ORDER_ITEM_CANCELLED");
        assertThat(items("K1")).isEmpty();
    }

    @Test
    void 先付款後出餐時品項仍可操作() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        jdbc.update("UPDATE table_session SET status = 'CLOSED' WHERE id = ?", s);
        long beef = items("K1").get(0).get("orderItemId").asLong();
        assertThat(status(start("K1", beef, 0))).isEqualTo(200);
        assertThat(status(complete("K1", beef, 1))).isEqualTo(200);
    }
}
