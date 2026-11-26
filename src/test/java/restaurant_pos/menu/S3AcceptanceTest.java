package restaurant_pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import restaurant_pos.shared.model.Station;

/**
 * S3 驗收（卡 3.8）。完成條件：菜單測試全部通過；顧客菜單可取得。
 * 情境取自 S10 第 4 步：分類「麵食」、「牛肉麵」（150，K1）、選項群組「加料」（最少 0、最多 2、不可重複）、
 * 「加麵」（+20）、「加蛋」（+15）、「小菜」（60，K2）。
 */
class S3AcceptanceTest extends MenuTestBase {

    @Autowired
    MenuQueryService menu;

    @Test
    void S10第4步的菜單可建立_管理端與顧客端都看得到() throws Exception {
        long cat = category("麵食");
        long beef = item(cat, "牛肉麵", 150, "K1", true);
        long g = group(beef, "加料", 0, 2, false);
        long noodle = modifier(g, "加麵", 20);
        long egg = modifier(g, "加蛋", 15);
        long side = item(cat, "小菜", 60, "K2", true);

        JsonNode items = tree().get("categories").get(0).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("sellable").asBoolean()).isTrue();
        assertThat(items.get(1).get("station").asText()).isEqualTo("K2");

        JsonNode customer = body(mvc.perform(get("/api/customer/menu")).andReturn());
        JsonNode customerItems = customer.get("categories").get(0).get("items");
        assertThat(customerItems).hasSize(2);
        assertThat(customerItems.get(0).get("name").asText()).isEqualTo("牛肉麵");
        assertThat(customerItems.get(0).get("modifierGroups").get(0).get("modifiers")).hasSize(2);

        // 給訂單用的快照：價格、站別、群組與選項（依顯示順序）
        ItemSnapshot s = menu.sellableItem(beef);
        assertThat(s.price()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(s.station()).isEqualTo(Station.K1);
        assertThat(s.groups().get(0).modifiers()).extracting(ItemSnapshot.ModifierSnapshot::id).containsExactly(noodle, egg);
        assertThat(menu.sellableItem(side).station()).isEqualTo(Station.K2);
        assertThat(menu.sellableItem(side).groups()).isEmpty();
    }
}
