package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import restaurant_pos.shared.model.Station;

/**
 * S5 驗收（卡 5.11）。完成條件：訂單測試全部通過（含 §4.6 出單併發保證）；兩批訂單、修改、取消皆可用。
 * 情境取自 S10 第 5～10 步（廚房以 KitchenItemService 代替尚未實作的 KDS API）。
 */
class S5AcceptanceTest extends OrderTestBase {

    @Autowired
    KitchenItemService kitchen;

    @Test
    void 兩批訂單_修改_取消() throws Exception {
        long s = openSession();

        // 第一批（顧客單於 S8 實作，這裡以店員送出代替）：牛肉麵 ×2（加麵）＋備註
        addDraft(s, beefId, 2, noodleId, 1);
        long firstOrder = body(submit(s, "req-1", "不要香菜")).get("orderId").asLong();
        JsonNode first = detail(s).get("orders").get(0);
        long beef = first.get("items").get(0).get("id").asLong();
        assertThat(first.get("batchNo").asInt()).isEqualTo(1);

        // 第二批：小菜 ×1
        addDraft(s, sideId, 1);
        JsonNode secondSubmit = body(submit(s, "req-2", null));
        assertThat(secondSubmit.get("batchNo").asInt()).isEqualTo(2);
        long side = detail(s).get("orders").get(1).get("items").get(0).get("id").asLong();

        // 廚房：牛肉麵開始製作再完成；小菜直接完成
        kitchen.start(Station.K1, beef, 0);
        kitchen.complete(Station.K1, beef, 1);
        kitchen.complete(Station.K2, side, 0);
        assertThat(detail(s).get("orders").get(0).get("status").asText()).isEqualTo("SERVED");

        // 已完成的小菜：修改或取消都失敗
        assertThat(errorCode(modify(side, 1, 2, ""))).isEqualTo("ORDER_ITEM_ALREADY_READY");
        assertThat(errorCode(cancelItem(side, PIN, "x"))).isEqualTo("ORDER_ITEM_ALREADY_READY");

        // 再送一項小菜（批次 3），以 PIN 取消
        addDraft(s, sideId, 1);
        assertThat(body(submit(s, "req-3", null)).get("batchNo").asInt()).isEqualTo(3);
        long third = detail(s).get("orders").get(2).get("items").get(0).get("id").asLong();
        assertThat(status(cancelItem(third, PIN, "客人不要了"))).isEqualTo(200);

        // 小計：只算未取消的正式單品項（牛肉麵 170×2 ＋ 小菜 60）
        JsonNode d = detail(s);
        assertThat(d.get("subtotal").asLong()).isEqualTo(400);
        assertThat(d.get("orders")).hasSize(3);
        assertThat(d.get("orders").get(2).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(d.get("orders").get(0).get("note").asText()).isEqualTo("不要香菜");
        assertThat(firstOrder).isEqualTo(d.get("orders").get(0).get("id").asLong());

        // 事件：3 次送出、2 次開始/完成（牛肉麵開始、牛肉麵完成、小菜完成）、1 次取消
        assertThat(orderEvents.events).filteredOn(e -> e.startsWith("SUBMITTED")).hasSize(3);
        assertThat(orderEvents.events).containsSubsequence("STARTED:" + beef, "COMPLETED:" + beef);
        assertThat(orderEvents.events).filteredOn(e -> e.startsWith("CANCELLED")).containsExactly("CANCELLED:" + third);
    }
}
