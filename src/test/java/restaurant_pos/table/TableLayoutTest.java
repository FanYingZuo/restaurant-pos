package restaurant_pos.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;

/** 卡 4.1：桌位 CRUD（TABLE_LAYOUT）（01-PLAN S4）。 */
class TableLayoutTest extends TableTestBase {

    private static String json(String tableNo, int capacity, String shape, int width, int height, int x, int y, String area) {
        return "{\"tableNo\":\"" + tableNo + "\",\"capacity\":" + capacity + ",\"shape\":\"" + shape + "\",\"width\":"
                + width + ",\"height\":" + height + ",\"x\":" + x + ",\"y\":" + y
                + (area == null ? "" : ",\"area\":\"" + area + "\"") + "}";
    }

    private MvcResult create(String body) throws Exception {
        return layout(post("/api/pos/tables"), body);
    }

    // ---- 權限 ----

    @Test
    void 版面編輯需要TABLE_LAYOUT解鎖_看板列表只需裝置憑證() throws Exception {
        long id = table("A1");
        String wrong = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        for (String t : new String[] {null, wrong}) {
            assertThat(errorCode(call(post("/api/pos/tables"), t, json("B1", 4, "SQUARE", 64, 64, 0, 0, null)))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(patch("/api/pos/tables/" + id), t, "{\"capacity\":6}"))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(delete("/api/pos/tables/" + id), t, null))).isEqualTo("FORBIDDEN");
        }
        assertThat(status(call(get("/api/pos/tables"), null))).isEqualTo(200);
    }

    @Test
    void 只有POS可以呼叫桌位API() throws Exception {
        TestDevice kds = registerDevice(DeviceType.KDS);
        MvcResult r = mvc.perform(get("/api/pos/tables").header("X-Device-Token", kds.token())).andReturn();
        assertThat(errorCode(r)).isEqualTo("DEVICE_TYPE_FORBIDDEN");
        assertThat(status(mvc.perform(get("/api/pos/tables")).andReturn())).isEqualTo(401);
    }

    // ---- 新增 ----

    @Test
    void 三種形狀都可新增() throws Exception {
        MvcResult square = create(json("A1", 4, "SQUARE", 64, 64, 10, 20, "一樓"));
        assertThat(status(square)).isEqualTo(201);
        JsonNode b = body(square);
        assertThat(b.get("tableNo").asText()).isEqualTo("A1");
        assertThat(b.get("shape").asText()).isEqualTo("SQUARE");
        assertThat(b.get("area").asText()).isEqualTo("一樓");
        assertThat(b.get("capacity").asInt()).isEqualTo(4);
        assertThat(status(create(json("A2", 6, "ROUND", 80, 80, 0, 0, null)))).isEqualTo(201);
        MvcResult rect = create(json("A3", 8, "RECTANGLE", 120, 60, 5000, 5000, null));
        assertThat(status(rect)).isEqualTo(201);
        assertThat(body(rect).get("width").asInt()).isEqualTo(120);
        assertThat(body(rect).get("height").asInt()).isEqualTo(60);
        assertThat(body(rect).get("area").asText()).isEmpty();
    }

    @Test
    void 欄位驗證的邊界值() throws Exception {
        // 合法邊界
        assertThat(status(create(json("x".repeat(20), 1, "RECTANGLE", 40, 400, 0, 0, "y".repeat(32))))).isEqualTo(201);
        assertThat(status(create(json("B", 50, "RECTANGLE", 400, 40, 5000, 5000, null)))).isEqualTo(201);
        // 非法
        String[] bad = {
                json("", 4, "SQUARE", 64, 64, 0, 0, null),
                json("   ", 4, "SQUARE", 64, 64, 0, 0, null),
                json("x".repeat(21), 4, "SQUARE", 64, 64, 0, 0, null),
                json("C1", 0, "SQUARE", 64, 64, 0, 0, null),
                json("C2", 51, "SQUARE", 64, 64, 0, 0, null),
                json("C3", 4, "RECTANGLE", 39, 64, 0, 0, null),
                json("C4", 4, "RECTANGLE", 401, 64, 0, 0, null),
                json("C5", 4, "RECTANGLE", 64, 39, 0, 0, null),
                json("C6", 4, "RECTANGLE", 64, 401, 0, 0, null),
                json("C7", 4, "SQUARE", 64, 64, -1, 0, null),
                json("C8", 4, "SQUARE", 64, 64, 5001, 0, null),
                json("C9", 4, "SQUARE", 64, 64, 0, -1, null),
                json("C10", 4, "SQUARE", 64, 64, 0, 5001, null),
                json("C11", 4, "SQUARE", 64, 64, 0, 0, "y".repeat(33)),
                json("C12", 4, "TRIANGLE", 64, 64, 0, 0, null),
                "{\"tableNo\":\"C13\",\"capacity\":4,\"shape\":\"SQUARE\",\"width\":64,\"height\":64,\"y\":0}",
                "{}"};
        for (String body : bad) {
            MvcResult r = create(body);
            assertThat(status(r)).as(body).isEqualTo(400);
            assertThat(errorCode(r)).as(body).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void 桌號前後空白會去除() throws Exception {
        assertThat(body(create(json("  A1  ", 4, "SQUARE", 64, 64, 0, 0, null))).get("tableNo").asText()).isEqualTo("A1");
    }

    @Test
    void 方形與圓形的寬高不同回VALIDATION_ERROR_不自動修正() throws Exception {
        for (String shape : new String[] {"SQUARE", "ROUND"}) {
            MvcResult r = create(json("D1", 4, shape, 64, 80, 0, 0, null));
            assertThat(status(r)).as(shape).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dining_table", Integer.class)).isZero();
        assertThat(status(create(json("D2", 4, "RECTANGLE", 64, 80, 0, 0, null)))).isEqualTo(201);
    }

    // ---- 桌號唯一 ----

    @Test
    void 桌號不分大小寫_去空白後唯一() throws Exception {
        table("A1");
        for (String dup : new String[] {"A1", "a1", "  a1 ", "A1  "}) {
            MvcResult r = create(json(dup, 4, "SQUARE", 64, 64, 0, 0, null));
            assertThat(status(r)).as(dup).isEqualTo(409);
            assertThat(errorCode(r)).isEqualTo("TABLE_NO_DUPLICATE");
        }
        assertThat(status(create(json("A2", 4, "SQUARE", 64, 64, 0, 0, null)))).isEqualTo(201);
    }

    @Test
    void 已刪除桌位的桌號可重複使用() throws Exception {
        long id = table("A1");
        assertThat(status(layout(delete("/api/pos/tables/" + id), null))).isEqualTo(204);
        assertThat(status(create(json("A1", 4, "SQUARE", 64, 64, 0, 0, null)))).isEqualTo(201);
    }

    // ---- 修改 ----

    @Test
    void 修改桌位_部分欄位() throws Exception {
        long id = table("A1");
        MvcResult r = layout(patch("/api/pos/tables/" + id), "{\"capacity\":6,\"x\":300,\"area\":\"二樓\"}");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("capacity").asInt()).isEqualTo(6);
        assertThat(body(r).get("x").asInt()).isEqualTo(300);
        assertThat(body(r).get("area").asText()).isEqualTo("二樓");
        assertThat(body(r).get("tableNo").asText()).isEqualTo("A1");
        assertThat(body(r).get("width").asInt()).isEqualTo(64);
    }

    @Test
    void 修改後仍要符合形狀規則() throws Exception {
        long id = table("A1"); // SQUARE 64×64
        assertThat(errorCode(layout(patch("/api/pos/tables/" + id), "{\"width\":100}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(status(layout(patch("/api/pos/tables/" + id), "{\"width\":100,\"height\":100}"))).isEqualTo(200);
        assertThat(status(layout(patch("/api/pos/tables/" + id), "{\"shape\":\"RECTANGLE\",\"width\":150}"))).isEqualTo(200);
        assertThat(errorCode(layout(patch("/api/pos/tables/" + id), "{\"shape\":\"ROUND\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(layout(patch("/api/pos/tables/" + id), "{}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(layout(patch("/api/pos/tables/" + id), "{\"capacity\":99}"))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 修改桌號不可與其他桌重複_改自己的大小寫可以() throws Exception {
        long a = table("A1");
        table("A2");
        assertThat(errorCode(layout(patch("/api/pos/tables/" + a), "{\"tableNo\":\"a2\"}"))).isEqualTo("TABLE_NO_DUPLICATE");
        assertThat(status(layout(patch("/api/pos/tables/" + a), "{\"tableNo\":\"a1\"}"))).isEqualTo(200);
    }

    // ---- 使用中不可編輯或刪除 ----

    @Test
    void 使用中的桌位不可編輯不可刪除() throws Exception {
        long id = table("A1");
        open(id, 2);
        MvcResult edit = layout(patch("/api/pos/tables/" + id), "{\"capacity\":6}");
        assertThat(status(edit)).isEqualTo(409);
        assertThat(errorCode(edit)).isEqualTo("TABLE_OCCUPIED");
        MvcResult del = layout(delete("/api/pos/tables/" + id), null);
        assertThat(status(del)).isEqualTo(409);
        assertThat(errorCode(del)).isEqualTo("TABLE_OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT capacity FROM dining_table WHERE id = ?", Integer.class, id)).isEqualTo(4);
    }

    @Test
    void 用餐結束後桌位可以編輯() throws Exception {
        long id = table("A1");
        long sessionId = open(id, 2).get("id").asLong();
        jdbc.update("UPDATE table_session SET status = 'CLOSED', occupying_table_id = NULL WHERE id = ?", sessionId);
        assertThat(status(layout(patch("/api/pos/tables/" + id), "{\"capacity\":6}"))).isEqualTo(200);
    }

    // ---- 不存在 ----

    @Test
    void 不存在或已刪除的桌位回404() throws Exception {
        MvcResult nf = layout(patch("/api/pos/tables/999999"), "{\"capacity\":6}");
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("TABLE_NOT_FOUND");
        assertThat(errorCode(layout(delete("/api/pos/tables/999999"), null))).isEqualTo("TABLE_NOT_FOUND");
        long id = table("A1");
        layout(delete("/api/pos/tables/" + id), null);
        assertThat(errorCode(layout(delete("/api/pos/tables/" + id), null))).isEqualTo("TABLE_NOT_FOUND");
        assertThat(errorCode(layout(patch("/api/pos/tables/" + id), "{\"capacity\":6}"))).isEqualTo("TABLE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM dining_table WHERE id = ?", Object.class, id)).isNotNull();
    }

    // ---- 列表 ----

    @Test
    void 列表依桌號自然排序_排除已刪除() throws Exception {
        for (String no : new String[] {"A10", "A2", "B1", "A1"}) {
            table(no);
        }
        long gone = table("A3");
        layout(delete("/api/pos/tables/" + gone), null);
        List<String> numbers = new ArrayList<>();
        tableList().forEach(t -> numbers.add(t.get("tableNo").asText()));
        assertThat(numbers).containsExactly("A1", "A2", "A10", "B1");
    }

    @Test
    void 列表帶用餐狀態_空桌為null() throws Exception {
        long a = table("A1");
        table("A2");
        open(a, 3);
        JsonNode list = tableList();
        assertThat(list.get(0).get("inUse").asBoolean()).isTrue();
        assertThat(list.get(0).get("session").get("guestCount").asInt()).isEqualTo(3);
        assertThat(list.get(0).get("session").get("billNo").asText()).isEqualTo("001");
        assertThat(list.get(1).get("inUse").asBoolean()).isFalse();
        assertThat(list.get(1).get("session").isNull()).isTrue();
        List<String> fields = new ArrayList<>();
        list.get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "tableNo", "capacity", "shape", "width", "height", "x", "y",
                "area", "inUse", "session");
    }
}
