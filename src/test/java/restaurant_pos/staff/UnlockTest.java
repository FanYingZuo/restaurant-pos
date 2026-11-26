package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.5：管理者解鎖（01-PLAN §4.5「管理者解鎖」）。 */
class UnlockTest extends AbstractIntegrationTest {

    private TestDevice pos;
    private long managerId;

    @BeforeEach
    void setUp() {
        pos = registerDevice(DeviceType.POS);
        managerId = createEmployee("店長", "MANAGER", MANAGER_PIN);
        createEmployee("小明", "STAFF", "1234");
    }

    private MvcResult doUnlock(String pin, String function) throws Exception {
        return mvc.perform(post("/api/staff/unlock").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"" + pin + "\",\"function\":\"" + function + "\"}")).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult check(String token, UnlockFunction function) throws Exception {
        var request = get("/api/staff/test-unlock").param("function", function.name()).header("X-Device-Token", pos.token());
        if (token != null) {
            request = request.header("X-Unlock-Token", token);
        }
        return mvc.perform(request).andReturn();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).get("code").asText();
    }

    private String token(UnlockFunction function) throws Exception {
        return body(doUnlock(MANAGER_PIN, function.name())).get("unlockToken").asText();
    }

    // ---- 取得 ----

    @Test
    void 管理者PIN加功能代碼換取32位元組憑證() throws Exception {
        MvcResult r = doUnlock(MANAGER_PIN, "TABLE_LAYOUT");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("function").asText()).isEqualTo("TABLE_LAYOUT");
        assertThat(Base64.getUrlDecoder().decode(body(r).get("unlockToken").asText())).hasSize(32);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void 非管理者回NOT_MANAGER() throws Exception {
        MvcResult r = doUnlock("1234", "EMPLOYEE_MANAGE");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("NOT_MANAGER");
    }

    @Test
    void 錯誤PIN回INVALID_PIN() throws Exception {
        assertThat(errorCode(doUnlock("0000", "EMPLOYEE_MANAGE"))).isEqualTo("INVALID_PIN");
    }

    @Test
    void 功能代碼不存在回VALIDATION_ERROR() throws Exception {
        MvcResult r = doUnlock(MANAGER_PIN, "EVERYTHING");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(mvc.perform(post("/api/staff/unlock").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + MANAGER_PIN + "\"}")).andReturn()))
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 解鎖使用PIN防暴力_被鎖時回PIN_LOCKED() throws Exception {
        for (int i = 0; i < 5; i++) {
            doUnlock("0000", "EMPLOYEE_MANAGE");
        }
        assertThat(errorCode(doUnlock(MANAGER_PIN, "EMPLOYEE_MANAGE"))).isEqualTo("PIN_LOCKED");
    }

    // ---- 檢查：只對該功能有效 ----

    @Test
    void 憑證只對該功能有效() throws Exception {
        String token = token(UnlockFunction.EMPLOYEE_MANAGE);
        MvcResult ok = check(token, UnlockFunction.EMPLOYEE_MANAGE);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("employeeId").asLong()).isEqualTo(managerId);
        MvcResult wrong = check(token, UnlockFunction.TABLE_LAYOUT);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(wrong)).isEqualTo("FORBIDDEN");
        // 功能不符不會讓憑證失效
        assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 缺少或亂填的憑證回FORBIDDEN() throws Exception {
        for (String bad : new String[] {null, "", "   ", "garbage", "x".repeat(1000)}) {
            MvcResult r = check(bad, UnlockFunction.EMPLOYEE_MANAGE);
            assertThat(r.getResponse().getStatus()).as(String.valueOf(bad)).isEqualTo(403);
            assertThat(errorCode(r)).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void 同時持有不同功能的憑證互不影響() throws Exception {
        String a = token(UnlockFunction.EMPLOYEE_MANAGE);
        String b = token(UnlockFunction.DEVICE_MANAGE);
        assertThat(a).isNotEqualTo(b);
        assertThat(check(a, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(200);
        assertThat(check(b, UnlockFunction.DEVICE_MANAGE).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- 釋放 ----

    @Test
    void 釋放後憑證立即失效_重複釋放與釋放不存在的都回204() throws Exception {
        String token = token(UnlockFunction.TABLE_LAYOUT);
        for (int i = 0; i < 2; i++) {
            assertThat(mvc.perform(delete("/api/staff/unlock").header("X-Device-Token", pos.token())
                    .header("X-Unlock-Token", token)).andReturn().getResponse().getStatus()).isEqualTo(204);
        }
        assertThat(check(token, UnlockFunction.TABLE_LAYOUT).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(delete("/api/staff/unlock").header("X-Device-Token", pos.token())).andReturn()
                .getResponse().getStatus()).isEqualTo(204);
    }

    // ---- 閒置 30 分鐘 ----

    @Test
    void 閒置30分鐘失效_29分59秒仍有效() throws Exception {
        String token = token(UnlockFunction.EMPLOYEE_MANAGE);
        clock.advance(Duration.ofMinutes(29).plusSeconds(59));
        assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(30));
        assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 使用中會重新計算閒置時間() throws Exception {
        String token = token(UnlockFunction.EMPLOYEE_MANAGE);
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofMinutes(20));
            assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).as("第 " + i).isEqualTo(200);
        }
    }

    @Test
    void 閒置時間由設定決定() throws Exception {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('unlock.idle.minutes', '5', NOW(6))");
        String token = token(UnlockFunction.EMPLOYEE_MANAGE);
        clock.advance(Duration.ofMinutes(5));
        assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(403);
    }

    // ---- 解鎖者停用、降級、重啟 ----

    @Test
    void 解鎖者停用後憑證失效() throws Exception {
        String token = token(UnlockFunction.EMPLOYEE_MANAGE);
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", managerId);
        assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 解鎖者不再是管理者後憑證失效() throws Exception {
        String token = token(UnlockFunction.EMPLOYEE_MANAGE);
        jdbc.update("UPDATE employee SET role = 'STAFF' WHERE id = ?", managerId);
        assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 服務重啟後憑證失效() throws Exception {
        String token = token(UnlockFunction.EMPLOYEE_MANAGE);
        unlockService.clear(); // 憑證只存在記憶體，重啟等同清空
        assertThat(check(token, UnlockFunction.EMPLOYEE_MANAGE).getResponse().getStatus()).isEqualTo(403);
    }

    // ---- 稽核與保密 ----

    @Test
    void 解鎖寫稽核_不含憑證() throws Exception {
        String token = token(UnlockFunction.DEVICE_MANAGE);
        var row = jdbc.queryForMap("SELECT action, target_type, target_id, actor_employee_id, actor_device_id, "
                + "CONCAT_WS(',', before_value, after_value, reason) AS extra FROM audit_log");
        assertThat(row.get("action")).isEqualTo("ADMIN_UNLOCKED");
        assertThat(row.get("target_id")).isEqualTo("DEVICE_MANAGE");
        assertThat(row.get("actor_employee_id")).isEqualTo(managerId);
        assertThat(row.get("actor_device_id")).isEqualTo(pos.id());
        assertThat(row.toString()).doesNotContain(token);
    }

    @Test
    void 憑證與PIN不出現在日誌() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String token = token(UnlockFunction.EMPLOYEE_MANAGE);
            check(token, UnlockFunction.TABLE_LAYOUT);
            check("bogus-unlock-token", UnlockFunction.EMPLOYEE_MANAGE);
            for (ILoggingEvent e : appender.list) {
                assertThat(e.getFormattedMessage()).doesNotContain(token).doesNotContain("bogus-unlock-token").doesNotContain(MANAGER_PIN);
            }
        } finally {
            root.detachAppender(appender);
        }
    }
}
