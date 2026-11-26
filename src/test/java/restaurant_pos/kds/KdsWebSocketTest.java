package restaurant_pos.kds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.order.OrderTestBase;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.support.RecordingPusher;
import restaurant_pos.support.WsTestClient;

/**
 * 卡 6.3、6.4：WebSocket 端點與連線驗證、推播監聽器（01-PLAN §4.5、§4.7、S6）。
 * 用真正的 STOMP 用戶端連到隨機埠的伺服器。
 */
class KdsWebSocketTest extends OrderTestBase {

    @LocalServerPort
    int port;

    @Autowired
    RecordingPusher recording;

    private TestDevice kds;
    private final List<WsTestClient> clients = new ArrayList<>();

    @BeforeEach
    void setUpWs() {
        kds = registerDevice(DeviceType.KDS);
        recording.clear();
    }

    @AfterEach
    void closeClients() {
        clients.forEach(WsTestClient::close);
    }

    private WsTestClient connect(String token) {
        WsTestClient c = WsTestClient.connect(port, token, WsTestClient.DEFAULT_ORIGIN);
        clients.add(c);
        return c;
    }

    private WsTestClient connected(String token) throws Exception {
        WsTestClient c = connect(token);
        c.connected.get(10, TimeUnit.SECONDS);
        return c;
    }

    private WsTestClient subscribed(String station) throws Exception {
        WsTestClient c = connected(kds.token());
        c.subscribe("/topic/kds/" + station);
        Thread.sleep(300); // 等訂閱生效
        return c;
    }

    private Map<String, Object> next(WsTestClient c) throws Exception {
        Map<String, Object> m = c.next(Duration.ofSeconds(5));
        assertThat(m).as("5 秒內沒有收到推播").isNotNull();
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemOf(Map<String, Object> message) {
        return (Map<String, Object>) message.get("item");
    }

    private void assertSilent(WsTestClient c) throws Exception {
        assertThat(c.next(Duration.ofMillis(800))).as("不應該收到推播").isNull();
    }

    // ---- 6.3 連線驗證 ----

    @Test
    void KDS憑證可以連線() throws Exception {
        WsTestClient c = connected(kds.token());
        assertThat(c.isConnected()).isTrue();
    }

    @Test
    void POS憑證也可以連線() throws Exception {
        assertThat(connected(this.pos.token()).isConnected()).isTrue();
    }

    @Test
    void 沒有憑證或憑證無效立即被拒() throws Exception {
        for (String token : new String[] {null, "", "garbage", "x".repeat(500)}) {
            WsTestClient c = connect(token);
            String failure = c.failure.get(10, TimeUnit.SECONDS);
            assertThat(failure).as(String.valueOf(token)).isNotBlank();
            assertThat(c.connected.isDone() && !c.connected.isCompletedExceptionally()).isFalse();
        }
    }

    @Test
    void 已撤銷的憑證連線被拒() throws Exception {
        jdbc.update("UPDATE device SET revoked_at = NOW(6) WHERE id = ?", kds.id());
        WsTestClient c = connect(kds.token());
        assertThat(c.failure.get(10, TimeUnit.SECONDS)).isNotBlank();
        assertThat(c.connected.isDone()).isFalse();
    }

    @Test
    void 憑證放網址參數無效_只認CONNECT帧標頭() throws Exception {
        WsTestClient c = WsTestClient.connect(port, null, WsTestClient.DEFAULT_ORIGIN);
        clients.add(c);
        assertThat(c.failure.get(10, TimeUnit.SECONDS)).isNotBlank();
    }

    @Test
    void 只允許公開基底網址的來源() throws Exception {
        WsTestClient evil = WsTestClient.connect(port, kds.token(), "http://evil.example");
        clients.add(evil);
        assertThat(evil.failure.get(10, TimeUnit.SECONDS)).isNotBlank();
        assertThat(evil.connected.isDone()).isFalse();
    }

    @Test
    void 伺服器心跳預設25秒_測試環境縮短() throws Exception {
        assertThat(WebSocketConfig.DEFAULT_HEARTBEAT_MS).isEqualTo(25_000);
        WsTestClient c = connected(kds.token());
        // 測試環境設定為 1000 毫秒；伺服器送出、不要求用戶端送
        assertThat(c.connected.get().getFirst("heart-beat")).isEqualTo("1000,0");
    }

    @Test
    void 撤銷後下次心跳斷線() throws Exception {
        WsTestClient c = connected(kds.token());
        c.subscribe("/topic/kds/K1");
        assertThat(c.isConnected()).isTrue();
        jdbc.update("UPDATE device SET revoked_at = NOW(6) WHERE id = ?", kds.id());
        c.closed.get(15, TimeUnit.SECONDS);
        assertThat(c.isConnected()).isFalse();
    }

    @Test
    void 未撤銷的連線不會被誤斷() throws Exception {
        WsTestClient c = connected(kds.token());
        Thread.sleep(3500); // 超過數個心跳週期
        assertThat(c.isConnected()).isTrue();
    }

    @Test
    void 只能訂閱兩個站別主題() throws Exception {
        WsTestClient ok = connected(kds.token());
        ok.subscribe("/topic/kds/K1");
        ok.subscribe("/topic/kds/K2");
        Thread.sleep(300);
        assertThat(ok.isConnected()).isTrue();

        for (String bad : new String[] {"/topic/other", "/topic/kds/K3", "/topic/kds/", "/queue/x", "/topic/kds/K1/extra"}) {
            WsTestClient c = connected(kds.token());
            c.subscribe(bad);
            assertThat(c.failure.get(10, TimeUnit.SECONDS)).as(bad).isNotBlank();
        }
    }

    @Test
    void 用戶端傳入的訊息一律忽略() throws Exception {
        WsTestClient listener = subscribed("K1");
        WsTestClient sender = connected(kds.token());
        sender.send("/topic/kds/K1", Map.of("type", "ITEM_REMOVE", "orderItemId", 1));
        sender.send("/app/anything", Map.of("x", 1));
        assertSilent(listener);
        assertThat(sender.isConnected()).isTrue();
    }

    // ---- 6.4 推播監聽器 ----

    @Test
    void 訂單送出_各站收到各自品項的新增() throws Exception {
        WsTestClient k1 = subscribed("K1");
        WsTestClient k2 = subscribed("K2");
        long s = openSession();
        submitStandardOrder(s, "r1");
        Map<String, Object> m1 = next(k1);
        assertThat(m1.get("type")).isEqualTo("ITEM_UPSERT");
        assertThat(itemOf(m1).get("name")).isEqualTo("牛肉麵");
        assertThat(itemOf(m1).get("status")).isEqualTo("PENDING");
        assertThat(itemOf(m1).get("tableNo")).isEqualTo("T1");
        assertThat(itemOf(m1).get("batchNo")).isEqualTo(1);
        Map<String, Object> m2 = next(k2);
        assertThat(m2.get("type")).isEqualTo("ITEM_UPSERT");
        assertThat(itemOf(m2).get("name")).isEqualTo("小菜");
        assertSilent(k1); // K1 不會收到小菜
    }

    @Test
    void 開始製作_推新增或更新_完成_推移除() throws Exception {
        long s = openSession();
        submitStandardOrder(s, "r1");
        WsTestClient k1 = subscribed("K1");
        long beef = jdbc.queryForObject("SELECT id FROM order_item WHERE name = '牛肉麵'", Long.class);
        call(post("/api/kds/stations/K1/items/" + beef + "/start"), "{\"expectedVersion\":0}", kds);
        Map<String, Object> started = next(k1);
        assertThat(started.get("type")).isEqualTo("ITEM_UPSERT");
        assertThat(itemOf(started).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(itemOf(started).get("version")).isEqualTo(1);

        call(post("/api/kds/stations/K1/items/" + beef + "/complete"), "{\"expectedVersion\":1}", kds);
        Map<String, Object> removed = next(k1);
        assertThat(removed.get("type")).isEqualTo("ITEM_REMOVE");
        assertThat(((Number) removed.get("orderItemId")).longValue()).isEqualTo(beef);
    }

    @Test
    void 品項修改_推更新() throws Exception {
        long s = openSession();
        var order = submitStandardOrder(s, "r1");
        long beef = itemIdByName(order, "牛肉麵");
        WsTestClient k1 = subscribed("K1");
        modify(beef, 0, 3, "少辣", eggId, 1);
        Map<String, Object> m = next(k1);
        assertThat(m.get("type")).isEqualTo("ITEM_UPSERT");
        assertThat(itemOf(m).get("quantity")).isEqualTo(3);
        assertThat(itemOf(m).get("note")).isEqualTo("少辣");
        assertThat(itemOf(m).get("version")).isEqualTo(1);
    }

    @Test
    void 品項取消_推移除() throws Exception {
        long s = openSession();
        var order = submitStandardOrder(s, "r1");
        long beef = itemIdByName(order, "牛肉麵");
        WsTestClient k1 = subscribed("K1");
        cancelItem(beef, PIN, "客人不要了");
        Map<String, Object> m = next(k1);
        assertThat(m.get("type")).isEqualTo("ITEM_REMOVE");
        assertThat(((Number) m.get("orderItemId")).longValue()).isEqualTo(beef);
    }

    @Test
    void 取消整單_每項各推一則移除() throws Exception {
        long s = openSession();
        var order = submitStandardOrder(s, "r1");
        WsTestClient k1 = subscribed("K1");
        call(post("/api/pos/orders/" + order.get("id").asLong() + "/cancel"), pinReason(PIN, "全取消"));
        List<Long> removed = new ArrayList<>();
        for (int i = 0; i < 2; i++) { // 兩個品項、各自送到兩站，K1 會收到兩則（牛肉麵、小菜）
            Map<String, Object> m = next(k1);
            assertThat(m.get("type")).isEqualTo("ITEM_REMOVE");
            removed.add(((Number) m.get("orderItemId")).longValue());
        }
        assertThat(removed).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void 用餐取消_兩站整板重新載入() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        WsTestClient k1 = subscribed("K1");
        WsTestClient k2 = subscribed("K2");
        call(post("/api/pos/sessions/" + s + "/cancel"), pinReason(PIN, "客人沒來"));
        assertThat(next(k1).get("type")).isEqualTo("BOARD_RELOAD");
        assertThat(next(k2).get("type")).isEqualTo("BOARD_RELOAD");
    }

    @Test
    void 草稿操作不推播() throws Exception {
        WsTestClient k1 = subscribed("K1");
        long s = openSession();
        addDraft(s, beefId, 1);
        assertSilent(k1);
    }

    @Test
    void 讀不到就改送移除_修改後已不在清單的品項() throws Exception {
        long s = openSession();
        var order = submitStandardOrder(s, "r1");
        long beef = itemIdByName(order, "牛肉麵");
        WsTestClient k1 = subscribed("K1");
        // 修改的同一個交易之後，品項因用餐紀錄被取消而不在清單上：模擬為先把用餐紀錄標成已取消
        jdbc.update("UPDATE table_session SET status = 'CANCELLED' WHERE id = ?", s);
        kitchenEventOnly(beef);
        Map<String, Object> m = next(k1);
        assertThat(m.get("type")).isEqualTo("ITEM_REMOVE");
        assertThat(((Number) m.get("orderItemId")).longValue()).isEqualTo(beef);
    }

    @Autowired
    org.springframework.context.ApplicationEventPublisher publisher;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    private void kitchenEventOnly(long orderItemId) {
        tx.executeWithoutResult(st -> publisher.publishEvent(new restaurant_pos.order.ItemStartedEvent(orderItemId, 0)));
    }

    @Test
    void 推播失敗只記日誌_不影響API結果() throws Exception {
        long s = openSession();
        addDraft(s, beefId, 1);
        recording.failNextPush();
        MvcResult r = submit(s, "r1", null);
        assertThat(status(r)).isEqualTo(201);
        assertThat(detail(s).get("orders")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE batch_no IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void 推播訊息格式符合合約() throws Exception {
        WsTestClient k1 = subscribed("K1");
        long s = openSession();
        addDraft(s, beefId, 1, noodleId, 1);
        submit(s, "r1", "備註");
        Map<String, Object> m = next(k1);
        assertThat(m.keySet()).containsExactlyInAnyOrder("type", "item");
        assertThat(itemOf(m).keySet()).containsExactlyInAnyOrder("orderItemId", "orderId", "sessionId", "tableNo",
                "batchNo", "name", "quantity", "note", "modifiers", "status", "version", "submittedAt", "orderNote");
    }

    // 帶指定裝置的 POST（KDS 路徑只允許 KDS 憑證）
    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body,
            TestDevice device) throws Exception {
        return mvc.perform(request.header("X-Device-Token", device.token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }
}
