package restaurant_pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;

/** 卡 3.1～3.3：分類、品項、選項群組與選項（01-PLAN S3）。 */
class MenuManagementTest extends MenuTestBase {

    // ---- 權限 ----

    @Test
    void 菜單管理需要裝置憑證且只限POS() throws Exception {
        assertThat(status(mvc.perform(get("/api/pos/menu")).andReturn())).isEqualTo(401);
        TestDevice kds = registerDevice(DeviceType.KDS);
        MvcResult r = mvc.perform(get("/api/pos/menu").header("X-Device-Token", kds.token())).andReturn();
        assertThat(status(r)).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("DEVICE_TYPE_FORBIDDEN");
        // 菜單管理不需要 PIN 也不需要解鎖
        assertThat(status(call(get("/api/pos/menu")))).isEqualTo(200);
    }

    @Test
    void 站別種子資料是K1與K2() {
        assertThat(jdbc.queryForList("SELECT code FROM station ORDER BY code", String.class)).containsExactly("K1", "K2");
    }

    // ---- 3.1 分類 ----

    @Test
    void 新增分類_預設上架_順序依序附加() throws Exception {
        MvcResult a = call(post("/api/pos/menu/categories"), "{\"name\":\"麵食\"}");
        assertThat(status(a)).isEqualTo(201);
        assertThat(body(a).get("name").asText()).isEqualTo("麵食");
        assertThat(body(a).get("active").asBoolean()).isTrue();
        assertThat(body(a).get("displayOrder").asInt()).isZero();
        assertThat(body(a).get("items")).isEmpty();
        assertThat(body(call(post("/api/pos/menu/categories"), "{\"name\":\"飲料\"}")).get("displayOrder").asInt()).isEqualTo(1);
    }

    @Test
    void 分類欄位驗證() throws Exception {
        for (String name : new String[] {"", "   ", "x".repeat(51)}) {
            MvcResult r = call(post("/api/pos/menu/categories"), "{\"name\":\"" + name + "\"}");
            assertThat(status(r)).as(name).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(status(call(post("/api/pos/menu/categories"), "{\"name\":\"" + "x".repeat(50) + "\"}"))).isEqualTo(201);
        assertThat(body(call(post("/api/pos/menu/categories"), "{\"name\":\"  湯品  \"}")).get("name").asText()).isEqualTo("湯品");
        for (String order : new String[] {"-1", "10000"}) {
            assertThat(errorCode(call(post("/api/pos/menu/categories"), "{\"name\":\"a\",\"displayOrder\":" + order + "}"))).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(status(call(post("/api/pos/menu/categories"), "{\"name\":\"頂\",\"displayOrder\":9999}"))).isEqualTo(201);
        assertThat(status(call(post("/api/pos/menu/categories"), "{}"))).isEqualTo(400);
    }

    @Test
    void 修改分類_名稱順序上下架() throws Exception {
        long id = category("麵食");
        MvcResult r = call(patch("/api/pos/menu/categories/" + id), "{\"name\":\"主食\",\"displayOrder\":7,\"active\":false}");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("name").asText()).isEqualTo("主食");
        assertThat(body(r).get("displayOrder").asInt()).isEqualTo(7);
        assertThat(body(r).get("active").asBoolean()).isFalse();
    }

    @Test
    void 修改分類的錯誤() throws Exception {
        long id = category("麵食");
        assertThat(errorCode(call(patch("/api/pos/menu/categories/" + id), "{}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(patch("/api/pos/menu/categories/" + id), "{\"name\":\"\"}"))).isEqualTo("VALIDATION_ERROR");
        MvcResult nf = call(patch("/api/pos/menu/categories/999999"), "{\"name\":\"x\"}");
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("MENU_CATEGORY_NOT_FOUND");
    }

    @Test
    void 分類下有上架品項不可下架() throws Exception {
        long cat = category("麵食");
        item(cat, "牛肉麵", 150, "K1", true);
        MvcResult r = call(patch("/api/pos/menu/categories/" + cat), "{\"active\":false}");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("MENU_CATEGORY_IN_USE");
        assertThat(jdbc.queryForObject("SELECT active FROM menu_category WHERE id = ?", Boolean.class, cat)).isTrue();
    }

    @Test
    void 分類下只有已下架品項時可以下架() throws Exception {
        long cat = category("麵食");
        item(cat, "舊品項", 100, "K1", false);
        assertThat(status(call(patch("/api/pos/menu/categories/" + cat), "{\"active\":false}"))).isEqualTo(200);
    }

    @Test
    void 分類下有任何品項不可刪_空分類可刪且軟刪除() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", false);
        MvcResult blocked = call(delete("/api/pos/menu/categories/" + cat));
        assertThat(status(blocked)).isEqualTo(409);
        assertThat(errorCode(blocked)).isEqualTo("MENU_CATEGORY_IN_USE");

        call(patch("/api/pos/menu/items/" + it), "{\"active\":false}");
        call(delete("/api/pos/menu/items/" + it));
        assertThat(status(call(delete("/api/pos/menu/categories/" + cat)))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM menu_category WHERE id = ?", Object.class, cat)).isNotNull();
        assertThat(tree().get("categories")).isEmpty();
        assertThat(errorCode(call(delete("/api/pos/menu/categories/" + cat)))).isEqualTo("MENU_CATEGORY_NOT_FOUND");
    }

    // ---- 3.2 品項 ----

    @Test
    void 新增品項_預設下架_所以不可售() throws Exception {
        long cat = category("麵食");
        MvcResult r = call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"牛肉麵\",\"price\":150,\"station\":\"K1\"}");
        assertThat(status(r)).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("price").asLong()).isEqualTo(150);
        assertThat(b.get("station").asText()).isEqualTo("K1");
        assertThat(b.get("active").asBoolean()).isFalse();
        assertThat(b.get("sellable").asBoolean()).isFalse();
        assertThat(b.get("soldOut").asBoolean()).isFalse();
        assertThat(b.get("imageKey").isNull()).isTrue();
        assertThat(b.get("modifierGroups")).isEmpty();
        assertThat(b.get("categoryId").asLong()).isEqualTo(cat);
    }

    @Test
    void 品項價格規則_整數0到9999999() throws Exception {
        long cat = category("麵食");
        for (String price : new String[] {"0", "9999999", "150.00"}) {
            MvcResult r = call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"a\",\"price\":" + price + ",\"station\":\"K1\"}");
            assertThat(status(r)).as(price).isEqualTo(201);
        }
        for (String price : new String[] {"-1", "10000000", "150.5", "0.01", "-0.5"}) {
            MvcResult r = call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"a\",\"price\":" + price + ",\"station\":\"K1\"}");
            assertThat(status(r)).as(price).isEqualTo(400);
            assertThat(errorCode(r)).as(price).isEqualTo("INVALID_PRICE");
        }
        for (String bad : new String[] {"\"abc\"", "null", "[]"}) {
            MvcResult r = call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"a\",\"price\":" + bad + ",\"station\":\"K1\"}");
            assertThat(errorCode(r)).as(bad).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(errorCode(call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"a\",\"station\":\"K1\"}"))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 品項其他欄位驗證() throws Exception {
        long cat = category("麵食");
        assertThat(errorCode(call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"\",\"price\":10,\"station\":\"K1\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"" + "x".repeat(81) + "\",\"price\":10,\"station\":\"K1\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(status(call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"" + "x".repeat(80) + "\",\"price\":10,\"station\":\"K1\"}"))).isEqualTo(201);
        assertThat(errorCode(call(post("/api/pos/menu/items"), "{\"categoryId\":" + cat + ",\"name\":\"a\",\"price\":10,\"station\":\"K3\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post("/api/pos/menu/items"), "{\"name\":\"a\",\"price\":10,\"station\":\"K1\"}"))).isEqualTo("VALIDATION_ERROR");
        MvcResult nf = call(post("/api/pos/menu/items"), "{\"categoryId\":999999,\"name\":\"a\",\"price\":10,\"station\":\"K1\"}");
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("MENU_CATEGORY_NOT_FOUND");
    }

    @Test
    void 修改品項_改價寫稽核_同價不寫() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", false);
        MvcResult r = call(patch("/api/pos/menu/items/" + it), "{\"name\":\"紅燒牛肉麵\",\"price\":180,\"station\":\"K2\",\"active\":true}");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("name").asText()).isEqualTo("紅燒牛肉麵");
        assertThat(body(r).get("price").asLong()).isEqualTo(180);
        assertThat(body(r).get("station").asText()).isEqualTo("K2");
        assertThat(body(r).get("active").asBoolean()).isTrue();
        Map<String, Object> audit = jdbc.queryForMap("SELECT action, target_type, target_id, before_value, after_value, actor_device_id FROM audit_log");
        assertThat(audit).containsEntry("action", "MENU_ITEM_PRICE_CHANGED").containsEntry("target_type", "menu_item")
                .containsEntry("target_id", String.valueOf(it)).containsEntry("before_value", "150")
                .containsEntry("after_value", "180").containsEntry("actor_device_id", pos.id());
        call(patch("/api/pos/menu/items/" + it), "{\"price\":180}");
        call(patch("/api/pos/menu/items/" + it), "{\"name\":\"改名不改價\"}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(1);
    }

    @Test
    void 修改品項的錯誤() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", false);
        assertThat(errorCode(call(patch("/api/pos/menu/items/" + it), "{}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(patch("/api/pos/menu/items/" + it), "{\"price\":12.5}"))).isEqualTo("INVALID_PRICE");
        assertThat(errorCode(call(patch("/api/pos/menu/items/" + it), "{\"price\":-3}"))).isEqualTo("INVALID_PRICE");
        assertThat(jdbc.queryForObject("SELECT price FROM menu_item WHERE id = ?", Long.class, it)).isEqualTo(150);
        MvcResult nf = call(patch("/api/pos/menu/items/999999"), "{\"name\":\"x\"}");
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    @Test
    void 先下架才可刪除品項() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", true);
        MvcResult blocked = call(delete("/api/pos/menu/items/" + it));
        assertThat(status(blocked)).isEqualTo(409);
        assertThat(errorCode(blocked)).isEqualTo("MENU_ITEM_NOT_ARCHIVED");
        call(patch("/api/pos/menu/items/" + it), "{\"active\":false}");
        assertThat(status(call(delete("/api/pos/menu/items/" + it)))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM menu_item WHERE id = ?", Object.class, it)).isNotNull();
        assertThat(findItem(tree(), it)).isNull();
        assertThat(errorCode(call(delete("/api/pos/menu/items/" + it)))).isEqualTo("MENU_ITEM_NOT_FOUND");
        assertThat(errorCode(call(patch("/api/pos/menu/items/" + it), "{\"name\":\"x\"}"))).isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    // ---- 3.3 選項群組與選項 ----

    @Test
    void 新增選項群組() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        MvcResult r = call(post("/api/pos/menu/items/" + it + "/modifier-groups"), "{\"name\":\"加料\",\"minSelect\":0,\"maxSelect\":2}");
        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r).get("allowDuplicate").asBoolean()).isFalse();
        assertThat(body(r).get("minSelect").asInt()).isZero();
        assertThat(body(r).get("maxSelect").asInt()).isEqualTo(2);
        assertThat(body(r).get("modifiers")).isEmpty();
        assertThat(body(r).get("displayOrder").asInt()).isZero();
    }

    @Test
    void 群組欄位驗證_最少不得大於最多() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        String url = "/api/pos/menu/items/" + it + "/modifier-groups";
        for (String json : new String[] {
                "{\"name\":\"a\",\"minSelect\":3,\"maxSelect\":2}",
                "{\"name\":\"a\",\"minSelect\":-1,\"maxSelect\":2}",
                "{\"name\":\"a\",\"minSelect\":21,\"maxSelect\":21}",
                "{\"name\":\"a\",\"minSelect\":0,\"maxSelect\":0}",
                "{\"name\":\"a\",\"minSelect\":0,\"maxSelect\":21}",
                "{\"name\":\"\",\"minSelect\":0,\"maxSelect\":1}",
                "{\"name\":\"a\",\"maxSelect\":1}",
                "{\"name\":\"a\",\"minSelect\":0}"}) {
            MvcResult r = call(post(url), json);
            assertThat(status(r)).as(json).isEqualTo(400);
            assertThat(errorCode(r)).as(json).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(status(call(post(url), "{\"name\":\"a\",\"minSelect\":20,\"maxSelect\":20,\"allowDuplicate\":true}"))).isEqualTo(201);
        assertThat(status(call(post(url), "{\"name\":\"b\",\"minSelect\":1,\"maxSelect\":1}"))).isEqualTo(201);
        MvcResult nf = call(post("/api/pos/menu/items/999999/modifier-groups"), "{\"name\":\"a\",\"minSelect\":0,\"maxSelect\":1}");
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    @Test
    void 修改群組_合併後仍要符合最少不大於最多() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g = group(it, "加料", 0, 2, false);
        assertThat(errorCode(call(patch("/api/pos/menu/modifier-groups/" + g), "{\"minSelect\":3}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(patch("/api/pos/menu/modifier-groups/" + g), "{\"maxSelect\":0}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(patch("/api/pos/menu/modifier-groups/" + g), "{}"))).isEqualTo("VALIDATION_ERROR");
        MvcResult ok = call(patch("/api/pos/menu/modifier-groups/" + g), "{\"name\":\"配料\",\"minSelect\":1,\"maxSelect\":5,\"allowDuplicate\":true}");
        assertThat(status(ok)).isEqualTo(200);
        assertThat(body(ok).get("name").asText()).isEqualTo("配料");
        assertThat(body(ok).get("minSelect").asInt()).isEqualTo(1);
        assertThat(body(ok).get("allowDuplicate").asBoolean()).isTrue();
        MvcResult nf = call(patch("/api/pos/menu/modifier-groups/999999"), "{\"name\":\"x\"}");
        assertThat(errorCode(nf)).isEqualTo("MODIFIER_GROUP_NOT_FOUND");
    }

    @Test
    void 刪群組連同選項軟刪除() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g = group(it, "加料", 0, 2, false);
        long m1 = modifier(g, "加蛋", 15);
        long m2 = modifier(g, "加麵", 20);
        assertThat(status(call(delete("/api/pos/menu/modifier-groups/" + g)))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM modifier_group WHERE id = ?", Object.class, g)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM modifier WHERE deleted_at IS NOT NULL AND id IN (?, ?)", Integer.class, m1, m2)).isEqualTo(2);
        assertThat(findItem(tree(), it).get("modifierGroups")).isEmpty();
        assertThat(errorCode(call(delete("/api/pos/menu/modifier-groups/" + g)))).isEqualTo("MODIFIER_GROUP_NOT_FOUND");
        assertThat(errorCode(call(patch("/api/pos/menu/modifiers/" + m1), "{\"name\":\"x\"}"))).isEqualTo("MODIFIER_NOT_FOUND");
    }

    @Test
    void 新增選項_加價0也允許() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g = group(it, "辣度", 0, 1, false);
        MvcResult r = call(post("/api/pos/menu/modifier-groups/" + g + "/modifiers"), "{\"name\":\"小辣\",\"priceDelta\":0}");
        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r).get("priceDelta").asLong()).isZero();
        assertThat(body(r).get("displayOrder").asInt()).isZero();
        assertThat(body(call(post("/api/pos/menu/modifier-groups/" + g + "/modifiers"), "{\"name\":\"大辣\",\"priceDelta\":10}")).get("displayOrder").asInt()).isEqualTo(1);
    }

    @Test
    void 選項加價規則與錯誤() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g = group(it, "加料", 0, 2, false);
        String url = "/api/pos/menu/modifier-groups/" + g + "/modifiers";
        for (String delta : new String[] {"-1", "10000000", "1.5"}) {
            assertThat(errorCode(call(post(url), "{\"name\":\"a\",\"priceDelta\":" + delta + "}"))).as(delta).isEqualTo("INVALID_PRICE");
        }
        assertThat(status(call(post(url), "{\"name\":\"a\",\"priceDelta\":9999999}"))).isEqualTo(201);
        assertThat(errorCode(call(post(url), "{\"name\":\"\",\"priceDelta\":1}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post(url), "{\"name\":\"a\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post("/api/pos/menu/modifier-groups/999999/modifiers"), "{\"name\":\"a\",\"priceDelta\":1}"))).isEqualTo("MODIFIER_GROUP_NOT_FOUND");
    }

    @Test
    void 選項加價變更寫稽核() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g = group(it, "加料", 0, 2, false);
        long m = modifier(g, "加蛋", 15);
        assertThat(status(call(patch("/api/pos/menu/modifiers/" + m), "{\"name\":\"加滷蛋\",\"priceDelta\":20}"))).isEqualTo(200);
        Map<String, Object> audit = jdbc.queryForMap("SELECT action, target_type, target_id, before_value, after_value FROM audit_log");
        assertThat(audit).containsEntry("action", "MODIFIER_PRICE_CHANGED").containsEntry("target_type", "modifier")
                .containsEntry("target_id", String.valueOf(m)).containsEntry("before_value", "15").containsEntry("after_value", "20");
        call(patch("/api/pos/menu/modifiers/" + m), "{\"name\":\"只改名\"}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(1);
        assertThat(errorCode(call(patch("/api/pos/menu/modifiers/" + m), "{}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(patch("/api/pos/menu/modifiers/" + m), "{\"priceDelta\":-2}"))).isEqualTo("INVALID_PRICE");
        assertThat(errorCode(call(patch("/api/pos/menu/modifiers/999999"), "{\"name\":\"x\"}"))).isEqualTo("MODIFIER_NOT_FOUND");
    }

    @Test
    void 刪除選項後從菜單消失() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g = group(it, "加料", 0, 2, false);
        long m = modifier(g, "加蛋", 15);
        assertThat(status(call(delete("/api/pos/menu/modifiers/" + m)))).isEqualTo(204);
        assertThat(findItem(tree(), it).get("modifierGroups").get(0).get("modifiers")).isEmpty();
        assertThat(errorCode(call(delete("/api/pos/menu/modifiers/" + m)))).isEqualTo("MODIFIER_NOT_FOUND");
    }

    // ---- 菜單樹 ----

    @Test
    void 菜單樹依顯示順序_含下架項目與完整欄位() throws Exception {
        long cat = category("麵食");
        long inactiveCat = category("停售分類");
        call(patch("/api/pos/menu/categories/" + inactiveCat), "{\"active\":false}");
        long it = item(cat, "牛肉麵", 150, "K1", true);
        item(cat, "停售麵", 90, "K1", false);
        long g = group(it, "加料", 0, 2, false);
        modifier(g, "加蛋", 15);
        modifier(g, "加麵", 20);
        JsonNode t = tree();
        assertThat(t.get("categories")).hasSize(2);
        assertThat(t.get("categories").get(1).get("active").asBoolean()).isFalse();
        JsonNode items = t.get("categories").get(0).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("name").asText()).isEqualTo("牛肉麵");
        assertThat(items.get(0).get("sellable").asBoolean()).isTrue();
        assertThat(items.get(1).get("sellable").asBoolean()).isFalse();
        JsonNode mods = items.get(0).get("modifierGroups").get(0).get("modifiers");
        assertThat(mods.get(0).get("name").asText()).isEqualTo("加蛋");
        assertThat(mods.get(1).get("priceDelta").asLong()).isEqualTo(20);
        List<String> itemFields = new java.util.ArrayList<>();
        items.get(0).fieldNames().forEachRemaining(itemFields::add);
        assertThat(itemFields).containsExactlyInAnyOrder("id", "categoryId", "name", "price", "station", "active",
                "soldOutBusinessDate", "soldOut", "sellable", "imageKey", "displayOrder", "modifierGroups");
    }
}
