package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.1：PIN 驗證元件（01-PLAN §4.5「PIN」）。 */
class PinServiceTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    private TestDevice pos;

    @BeforeEach
    void setUp() {
        pos = registerDevice(DeviceType.POS);
    }

    private MvcResult verify(String pin, TestDevice device) throws Exception {
        return mvc.perform(post("/api/pos/test-pin").header("X-Device-Token", device.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}")).andReturn();
    }

    private MvcResult verify(String pin) throws Exception {
        return verify(pin, pos);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    // ---- HMAC 比對 ----

    @Test
    void 正確PIN回傳在職員工() throws Exception {
        long id = createEmployee("小明", "STAFF", "1234");
        MvcResult r = verify("1234");
        assertThat(status(r)).isEqualTo(200);
        assertThat(json(r).get("id").asLong()).isEqualTo(id);
        assertThat(json(r).get("name").asText()).isEqualTo("小明");
        assertThat(json(r).get("manager").asBoolean()).isFalse();
    }

    @Test
    void 管理者PIN回傳是管理者() throws Exception {
        createEmployee("店長", "MANAGER", "999999");
        assertThat(json(verify("999999")).get("manager").asBoolean()).isTrue();
    }

    @Test
    void 資料庫只存HMAC雜湊_不存PIN明碼() {
        createEmployee("小明", "STAFF", "4321");
        String stored = jdbc.queryForObject("SELECT pin_hash FROM employee", String.class);
        assertThat(stored).hasSize(64).isEqualTo(hasher.pin("4321")).doesNotContain("4321");
    }

    @Test
    void 錯誤PIN回INVALID_PIN() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        MvcResult r = verify("1235");
        assertThat(status(r)).isEqualTo(403);
        assertThat(json(r).get("code").asText()).isEqualTo("INVALID_PIN");
    }

    @Test
    void 全店PIN唯一_資料庫拒絕兩位在職員工使用同一PIN() {
        createEmployee("甲", "STAFF", "1111");
        org.junit.jupiter.api.Assertions.assertThrows(DuplicateKeyException.class, () -> createEmployee("乙", "STAFF", "1111"));
    }

    // ---- 停用員工回 PIN 錯誤 ----

    @Test
    void 停用員工回INVALID_PIN_且與不存在的PIN完全相同_不洩漏已停用() throws Exception {
        long id = createEmployee("離職者", "STAFF", "2222");
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", id);
        MvcResult deactivated = verify("2222");
        MvcResult neverExisted = verify("3333");
        assertThat(status(deactivated)).isEqualTo(403);
        assertThat(json(deactivated).get("code").asText()).isEqualTo("INVALID_PIN");
        assertThat(json(deactivated)).isEqualTo(json(neverExisted));
        assertThat(deactivated.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("停用");
    }

    @Test
    void 停用員工的PIN可被新員工使用() throws Exception {
        long old = createEmployee("離職者", "STAFF", "2222");
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", old);
        long fresh = createEmployee("新人", "STAFF", "2222");
        assertThat(json(verify("2222")).get("id").asLong()).isEqualTo(fresh);
    }

    // ---- 格式 ----

    @Test
    void PIN格式不合法回VALIDATION_ERROR_且不計入失敗次數() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        for (String bad : new String[] {"123", "1234567", "12a4", "", "12 34", "１２３４"}) {
            MvcResult r = verify(bad);
            assertThat(status(r)).as(bad).isEqualTo(400);
            assertThat(json(r).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        for (int i = 0; i < 4; i++) {
            verify("0000");
        }
        assertThat(status(verify("1234"))).isEqualTo(200);
    }

    // ---- 防暴力：以裝置計算 ----

    private void failTimes(int n, TestDevice device) throws Exception {
        for (int i = 0; i < n; i++) {
            assertThat(status(verify("0000", device))).isEqualTo(403);
        }
    }

    @Test
    void 同裝置5分鐘內錯5次鎖5分鐘_連正確PIN也被拒() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        failTimes(5, pos); // 第 5 次仍回 INVALID_PIN，之後才被鎖
        MvcResult locked = verify("1234");
        assertThat(status(locked)).isEqualTo(429);
        assertThat(json(locked).get("code").asText()).isEqualTo("PIN_LOCKED");
    }

    @Test
    void 鎖定滿5分鐘後解除() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        failTimes(5, pos);
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(status(verify("1234"))).isEqualTo(429);
        clock.advance(Duration.ofSeconds(2));
        assertThat(status(verify("1234"))).isEqualTo(200);
    }

    @Test
    void 成功不清除失敗計數() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        failTimes(4, pos);
        assertThat(status(verify("1234"))).isEqualTo(200);
        failTimes(1, pos); // 第 5 次失敗
        assertThat(status(verify("1234"))).isEqualTo(429);
    }

    @Test
    void 超過5分鐘視窗的失敗不累計() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        failTimes(4, pos);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        failTimes(4, pos);
        assertThat(status(verify("1234"))).isEqualTo(200);
    }

    @Test
    void 鎖定只影響該裝置_其他裝置不受影響() throws Exception {
        createEmployee("小明", "STAFF", "1234");
        TestDevice other = registerDevice(DeviceType.POS);
        failTimes(5, pos);
        assertThat(status(verify("1234", pos))).isEqualTo(429);
        assertThat(status(verify("1234", other))).isEqualTo(200);
    }

    @Test
    void 鎖定寫稽核且包含裝置編號() throws Exception {
        failTimes(5, pos);
        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log", String.class);
        assertThat(actions).containsExactly("PIN_LOCKED");
        assertThat(jdbc.queryForObject("SELECT actor_device_id FROM audit_log", Long.class)).isEqualTo(pos.id());
        // 之後被鎖期間的嘗試不重複寫稽核
        verify("0000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(1);
    }

    @Test
    void PIN不出現在任何日誌或稽核() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            createEmployee("小明", "STAFF", "1234");
            verify("7777");
            verify("1234");
            for (int i = 0; i < 5; i++) {
                verify("8888");
            }
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("7777").doesNotContain("8888").doesNotContain("1234");
            }
            assertThat(jdbc.queryForList("SELECT CONCAT_WS(',', action, target_type, target_id, before_value, after_value, reason) FROM audit_log",
                    String.class).toString()).doesNotContain("8888").doesNotContain("1234");
        } finally {
            root.detachAppender(appender);
        }
    }
}
