package restaurant_pos.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.config.PosProperties;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.time.BusinessDayService;
import org.springframework.context.ApplicationEventPublisher;

/** 卡 4.3、4.4：QR 圖片 API（ZXing）、修改人數與服務費（01-PLAN S4）。 */
class QrAndGuestTest extends TableTestBase {

    @Autowired
    DiningTableRepository tableRepo;

    @Autowired
    TableSessionRepository sessionRepo;

    @Autowired
    DailySequenceService sequences;

    @Autowired
    BusinessDayService time;

    @Autowired
    AppSettingService settings;

    @Autowired
    AuditService audit;

    @Autowired
    PinVerifier pinVerifier;

    @Autowired
    QrImageService qrService;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    private long sessionOf(String tableNo, int guests) throws Exception {
        return open(table(tableNo), guests).get("id").asLong();
    }

    private static String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    }

    private String tokenOf(long sessionId) {
        return jdbc.queryForObject("SELECT token FROM table_session WHERE id = ?", String.class, sessionId);
    }

    // ---- 4.3 QR 圖片 ----

    @Test
    void QR圖片的網址由基底網址_訂單路徑_用餐紀錄編號_token組成() throws Exception {
        long id = sessionOf("A1", 2);
        MvcResult r = call(get("/api/pos/sessions/" + id + "/qr"), null);
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        byte[] png = r.getResponse().getContentAsByteArray();
        assertThat(png[1]).isEqualTo((byte) 'P'); // PNG 檔頭
        // 開發環境基底網址預設 http://localhost:8080
        assertThat(decode(png)).isEqualTo("http://localhost:8080/customer/order/" + id + "#t=" + tokenOf(id));
    }

    @Test
    void 基底網址的結尾斜線會去除() throws Exception {
        long id = sessionOf("A1", 2);
        for (String base : new String[] {"https://pos.example.test/", "https://pos.example.test///", "https://pos.example.test"}) {
            TableSessionService custom = new TableSessionService(tableRepo, sessionRepo, sequences, time, settings, audit,
                    pinVerifier, qrService, new PosProperties(base, null, null, null, "images"), publisher, tx);
            TableSession s = sessionRepo.findById(id).orElseThrow();
            assertThat(custom.qrUrl(s)).as(base).isEqualTo("https://pos.example.test/customer/order/" + id + "#t=" + tokenOf(id));
        }
    }

    @Test
    void 只有使用中可取得QR() throws Exception {
        long id = sessionOf("A1", 2);
        for (String status : new String[] {"CLOSED", "CANCELLED"}) {
            jdbc.update("UPDATE table_session SET status = ? WHERE id = ?", status, id);
            MvcResult r = call(get("/api/pos/sessions/" + id + "/qr"), null);
            assertThat(status(r)).as(status).isEqualTo(409);
            assertThat(errorCode(r)).isEqualTo("SESSION_NOT_OPEN");
        }
    }

    @Test
    void QR過期後仍可取得圖片_過期只影響顧客送單() throws Exception {
        long id = sessionOf("A1", 2);
        clock.set(Instant.parse("2026-03-02T05:00:00Z")); // 超過開桌＋4 小時
        assertThat(status(call(get("/api/pos/sessions/" + id + "/qr"), null))).isEqualTo(200);
        assertThat(summaryOf(id).get("qrExpired").asBoolean()).isTrue();
    }

    @Test
    void QR圖片需要裝置憑證_且找不到回404() throws Exception {
        long id = sessionOf("A1", 2);
        assertThat(status(mvc.perform(get("/api/pos/sessions/" + id + "/qr")).andReturn())).isEqualTo(401);
        MvcResult nf = call(get("/api/pos/sessions/999999/qr"), null);
        assertThat(status(nf)).isEqualTo(404);
        assertThat(errorCode(nf)).isEqualTo("SESSION_NOT_FOUND");
    }

    // ---- 4.4 修改人數 ----

    private com.fasterxml.jackson.databind.JsonNode summaryOf(long id) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode t : tableList()) {
            if (!t.get("session").isNull() && t.get("session").get("id").asLong() == id) {
                return t.get("session");
            }
        }
        throw new AssertionError("找不到用餐紀錄 " + id);
    }

    private MvcResult guests(long id, String value) throws Exception {
        return call(patch("/api/pos/sessions/" + id + "/guest-count"), "{\"guestCount\":" + value + "}");
    }

    @Test
    void 修改人數() throws Exception {
        long id = sessionOf("A1", 2);
        MvcResult r = guests(id, "6");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("guestCount").asInt()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT guest_count FROM table_session WHERE id = ?", Integer.class, id)).isEqualTo(6);
    }

    @Test
    void 人數必須1到99() throws Exception {
        long id = sessionOf("A1", 2);
        for (String bad : new String[] {"0", "100", "-3", "null", "\"x\"", "1.5"}) {
            MvcResult r = guests(id, bad);
            assertThat(status(r)).as(bad).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(status(guests(id, "1"))).isEqualTo(200);
        assertThat(status(guests(id, "99"))).isEqualTo(200);
    }

    @Test
    void 只有使用中且非結帳中可修改人數() throws Exception {
        long id = sessionOf("A1", 2);
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", id);
        MvcResult checkout = guests(id, "3");
        assertThat(status(checkout)).isEqualTo(409);
        assertThat(errorCode(checkout)).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, pending_recheckout = TRUE WHERE id = ?", id);
        assertThat(errorCode(guests(id, "3"))).isEqualTo("SESSION_PENDING_RECHECKOUT");
        jdbc.update("UPDATE table_session SET pending_recheckout = FALSE, status = 'CLOSED' WHERE id = ?", id);
        assertThat(errorCode(guests(id, "3"))).isEqualTo("SESSION_NOT_OPEN");
        jdbc.update("UPDATE table_session SET status = 'CANCELLED' WHERE id = ?", id);
        assertThat(errorCode(guests(id, "3"))).isEqualTo("SESSION_NOT_OPEN");
        assertThat(errorCode(guests(999999, "3"))).isEqualTo("SESSION_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT guest_count FROM table_session WHERE id = ?", Integer.class, id)).isEqualTo(2);
    }

    // ---- 4.4 服務費開關（PIN＋稽核） ----

    private MvcResult serviceCharge(long id, boolean enabled, String pin) throws Exception {
        return call(put("/api/pos/sessions/" + id + "/service-charge"), "{\"enabled\":" + enabled + ",\"pin\":\"" + pin + "\"}");
    }

    @Test
    void 服務費開關需PIN_成功寫稽核() throws Exception {
        long employee = createEmployee("小明", "STAFF", "1234");
        long id = sessionOf("A1", 2);
        MvcResult r = serviceCharge(id, false, "1234");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("serviceChargeEnabled").asBoolean()).isFalse();
        Map<String, Object> audit = jdbc.queryForMap("SELECT action, target_type, target_id, before_value, after_value, "
                + "actor_employee_id, actor_device_id FROM audit_log WHERE action = 'SERVICE_CHARGE_CHANGED'");
        assertThat(audit).containsEntry("target_type", "table_session").containsEntry("target_id", String.valueOf(id))
                .containsEntry("before_value", "true").containsEntry("after_value", "false")
                .containsEntry("actor_employee_id", employee).containsEntry("actor_device_id", pos.id());
        assertThat(r.getResponse().getContentAsString()).doesNotContain("1234");
    }

    @Test
    void 值沒變就不寫稽核() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        long id = sessionOf("A1", 2);
        assertThat(status(serviceCharge(id, true, "1234"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SERVICE_CHARGE_CHANGED'", Integer.class)).isZero();
    }

    @Test
    void 錯誤PIN不改變服務費() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        long id = sessionOf("A1", 2);
        MvcResult r = serviceCharge(id, false, "0000");
        assertThat(status(r)).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("INVALID_PIN");
        assertThat(jdbc.queryForObject("SELECT service_charge_enabled FROM table_session WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(errorCode(serviceCharge(id, false, "12"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(put("/api/pos/sessions/" + id + "/service-charge"), "{\"pin\":\"1234\"}"))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 先檢查狀態之後才驗PIN_避免用錯誤PIN探測資料狀態() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        long id = sessionOf("A1", 2);
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE WHERE id = ?", id);
        assertThat(errorCode(serviceCharge(id, false, "0000"))).isEqualTo("CHECKOUT_IN_PROGRESS");
        jdbc.update("UPDATE table_session SET checkout_in_progress = FALSE, status = 'CLOSED' WHERE id = ?", id);
        assertThat(errorCode(serviceCharge(id, false, "0000"))).isEqualTo("SESSION_NOT_OPEN");
        assertThat(errorCode(serviceCharge(999999, false, "0000"))).isEqualTo("SESSION_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SERVICE_CHARGE_CHANGED'", Integer.class)).isZero();
    }

    @Test
    void PIN被鎖定時回PIN_LOCKED() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        long id = sessionOf("A1", 2);
        for (int i = 0; i < 5; i++) {
            serviceCharge(id, false, "0000");
        }
        MvcResult r = serviceCharge(id, false, "1234");
        assertThat(status(r)).isEqualTo(429);
        assertThat(errorCode(r)).isEqualTo("PIN_LOCKED");
    }

    @Test
    void 服務費開關也受結帳中與待重新結帳限制() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        long id = sessionOf("A1", 2);
        jdbc.update("UPDATE table_session SET pending_recheckout = TRUE WHERE id = ?", id);
        assertThat(errorCode(serviceCharge(id, false, "1234"))).isEqualTo("SESSION_PENDING_RECHECKOUT");
    }

    @Test
    void 只有POS可以呼叫() throws Exception {
        long id = sessionOf("A1", 2);
        TestDevice kds = registerDevice(DeviceType.KDS);
        MvcResult r = mvc.perform(patch("/api/pos/sessions/" + id + "/guest-count").header("X-Device-Token", kds.token())
                .contentType("application/json").content("{\"guestCount\":3}")).andReturn();
        assertThat(errorCode(r)).isEqualTo("DEVICE_TYPE_FORBIDDEN");
    }
}
