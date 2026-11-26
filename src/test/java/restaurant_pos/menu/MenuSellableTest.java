package restaurant_pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.model.Station;

/** 卡 3.4、3.5、3.8：售完與恢復、可售判斷與給訂單的快照、顧客菜單（01-PLAN S3）。 */
class MenuSellableTest extends MenuTestBase {

    @Autowired
    MenuQueryService menu;

    private boolean sellable(long itemId) throws Exception {
        return findItem(tree(), itemId).get("sellable").asBoolean();
    }

    private JsonNode customerMenu() throws Exception {
        MvcResult r = mvc.perform(get("/api/customer/menu")).andReturn();
        assertThat(status(r)).isEqualTo(200);
        return body(r);
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(expected);
    }

    // ---- 3.4 售完與恢復 ----

    @Test
    void 設為售完記錄今天營業日() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        MvcResult r = call(post("/api/pos/menu/items/" + it + "/sold-out"));
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("soldOut").asBoolean()).isTrue();
        assertThat(body(r).get("soldOutBusinessDate").asText()).isEqualTo("2026-03-02");
        assertThat(body(r).get("sellable").asBoolean()).isFalse();
        // 已售完時重複呼叫回目前狀態
        assertThat(body(call(post("/api/pos/menu/items/" + it + "/sold-out"))).get("soldOut").asBoolean()).isTrue();
    }

    @Test
    void 隔營業日自動恢復_售完日仍保留() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        call(post("/api/pos/menu/items/" + it + "/sold-out"));
        // 營業日在台北 04:00 切換：2026-03-02 的營業日到 2026-03-03 04:00（＝ UTC 03-02 20:00）才結束
        clock.set(Instant.parse("2026-03-02T19:59:59Z"));
        assertThat(findItem(tree(), it).get("soldOut").asBoolean()).isTrue();
        clock.set(Instant.parse("2026-03-02T20:00:00Z"));
        JsonNode next = findItem(tree(), it);
        assertThat(next.get("soldOut").asBoolean()).isFalse();
        assertThat(next.get("sellable").asBoolean()).isTrue();
        assertThat(next.get("soldOutBusinessDate").asText()).isEqualTo("2026-03-02");
    }

    @Test
    void 凌晨0到4點售完屬於前一個營業日() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        clock.set(Instant.parse("2026-03-01T18:00:00Z")); // 台北 03-02 02:00 → 營業日 03-01
        MvcResult r = call(post("/api/pos/menu/items/" + it + "/sold-out"));
        assertThat(body(r).get("soldOutBusinessDate").asText()).isEqualTo("2026-03-01");
        clock.set(Instant.parse("2026-03-01T20:00:00Z")); // 台北 04:00 → 已是下一個營業日
        assertThat(findItem(tree(), it).get("soldOut").asBoolean()).isFalse();
    }

    @Test
    void 可手動恢復供應() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        call(post("/api/pos/menu/items/" + it + "/sold-out"));
        MvcResult r = call(delete("/api/pos/menu/items/" + it + "/sold-out"));
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("soldOut").asBoolean()).isFalse();
        assertThat(body(r).get("soldOutBusinessDate").isNull()).isTrue();
        assertThat(body(r).get("sellable").asBoolean()).isTrue();
    }

    @Test
    void 重新上架不清除售完日() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        call(post("/api/pos/menu/items/" + it + "/sold-out"));
        call(patch("/api/pos/menu/items/" + it), "{\"active\":false}");
        MvcResult again = call(patch("/api/pos/menu/items/" + it), "{\"active\":true}");
        assertThat(body(again).get("soldOutBusinessDate").asText()).isEqualTo("2026-03-02");
        assertThat(body(again).get("soldOut").asBoolean()).isTrue();
        assertThat(body(again).get("sellable").asBoolean()).isFalse();
    }

    @Test
    void 售完與恢復的錯誤() throws Exception {
        assertThat(errorCode(call(post("/api/pos/menu/items/999999/sold-out")))).isEqualTo("MENU_ITEM_NOT_FOUND");
        assertThat(errorCode(call(delete("/api/pos/menu/items/999999/sold-out")))).isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    // ---- 3.5 可售判斷：四個條件各一 ----

    @Test
    void 可售條件一_品項必須上架() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", false);
        assertThat(sellable(it)).isFalse();
        call(patch("/api/pos/menu/items/" + it), "{\"active\":true}");
        assertThat(sellable(it)).isTrue();
    }

    @Test
    void 可售條件二_分類必須上架() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", false);
        call(patch("/api/pos/menu/categories/" + cat), "{\"active\":false}");
        call(patch("/api/pos/menu/items/" + it), "{\"active\":true}"); // 分類已下架時仍可把品項設為上架，但不可售
        assertThat(sellable(it)).isFalse();
        call(patch("/api/pos/menu/categories/" + cat), "{\"active\":true}");
        assertThat(sellable(it)).isTrue();
    }

    @Test
    void 可售條件三_售完日不能是今天() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        assertThat(sellable(it)).isTrue();
        call(post("/api/pos/menu/items/" + it + "/sold-out"));
        assertThat(sellable(it)).isFalse();
    }

    @Test
    void 可售條件四_每個群組的選項數不得少於最少可選() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g = group(it, "必選配料", 2, 3, false);
        assertThat(sellable(it)).isFalse(); // 0 個選項 < 2
        long m1 = modifier(g, "A", 0);
        assertThat(sellable(it)).isFalse(); // 1 個 < 2
        long m2 = modifier(g, "B", 0);
        assertThat(sellable(it)).isTrue(); // 2 個 ≥ 2
        call(delete("/api/pos/menu/modifiers/" + m2));
        assertThat(sellable(it)).isFalse(); // 已刪除的選項不算
        modifier(g, "C", 5);
        assertThat(sellable(it)).isTrue();
        assertThat(m1).isPositive();
    }

    @Test
    void 最少可選為0的群組即使沒有選項也不影響可售() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        group(it, "選配", 0, 2, false);
        assertThat(sellable(it)).isTrue();
    }

    @Test
    void 已刪除的品項不在菜單中() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", false);
        call(delete("/api/pos/menu/items/" + it));
        assertThat(findItem(tree(), it)).isNull();
    }

    // ---- 3.5 給訂單的快照 ----

    @Test
    void 可售品項快照只含未刪除的群組與選項() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", true);
        long g1 = group(it, "加料", 0, 2, false);
        long g2 = group(it, "已刪群組", 0, 1, false);
        long m1 = modifier(g1, "加蛋", 15);
        modifier(g1, "加麵", 20);
        long deleted = modifier(g1, "已刪選項", 5);
        modifier(g2, "不該出現", 1);
        call(delete("/api/pos/menu/modifiers/" + deleted));
        call(delete("/api/pos/menu/modifier-groups/" + g2));

        ItemSnapshot s = menu.sellableItem(it);
        assertThat(s.id()).isEqualTo(it);
        assertThat(s.name()).isEqualTo("牛肉麵");
        assertThat(s.price()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(s.station()).isEqualTo(Station.K1);
        assertThat(s.groups()).hasSize(1);
        assertThat(s.groups().get(0).id()).isEqualTo(g1);
        assertThat(s.groups().get(0).minSelect()).isZero();
        assertThat(s.groups().get(0).maxSelect()).isEqualTo(2);
        assertThat(s.groups().get(0).allowDuplicate()).isFalse();
        List<String> names = new ArrayList<>();
        s.groups().get(0).modifiers().forEach(m -> names.add(m.name()));
        assertThat(names).containsExactly("加蛋", "加麵");
        assertThat(s.groups().get(0).modifiers().get(0).id()).isEqualTo(m1);
        assertThat(s.groups().get(0).modifiers().get(0).priceDelta()).isEqualByComparingTo(new BigDecimal("15"));
    }

    @Test
    void 可售品項快照_不存在回找不到_不可售回不可售() throws Exception {
        assertCode(() -> menu.sellableItem(999999), ErrorCode.MENU_ITEM_NOT_FOUND);
        long cat = category("麵食");
        long inactive = item(cat, "下架品", 100, "K1", false);
        assertCode(() -> menu.sellableItem(inactive), ErrorCode.MENU_ITEM_NOT_SELLABLE);
        long soldOut = item(cat, "售完品", 100, "K1", true);
        call(post("/api/pos/menu/items/" + soldOut + "/sold-out"));
        assertCode(() -> menu.sellableItem(soldOut), ErrorCode.MENU_ITEM_NOT_SELLABLE);
        long needs = item(cat, "必選品", 100, "K1", true);
        group(needs, "必選", 1, 1, false);
        assertCode(() -> menu.sellableItem(needs), ErrorCode.MENU_ITEM_NOT_SELLABLE);
        long deleted = item(cat, "已刪品", 100, "K1", false);
        call(delete("/api/pos/menu/items/" + deleted));
        assertCode(() -> menu.sellableItem(deleted), ErrorCode.MENU_ITEM_NOT_FOUND);
        long inactiveCat = category("停售");
        long underInactive = item(inactiveCat, "品", 100, "K1", true);
        call(patch("/api/pos/menu/categories/" + inactiveCat), "{\"active\":false}");
        // 分類下有上架品項時不可下架，所以改為先把品項放進已下架分類的情境：直接改資料庫
        jdbc.update("UPDATE menu_category SET active = FALSE WHERE id = ?", inactiveCat);
        assertCode(() -> menu.sellableItem(underInactive), ErrorCode.MENU_ITEM_NOT_SELLABLE);
    }

    @Test
    void 可編輯品項只要求未刪除() throws Exception {
        long cat = category("麵食");
        long inactive = item(cat, "下架品", 100, "K2", false);
        long soldOut = item(cat, "售完品", 120, "K1", true);
        call(post("/api/pos/menu/items/" + soldOut + "/sold-out"));
        assertThat(menu.editableItem(inactive).station()).isEqualTo(Station.K2);
        assertThat(menu.editableItem(soldOut).price()).isEqualByComparingTo(new BigDecimal("120"));
        long deleted = item(cat, "已刪品", 100, "K1", false);
        call(delete("/api/pos/menu/items/" + deleted));
        assertCode(() -> menu.editableItem(deleted), ErrorCode.MENU_ITEM_NOT_FOUND);
        assertCode(() -> menu.editableItem(999999), ErrorCode.MENU_ITEM_NOT_FOUND);
    }

    // ---- 3.8 顧客菜單 ----

    @Test
    void 顧客菜單不需任何驗證() throws Exception {
        assertThat(status(mvc.perform(get("/api/customer/menu")).andReturn())).isEqualTo(200);
    }

    @Test
    void 顧客菜單只含上架分類中的可售品項() throws Exception {
        long active = category("麵食");
        long inactiveCat = category("停售分類");
        long onSale = item(active, "牛肉麵", 150, "K1", true);
        item(active, "下架品", 90, "K1", false);
        long soldOut = item(active, "售完品", 80, "K1", true);
        call(post("/api/pos/menu/items/" + soldOut + "/sold-out"));
        long hidden = item(inactiveCat, "停售分類的品", 70, "K1", false);
        call(patch("/api/pos/menu/categories/" + inactiveCat), "{\"active\":false}");
        call(patch("/api/pos/menu/items/" + hidden), "{\"active\":true}");

        JsonNode menu = customerMenu();
        assertThat(menu.get("categories")).hasSize(1);
        JsonNode cat = menu.get("categories").get(0);
        assertThat(cat.get("name").asText()).isEqualTo("麵食");
        assertThat(cat.get("items")).hasSize(1);
        assertThat(cat.get("items").get(0).get("id").asLong()).isEqualTo(onSale);
    }

    @Test
    void 空分類不出現() throws Exception {
        long onlyUnsellable = category("全下架");
        item(onlyUnsellable, "下架品", 10, "K1", false);
        category("沒有品項");
        long ok = category("麵食");
        item(ok, "牛肉麵", 150, "K1", true);
        JsonNode cats = customerMenu().get("categories");
        assertThat(cats).hasSize(1);
        assertThat(cats.get(0).get("name").asText()).isEqualTo("麵食");
    }

    @Test
    void 加價為0的選項也回傳_欄位不含內部資訊() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", true);
        long g = group(it, "辣度", 0, 1, false);
        modifier(g, "不辣", 0);
        modifier(g, "加辣", 10);
        JsonNode item = customerMenu().get("categories").get(0).get("items").get(0);
        List<String> fields = new ArrayList<>();
        item.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "name", "price", "imageKey", "modifierGroups");
        JsonNode mods = item.get("modifierGroups").get(0).get("modifiers");
        assertThat(mods).hasSize(2);
        assertThat(mods.get(0).get("name").asText()).isEqualTo("不辣");
        assertThat(mods.get(0).get("priceDelta").asLong()).isZero();
        assertThat(item.get("modifierGroups").get(0).get("minSelect").asInt()).isZero();
        assertThat(item.get("modifierGroups").get(0).get("maxSelect").asInt()).isEqualTo(1);
        assertThat(item.get("modifierGroups").get(0).get("allowDuplicate").asBoolean()).isFalse();
    }

    @Test
    void 顧客菜單依顯示順序() throws Exception {
        long c1 = category("甲");
        long c2 = category("乙");
        item(c1, "甲一", 10, "K1", true);
        item(c1, "甲二", 10, "K1", true);
        item(c2, "乙一", 10, "K1", true);
        call(patch("/api/pos/menu/categories/" + c1), "{\"displayOrder\":5}");
        call(patch("/api/pos/menu/categories/" + c2), "{\"displayOrder\":1}");
        JsonNode cats = customerMenu().get("categories");
        assertThat(cats.get(0).get("name").asText()).isEqualTo("乙");
        assertThat(cats.get(1).get("items").get(0).get("name").asText()).isEqualTo("甲一");
        assertThat(cats.get(1).get("items").get(1).get("name").asText()).isEqualTo("甲二");
    }

    @Test
    void 必選群組選項不足的品項不出現在顧客菜單() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "必選品", 100, "K1", true);
        long g = group(it, "必選", 1, 1, false);
        assertThat(customerMenu().get("categories")).isEmpty();
        modifier(g, "選項", 0);
        assertThat(customerMenu().get("categories")).hasSize(1);
    }

    @Test
    void 售完品隔營業日自動回到顧客菜單() throws Exception {
        long cat = category("麵食");
        long it = item(cat, "牛肉麵", 150, "K1", true);
        call(post("/api/pos/menu/items/" + it + "/sold-out"));
        assertThat(customerMenu().get("categories")).isEmpty();
        clock.set(Instant.parse("2026-03-02T20:00:00Z"));
        assertThat(customerMenu().get("categories")).hasSize(1);
    }
}
