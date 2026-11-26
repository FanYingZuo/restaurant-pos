package restaurant_pos.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S4 驗收（卡 4.5）。完成條件：桌位與用餐測試全部通過；開桌後可取得 QR 圖片。
 * 情境取自 S10 第 3、5 步：建立桌位 A1、開桌 A1（4 人）→ 帳單號 1、可取得 QR 圖片。
 */
class S4AcceptanceTest extends TableTestBase {

    @Test
    void 建立桌位_開桌_取得QR_改人數_看板顯示() throws Exception {
        MvcResult created = layout(post("/api/pos/tables"),
                "{\"tableNo\":\"A1\",\"capacity\":4,\"shape\":\"SQUARE\",\"width\":64,\"height\":64,\"x\":10,\"y\":20}");
        assertThat(status(created)).isEqualTo(201);
        long tableId = body(created).get("id").asLong();

        JsonNode session = open(tableId, 4);
        assertThat(session.get("billNo").asText()).isEqualTo("001");
        assertThat(session.get("guestCount").asInt()).isEqualTo(4);
        long sessionId = session.get("id").asLong();

        MvcResult qr = call(get("/api/pos/sessions/" + sessionId + "/qr"), null);
        assertThat(status(qr)).isEqualTo(200);
        assertThat(qr.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(qr.getResponse().getContentAsByteArray().length).isGreaterThan(100);

        assertThat(body(call(patch("/api/pos/sessions/" + sessionId + "/guest-count"), "{\"guestCount\":5}"))
                .get("guestCount").asInt()).isEqualTo(5);

        JsonNode board = tableList();
        assertThat(board).hasSize(1);
        assertThat(board.get(0).get("inUse").asBoolean()).isTrue();
        assertThat(board.get(0).get("session").get("guestCount").asInt()).isEqualTo(5);
        assertThat(events.started).containsExactly(sessionId);
        assertThat(body(call(get("/api/pos/sessions/pending-recheckout"), null))).isEmpty();
        assertThat(errorCode(call(post("/api/pos/tables/" + tableId + "/open"), "{\"guestCount\":2}"))).isEqualTo("TABLE_OCCUPIED");
    }
}
