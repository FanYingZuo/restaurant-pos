package restaurant_pos.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** 卡 4.2：開桌（01-PLAN S4、§4.6 併發保證）。 */
class OpenTableTest extends TableTestBase {

    @Autowired
    TableSessionService sessionService;

    @Autowired
    TransactionTemplate tx;

    private MvcResult openRaw(long tableId, String guests) throws Exception {
        return call(post("/api/pos/tables/" + tableId + "/open"), "{\"guestCount\":" + guests + "}");
    }

    private void cancelBySystem(long sessionId) {
        tx.executeWithoutResult(s -> {
            sessionService.lockById(sessionId);
            sessionService.cancel(sessionId);
        });
    }

    // ---- 回應 ----

    @Test
    void 開桌回傳完整欄位_且不含token() throws Exception {
        long t = table("A1");
        MvcResult r = openRaw(t, "4");
        assertThat(status(r)).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("tableId").asLong()).isEqualTo(t);
        assertThat(b.get("tableNo").asText()).isEqualTo("A1");
        assertThat(b.get("billNo").asText()).isEqualTo("001");
        assertThat(b.get("businessDate").asText()).isEqualTo("2026-03-02");
        assertThat(b.get("status").asText()).isEqualTo("OPEN");
        assertThat(b.get("occupiesTable").asBoolean()).isTrue();
        assertThat(b.get("guestCount").asInt()).isEqualTo(4);
        assertThat(b.get("openedAt").asText()).isEqualTo("2026-03-02T00:00:00Z");
        assertThat(b.get("qrExpiresAt").asText()).isEqualTo("2026-03-02T04:00:00Z");
        assertThat(b.get("qrExpired").asBoolean()).isFalse();
        assertThat(b.get("serviceChargeEnabled").asBoolean()).isTrue();
        assertThat(b.get("checkoutInProgress").asBoolean()).isFalse();
        assertThat(b.get("pendingRecheckout").asBoolean()).isFalse();
        List<String> fields = new ArrayList<>();
        b.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "tableId", "tableNo", "billNo", "businessDate", "status",
                "occupiesTable", "guestCount", "openedAt", "qrExpiresAt", "qrExpired", "serviceChargeEnabled",
                "checkoutInProgress", "pendingRecheckout");
        String token = jdbc.queryForObject("SELECT token FROM table_session", String.class);
        assertThat(r.getResponse().getContentAsString()).doesNotContain(token).doesNotContain("token");
    }

    @Test
    void token是32位元組隨機值_每筆不同() throws Exception {
        long a = table("A1");
        long b = table("A2");
        open(a, 2);
        open(b, 2);
        List<String> tokens = jdbc.queryForList("SELECT token FROM table_session", String.class);
        assertThat(tokens).hasSize(2).doesNotHaveDuplicates();
        for (String t : tokens) {
            assertThat(java.util.Base64.getUrlDecoder().decode(t)).hasSize(32);
        }
    }

    @Test
    void 開桌後桌位使用中並占桌() throws Exception {
        long t = table("A1");
        long id = open(t, 2).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT occupying_table_id FROM table_session WHERE id = ?", Long.class, id)).isEqualTo(t);
        assertThat(tableList().get(0).get("inUse").asBoolean()).isTrue();
    }

    // ---- 帳單號 ----

    @Test
    void 帳單號每營業日從1開始_同日遞增() throws Exception {
        assertThat(open(table("A1"), 1).get("billNo").asText()).isEqualTo("001");
        assertThat(open(table("A2"), 1).get("billNo").asText()).isEqualTo("002");
        assertThat(open(table("A3"), 1).get("billNo").asText()).isEqualTo("003");
    }

    @Test
    void 隔營業日重新從1開始() throws Exception {
        long a = table("A1");
        long id = open(a, 1).get("id").asLong();
        assertThat(open(table("A2"), 1).get("billNo").asText()).isEqualTo("002");
        cancelBySystem(id);
        clock.set(Instant.parse("2026-03-02T20:00:00Z")); // 台北 03-03 04:00 → 新營業日
        JsonNode next = open(a, 1);
        assertThat(next.get("billNo").asText()).isEqualTo("001");
        assertThat(next.get("businessDate").asText()).isEqualTo("2026-03-03");
    }

    @Test
    void 凌晨4點前開桌屬於前一營業日() throws Exception {
        clock.set(Instant.parse("2026-03-01T18:00:00Z")); // 台北 03-02 02:00
        assertThat(open(table("A1"), 1).get("businessDate").asText()).isEqualTo("2026-03-01");
    }

    @Test
    void 取消的帳單號不回收() throws Exception {
        long a = table("A1");
        long first = open(a, 1).get("id").asLong();
        cancelBySystem(first);
        assertThat(open(a, 1).get("billNo").asText()).isEqualTo("002");
    }

    @Test
    void 營業日與帳單號組合唯一() throws Exception {
        open(table("A1"), 1);
        open(table("A2"), 1);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO table_session (table_id, business_date, bill_no, status, guest_count, token, "
                        + "qr_expires_at, service_charge_enabled, checkout_in_progress, pending_recheckout, opened_at) "
                        + "VALUES (99, '2026-03-02', 1, 'CLOSED', 1, 'dup-bill-token', NOW(6), TRUE, FALSE, FALSE, NOW(6))"));
    }

    // ---- 設定 ----

    @Test
    void QR有效時數與服務費預設值來自設定() throws Exception {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('qr.validity.hours', '2', NOW(6))");
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('service_charge.default_enabled', 'false', NOW(6))");
        JsonNode b = open(table("A1"), 2);
        assertThat(b.get("qrExpiresAt").asText()).isEqualTo("2026-03-02T02:00:00Z");
        assertThat(b.get("serviceChargeEnabled").asBoolean()).isFalse();
    }

    // ---- 錯誤 ----

    @Test
    void 已使用的桌位回TABLE_OCCUPIED() throws Exception {
        long t = table("A1");
        open(t, 2);
        MvcResult r = openRaw(t, "2");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("TABLE_OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM table_session", Integer.class)).isEqualTo(1);
        assertThat(events.started).hasSize(1);
    }

    @Test
    void 不存在或已刪除的桌位回404() throws Exception {
        MvcResult r = openRaw(999999, "2");
        assertThat(status(r)).isEqualTo(404);
        assertThat(errorCode(r)).isEqualTo("TABLE_NOT_FOUND");
        long t = table("A1");
        layout(delete("/api/pos/tables/" + t), null);
        assertThat(errorCode(openRaw(t, "2"))).isEqualTo("TABLE_NOT_FOUND");
    }

    @Test
    void 人數必須是1到99() throws Exception {
        long t = table("A1");
        for (String bad : new String[] {"0", "100", "-1", "null", "\"abc\"", "2.5"}) {
            MvcResult r = openRaw(t, bad);
            assertThat(status(r)).as(bad).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(call(post("/api/pos/tables/" + t + "/open"), "{}").getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM table_session", Integer.class)).isZero();
        assertThat(status(openRaw(t, "1"))).isEqualTo(201);
        long u = table("A2");
        assertThat(status(openRaw(u, "99"))).isEqualTo(201);
    }

    // ---- 事件 ----

    @Test
    void 開桌發佈用餐開始事件() throws Exception {
        long id = open(table("A1"), 2).get("id").asLong();
        assertThat(events.started).containsExactly(id);
    }

    // ---- 併發保證 ----

    @Test
    void 同時開同一桌只有一個成功() throws Exception {
        long t = table("A1");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return openRaw(t, "2");
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            if (status(r) == 201) {
                created++;
            } else {
                assertThat(status(r)).isEqualTo(409);
                assertThat(errorCode(r)).isIn("TABLE_OCCUPIED", "CONCURRENT_MODIFICATION");
            }
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM table_session", Integer.class)).isEqualTo(1);
        assertThat(events.started).hasSize(1);
    }

    @Test
    void 新的一天同時開不同桌_帳單號不重複且連續() throws Exception {
        int threads = 6;
        List<Long> tables = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tables.add(table("T" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (long t : tables) {
            results.add(pool.submit(() -> {
                start.await();
                return openRaw(t, "2");
            }));
        }
        start.countDown();
        Set<String> bills = new HashSet<>();
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
            bills.add(body(r).get("billNo").asText());
        }
        pool.shutdown();
        assertThat(bills).containsExactlyInAnyOrder("001", "002", "003", "004", "005", "006");
    }
}
