package restaurant_pos.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.support.AbstractIntegrationTest;
import restaurant_pos.support.OrderEventProbe;
import restaurant_pos.support.TableEventProbe;

/**
 * 訂單測試的共用輔助。菜單與桌位用 SQL 直接建立（快）；訂單操作一律走 HTTP API。
 * 預設菜單：牛肉麵 150（K1，加料 0～2 不可重複：加麵 +20、加蛋 +15）、小菜 60（K2）、
 * 辣麵 120（K1，辣度 1～1：小辣 0、大辣 5）。
 */
public abstract class OrderTestBase extends AbstractIntegrationTest {

    public static final String PIN = "1234";

    @Autowired
    protected OrderEventProbe orderEvents;

    @Autowired
    protected TableEventProbe tableEvents;

    protected TestDevice pos;
    protected long employeeId;
    protected long beefId;
    protected long sideId;
    protected long spicyId;
    protected long noodleId;
    protected long eggId;
    protected long mildId;
    protected long hotId;
    protected long toppingGroupId;
    protected long spicyGroupId;

    @BeforeEach
    void setUpOrders() {
        orderEvents.clear();
        tableEvents.clear();
        pos = registerDevice(DeviceType.POS);
        employeeId = createEmployee("小明", "STAFF", PIN);
        long cat = insert("INSERT INTO menu_category (name, display_order, active, created_at) VALUES ('麵食', 0, TRUE, NOW(6))");
        beefId = item(cat, "牛肉麵", 150, "K1", 0);
        toppingGroupId = group(beefId, "加料", 0, 2, false, 0);
        noodleId = modifier(toppingGroupId, "加麵", 20, 0);
        eggId = modifier(toppingGroupId, "加蛋", 15, 1);
        sideId = item(cat, "小菜", 60, "K2", 1);
        spicyId = item(cat, "辣麵", 120, "K1", 2);
        spicyGroupId = group(spicyId, "辣度", 1, 1, false, 0);
        mildId = modifier(spicyGroupId, "小辣", 0, 0);
        hotId = modifier(spicyGroupId, "大辣", 5, 1);
    }

    // ---- 以 SQL 建立資料 ----

    protected long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    protected long item(long categoryId, String name, int price, String station, int order) {
        return insert("INSERT INTO menu_item (category_id, name, price, station_code, active, display_order, created_at) "
                + "VALUES (?, ?, ?, ?, TRUE, ?, NOW(6))", categoryId, name, price, station, order);
    }

    protected long group(long itemId, String name, int min, int max, boolean allowDuplicate, int order) {
        return insert("INSERT INTO modifier_group (menu_item_id, name, min_select, max_select, allow_duplicate, display_order, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, NOW(6))", itemId, name, min, max, allowDuplicate, order);
    }

    protected long modifier(long groupId, String name, int priceDelta, int order) {
        return insert("INSERT INTO modifier (modifier_group_id, name, price_delta, display_order, created_at) "
                + "VALUES (?, ?, ?, ?, NOW(6))", groupId, name, priceDelta, order);
    }

    private int tableSeq;

    /** 建立桌位並開桌，回傳用餐紀錄編號。 */
    protected long openSession() throws Exception {
        long tableId = insert("INSERT INTO dining_table (table_no, capacity, shape, width, height, x, y, area, created_at) "
                + "VALUES (?, 4, 'SQUARE', 64, 64, 0, 0, '', NOW(6))", "T" + (++tableSeq));
        MvcResult r = call(post("/api/pos/tables/" + tableId + "/open"), "{\"guestCount\":2}");
        return body(r).get("id").asLong();
    }

    // ---- HTTP ----

    protected MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request = request.header("X-Device-Token", pos.token());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    protected MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, null);
    }

    protected JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    protected String errorCode(MvcResult r) throws Exception {
        return body(r).get("code").asText();
    }

    /** 加入或取代品項用的請求內容。mods 為「選項編號, 數量」成對的列表。 */
    protected String itemBody(Object menuItemId, Object quantity, String note, long... mods) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        if (menuItemId != null) {
            m.put("menuItemId", menuItemId);
        }
        if (quantity != null) {
            m.put("quantity", quantity);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i + 1 < mods.length; i += 2) {
            list.add(Map.of("modifierId", mods[i], "quantity", mods[i + 1]));
        }
        m.put("modifiers", list);
        if (note != null) {
            m.put("note", note);
        }
        return json.writeValueAsString(m);
    }

    protected MvcResult addDraft(long sessionId, long menuItemId, int quantity, long... mods) throws Exception {
        return call(post("/api/pos/sessions/" + sessionId + "/draft/items"), itemBody(menuItemId, quantity, null, mods));
    }

    protected MvcResult submit(long sessionId, String requestId, String note) throws Exception {
        return call(post("/api/pos/sessions/" + sessionId + "/draft/submit"),
                json.writeValueAsString(note == null ? Map.of("requestId", requestId) : Map.of("requestId", requestId, "note", note)));
    }

    protected JsonNode detail(long sessionId) throws Exception {
        return body(call(get("/api/pos/sessions/" + sessionId)));
    }

    /** 送出一張包含牛肉麵×2（加麵）與小菜×1 的單，回傳該單的 JSON（取自用餐詳情）。 */
    protected JsonNode submitStandardOrder(long sessionId, String requestId) throws Exception {
        addDraft(sessionId, beefId, 2, noodleId, 1);
        addDraft(sessionId, sideId, 1);
        MvcResult r = submit(sessionId, requestId, null);
        long orderId = body(r).get("orderId").asLong();
        for (JsonNode o : detail(sessionId).get("orders")) {
            if (o.get("id").asLong() == orderId) {
                return o;
            }
        }
        throw new AssertionError("找不到剛送出的訂單");
    }

    protected long itemIdByName(JsonNode order, String name) {
        for (JsonNode i : order.get("items")) {
            if (i.get("name").asText().equals(name)) {
                return i.get("id").asLong();
            }
        }
        throw new AssertionError("找不到品項 " + name);
    }

    protected JsonNode itemOf(long sessionId, long orderItemId) throws Exception {
        for (JsonNode o : detail(sessionId).get("orders")) {
            for (JsonNode i : o.get("items")) {
                if (i.get("id").asLong() == orderItemId) {
                    return i;
                }
            }
        }
        throw new AssertionError("找不到品項 " + orderItemId);
    }

    protected MvcResult modify(long orderItemId, Object expectedVersion, Object quantity, String note, long... mods) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        if (expectedVersion != null) {
            m.put("expectedVersion", expectedVersion);
        }
        if (quantity != null) {
            m.put("quantity", quantity);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i + 1 < mods.length; i += 2) {
            list.add(Map.of("modifierId", mods[i], "quantity", mods[i + 1]));
        }
        m.put("modifiers", list);
        if (note != null) {
            m.put("note", note);
        }
        return call(patch("/api/pos/order-items/" + orderItemId), json.writeValueAsString(m));
    }

    protected MvcResult cancelItem(long orderItemId, String pin, String reason) throws Exception {
        return call(post("/api/pos/order-items/" + orderItemId + "/cancel"), pinReason(pin, reason));
    }

    protected String pinReason(String pin, String reason) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        if (pin != null) {
            m.put("pin", pin);
        }
        if (reason != null) {
            m.put("reason", reason);
        }
        return json.writeValueAsString(m);
    }

    protected String sessionStatus(long sessionId) {
        return jdbc.queryForObject("SELECT status FROM table_session WHERE id = ?", String.class, sessionId);
    }

    protected void setItemStatus(long orderItemId, String status) {
        jdbc.update("UPDATE order_item SET status = ?, version = version + 1 WHERE id = ?", status, orderItemId);
    }

    protected int orderCount(long sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE session_id = ?", Integer.class, sessionId);
    }

}
