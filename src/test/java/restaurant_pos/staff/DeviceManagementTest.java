package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.7：裝置管理（DEVICE_MANAGE，01-PLAN S2、§4.5「裝置配對與憑證」）。 */
class DeviceManagementTest extends AbstractIntegrationTest {

    private TestDevice pos;
    private String token;
    private long managerId;

    @BeforeEach
    void setUp() {
        pos = registerDevice(DeviceType.POS);
        managerId = createEmployee("測試店長", "MANAGER", MANAGER_PIN);
        token = unlock(pos, UnlockFunction.DEVICE_MANAGE);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String unlockToken, String body) throws Exception {
        request = request.header("X-Device-Token", pos.token());
        if (unlockToken != null) {
            request = request.header("X-Unlock-Token", unlockToken);
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, token, body);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).get("code").asText();
    }

    private MvcResult create(String name, String type) throws Exception {
        return call(post("/api/staff/devices"), "{\"name\":\"" + name + "\",\"deviceType\":\"" + type + "\"}");
    }

    private MvcResult pair(String code) throws Exception {
        return mvc.perform(post("/api/pairing").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pairingCode\":\"" + code + "\"}")).andReturn();
    }

    // ---- 權限 ----

    @Test
    void 沒有解鎖或功能不符_所有裝置管理API都回FORBIDDEN() throws Exception {
        String wrongFunction = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        for (String t : new String[] {null, wrongFunction}) {
            assertThat(errorCode(call(get("/api/staff/devices"), t, null))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(post("/api/staff/devices"), t, "{\"name\":\"a\",\"deviceType\":\"KDS\"}"))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(post("/api/staff/devices/1/pairing-code"), t, null))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(post("/api/staff/devices/1/revoke"), t, null))).isEqualTo("FORBIDDEN");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Integer.class)).isEqualTo(1);
    }

    // ---- 建立 ----

    @Test
    void 建立裝置回傳一次性配對碼() throws Exception {
        MvcResult r = create("廚房平板", "KDS");
        assertThat(status(r)).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("pairingCode").asText()).matches("^[A-Z0-9]{8}$");
        assertThat(b.get("device").get("name").asText()).isEqualTo("廚房平板");
        assertThat(b.get("device").get("deviceType").asText()).isEqualTo("KDS");
        assertThat(b.get("device").get("paired").asBoolean()).isFalse();
        assertThat(b.get("pairingExpiresAt").asText()).isEqualTo("2026-03-02T00:10:00Z");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        MvcResult paired = pair(b.get("pairingCode").asText());
        assertThat(status(paired)).isEqualTo(200);
        assertThat(body(paired).get("deviceType").asText()).isEqualTo("KDS");
    }

    @Test
    void 建立時寫稽核_含操作的管理者與裝置() throws Exception {
        long newId = body(create("廚房平板", "KDS")).get("device").get("id").asLong();
        var row = jdbc.queryForMap("SELECT actor_employee_id, actor_device_id, target_id, after_value FROM audit_log WHERE action = 'DEVICE_CREATED'");
        assertThat(row.get("actor_employee_id")).isEqualTo(managerId);
        assertThat(row.get("actor_device_id")).isEqualTo(pos.id());
        assertThat(row.get("target_id")).isEqualTo(String.valueOf(newId));
        assertThat(row.get("after_value")).isEqualTo("KDS");
    }

    @Test
    void 建立的輸入驗證() throws Exception {
        assertThat(errorCode(create("", "POS"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(create("x".repeat(101), "POS"))).isEqualTo("VALIDATION_ERROR");
        assertThat(status(create("x".repeat(100), "POS"))).isEqualTo(201);
        assertThat(errorCode(create("平板", "TV"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post("/api/staff/devices"), "{\"name\":\"平板\"}"))).isEqualTo("VALIDATION_ERROR");
    }

    // ---- 列表 ----

    @Test
    void 列表不含憑證_雜湊或配對碼() throws Exception {
        String code = body(create("廚房平板", "KDS")).get("pairingCode").asText();
        MvcResult r = call(get("/api/staff/devices"), null);
        assertThat(status(r)).isEqualTo(200);
        JsonNode list = body(r);
        assertThat(list).hasSize(2);
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(code).doesNotContain(pos.token()).doesNotContain(hasher.deviceCredential(pos.token()))
                .doesNotContain("Hash").doesNotContain("credential").doesNotContain("pairingCode");
        JsonNode mine = list.get(0);
        assertThat(mine.get("paired").asBoolean()).isTrue();
        assertThat(mine.get("revokedAt").isNull()).isTrue();
        assertThat(list.get(1).get("paired").asBoolean()).isFalse();
    }

    @Test
    void 列表含已撤銷的裝置() throws Exception {
        long id = body(create("舊平板", "POS")).get("device").get("id").asLong();
        call(post("/api/staff/devices/" + id + "/revoke"), null);
        JsonNode list = body(call(get("/api/staff/devices"), null));
        assertThat(list.get(1).get("revokedAt").isNull()).isFalse();
    }

    // ---- 重新產生配對碼 ----

    @Test
    void 重新產生配對碼_舊碼立即失效() throws Exception {
        JsonNode created = body(create("廚房平板", "KDS"));
        long id = created.get("device").get("id").asLong();
        String oldCode = created.get("pairingCode").asText();
        MvcResult r = call(post("/api/staff/devices/" + id + "/pairing-code"), null);
        assertThat(status(r)).isEqualTo(200);
        String newCode = body(r).get("pairingCode").asText();
        assertThat(newCode).isNotEqualTo(oldCode);
        assertThat(status(pair(oldCode))).isEqualTo(403);
        assertThat(status(pair(newCode))).isEqualTo(200);
    }

    @Test
    void 重新產生配對碼的過期時間從現在起算() throws Exception {
        long id = body(create("廚房平板", "KDS")).get("device").get("id").asLong();
        clock.advance(Duration.ofMinutes(8));
        JsonNode b = body(call(post("/api/staff/devices/" + id + "/pairing-code"), null));
        assertThat(b.get("pairingExpiresAt").asText()).isEqualTo("2026-03-02T00:18:00Z");
    }

    @Test
    void 重新產生配對碼的錯誤() throws Exception {
        MvcResult notFound = call(post("/api/staff/devices/999999/pairing-code"), null);
        assertThat(status(notFound)).isEqualTo(404);
        assertThat(errorCode(notFound)).isEqualTo("NOT_FOUND");
        long id = body(create("舊平板", "POS")).get("device").get("id").asLong();
        call(post("/api/staff/devices/" + id + "/revoke"), null);
        assertThat(errorCode(call(post("/api/staff/devices/" + id + "/pairing-code"), null))).isEqualTo("NOT_FOUND");
    }

    // ---- 撤銷 ----

    @Test
    void 撤銷立即生效() throws Exception {
        JsonNode created = body(create("廚房平板", "KDS"));
        String kdsToken = body(pair(created.get("pairingCode").asText())).get("deviceToken").asText();
        assertThat(status(mvc.perform(get("/api/kds/test-ping").header("X-Device-Token", kdsToken)).andReturn())).isEqualTo(200);
        long id = created.get("device").get("id").asLong();
        MvcResult r = call(post("/api/staff/devices/" + id + "/revoke"), null);
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("revokedAt").isNull()).isFalse();
        MvcResult after = mvc.perform(get("/api/kds/test-ping").header("X-Device-Token", kdsToken)).andReturn();
        assertThat(status(after)).isEqualTo(401);
        assertThat(errorCode(after)).isEqualTo("DEVICE_TOKEN_INVALID");
    }

    @Test
    void 不可撤銷自己正在用的裝置() throws Exception {
        MvcResult r = call(post("/api/staff/devices/" + pos.id() + "/revoke"), null);
        assertThat(status(r)).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("DEVICE_SELF_REVOKE");
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM device WHERE id = ?", Object.class, pos.id())).isNull();
        assertThat(status(mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", pos.token())).andReturn())).isEqualTo(200);
    }

    @Test
    void 撤銷其他POS也可以() throws Exception {
        TestDevice other = registerDevice(DeviceType.POS);
        assertThat(status(call(post("/api/staff/devices/" + other.id() + "/revoke"), null))).isEqualTo(200);
        assertThat(status(mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", other.token())).andReturn())).isEqualTo(401);
    }

    @Test
    void 重複撤銷是冪等的_不重複寫稽核() throws Exception {
        long id = body(create("舊平板", "POS")).get("device").get("id").asLong();
        call(post("/api/staff/devices/" + id + "/revoke"), null);
        assertThat(status(call(post("/api/staff/devices/" + id + "/revoke"), null))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'DEVICE_REVOKED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 撤銷不存在的裝置回404() throws Exception {
        assertThat(errorCode(call(post("/api/staff/devices/999999/revoke"), null))).isEqualTo("NOT_FOUND");
    }

    @Test
    void 撤銷時清除尚未使用的配對碼() throws Exception {
        JsonNode created = body(create("舊平板", "POS"));
        call(post("/api/staff/devices/" + created.get("device").get("id").asLong() + "/revoke"), null);
        assertThat(status(pair(created.get("pairingCode").asText()))).isEqualTo(403);
    }

    @Test
    void 撤銷寫稽核() throws Exception {
        long id = body(create("舊平板", "POS")).get("device").get("id").asLong();
        call(post("/api/staff/devices/" + id + "/revoke"), null);
        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log ORDER BY id", String.class);
        assertThat(actions).contains("ADMIN_UNLOCKED", "DEVICE_CREATED", "DEVICE_REVOKED");
        var row = jdbc.queryForMap("SELECT actor_employee_id, actor_device_id, target_id FROM audit_log WHERE action = 'DEVICE_REVOKED'");
        assertThat(row.get("actor_employee_id")).isEqualTo(managerId);
        assertThat(row.get("target_id")).isEqualTo(String.valueOf(id));
    }
}
