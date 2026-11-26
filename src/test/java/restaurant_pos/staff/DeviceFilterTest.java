package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 0.10：device 表、憑證雜湊、裝置憑證過濾器（01-PLAN §4.5）。 */
class DeviceFilterTest extends AbstractIntegrationTest {

    private static final List<String> STORE_PATHS = List.of("/api/pos/test-ping", "/api/kds/test-ping", "/api/staff/test-ping");

    @Autowired
    ObjectMapper objectMapper;

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 比較錯誤回應時忽略時間戳。 */
    private JsonNode errorOf(MvcResult r) throws Exception {
        return objectMapper.readTree(body(r));
    }

    // ---- 無憑證、無效、已撤銷 ----

    @Test
    void 沒有憑證回401() throws Exception {
        for (String path : STORE_PATHS) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("DEVICE_TOKEN_INVALID"));
        }
    }

    @Test
    void 空白或亂碼或過長的憑證回401而不是500() throws Exception {
        for (String token : new String[] {"", "   ", "garbage", "x".repeat(10_000)}) {
            mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("DEVICE_TOKEN_INVALID"));
        }
    }

    @Test
    void 已撤銷的憑證回401() throws Exception {
        TestDevice device = registerDevice(DeviceType.POS);
        mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", device.token())).andExpect(status().isOk());
        jdbc.update("UPDATE device SET revoked_at = NOW(6) WHERE id = ?", device.id());
        mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", device.token()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("DEVICE_TOKEN_INVALID"));
    }

    @Test
    void 不存在與已撤銷的錯誤回應完全相同_不洩漏差異() throws Exception {
        TestDevice revoked = registerDevice(DeviceType.POS);
        jdbc.update("UPDATE device SET revoked_at = NOW(6) WHERE id = ?", revoked.id());
        JsonNode a = errorOf(mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", revoked.token())).andReturn());
        JsonNode b = errorOf(mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", "never-existed")).andReturn());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void 錯誤回應不含憑證() throws Exception {
        String token = "very-recognizable-fake-token-value";
        MvcResult r = mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", token)).andReturn();
        assertThat(body(r)).doesNotContain(token);
    }

    // ---- 裝置類型限制 ----

    @Test
    void POS憑證可用於pos與staff_不可用於kds() throws Exception {
        TestDevice pos = registerDevice(DeviceType.POS);
        mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", pos.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("POS"))
                .andExpect(jsonPath("$.deviceId").value(pos.id()));
        mvc.perform(get("/api/staff/test-ping").header("X-Device-Token", pos.token())).andExpect(status().isOk());
        mvc.perform(get("/api/kds/test-ping").header("X-Device-Token", pos.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEVICE_TYPE_FORBIDDEN"));
    }

    @Test
    void KDS憑證只能用於kds_呼叫POS_API被拒() throws Exception {
        TestDevice kds = registerDevice(DeviceType.KDS);
        mvc.perform(get("/api/kds/test-ping").header("X-Device-Token", kds.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("KDS"));
        for (String path : List.of("/api/pos/test-ping", "/api/staff/test-ping")) {
            mvc.perform(get(path).header("X-Device-Token", kds.token()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEVICE_TYPE_FORBIDDEN"));
        }
    }

    @Test
    void 非店內路徑不需要裝置憑證() throws Exception {
        mvc.perform(get("/api/test/errors/param").param("n", "1")).andExpect(status().isOk());
    }

    // ---- 憑證雜湊 ----

    @Test
    void 資料庫只存HMAC雜湊_不存明碼() throws Exception {
        TestDevice device = registerDevice(DeviceType.POS);
        String stored = jdbc.queryForObject("SELECT credential_hash FROM device WHERE id = ?", String.class, device.id());
        assertThat(stored).hasSize(64).matches("^[0-9a-f]{64}$").isEqualTo(hasher.deviceCredential(device.token()));
        // 不是單純的 SHA-256（有密鑰與用途前綴）
        String plainSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(device.token().getBytes(StandardCharsets.UTF_8)));
        assertThat(stored).isNotEqualTo(plainSha);
        // 明碼不出現在該列的任何欄位
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM device WHERE id = ?", device.id());
        assertThat(row.values().toString()).doesNotContain(device.token());
    }

    @Test
    void 不同用途的雜湊互不相同() {
        String value = "same-input-value";
        assertThat(hasher.deviceCredential(value)).isNotEqualTo(hasher.pin(value))
                .isNotEqualTo(hasher.pairingCode(value));
        assertThat(hasher.pin(value)).isNotEqualTo(hasher.pairingCode(value));
    }

    @Test
    void 憑證雜湊唯一() {
        TestDevice a = registerDevice(DeviceType.POS);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO device (device_type, name, credential_hash, created_at) "
                        + "VALUES ('KDS', 'dup', ?, NOW(6))", hasher.deviceCredential(a.token())));
    }

    @Test
    void 尚未配對的裝置沒有憑證_允許多台() {
        jdbc.update("INSERT INTO device (device_type, name, created_at) VALUES ('POS', 'a', NOW(6))");
        jdbc.update("INSERT INTO device (device_type, name, created_at) VALUES ('KDS', 'b', NOW(6))");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE credential_hash IS NULL", Integer.class))
                .isEqualTo(2);
    }

    // ---- 最後使用時間（分鐘為單位） ----

    @Test
    void 最後使用時間以分鐘為單位更新() {
        TestDevice device = registerDevice(DeviceType.POS);
        assertThat(lastUsedText(device)).isNull();

        clock.set(java.time.Instant.parse("2026-03-02T00:00:40Z"));
        call(device);
        assertThat(lastUsedText(device)).isEqualTo("2026-03-02 00:00:00");

        clock.advance(Duration.ofSeconds(10)); // 00:00:50，同一分鐘
        call(device);
        assertThat(lastUsedText(device)).isEqualTo("2026-03-02 00:00:00");

        clock.advance(Duration.ofSeconds(15)); // 00:01:05，下一分鐘
        call(device);
        assertThat(lastUsedText(device)).isEqualTo("2026-03-02 00:01:00");
    }

    private void call(TestDevice device) {
        try {
            mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", device.token())).andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String lastUsedText(TestDevice device) {
        return jdbc.queryForObject(
                "SELECT DATE_FORMAT(last_used_at, '%Y-%m-%d %H:%i:%s') FROM device WHERE id = ?", String.class, device.id());
    }
}
