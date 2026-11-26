package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.staff.employee.EmployeeNames;
import restaurant_pos.support.AbstractIntegrationTest;

/**
 * 卡 2.10：對外介面（PIN 取員工、解鎖檢查、批次取名稱）與 S2 驗收。
 * S2 完成條件：員工、打卡、裝置、出勤更正、稽核查詢測試全部通過。
 */
class S2AcceptanceTest extends AbstractIntegrationTest {

    @Autowired
    EmployeeNames employeeNames;

    @Autowired
    PinVerifier pinVerifier;

    @Autowired
    UnlockGate unlockGate;

    // ---- 2.10 對外介面 ----

    @Test
    void 批次取員工名稱_含已停用_查不到的不在結果中() {
        long a = createEmployee("小明", "STAFF", "1111");
        long b = createEmployee("小華", "STAFF", "2222");
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", b);
        Map<Long, String> names = employeeNames.namesByIds(List.of(a, b, 999_999L, a));
        assertThat(names).containsOnlyKeys(a, b).containsEntry(a, "小明").containsEntry(b, "小華");
    }

    @Test
    void 批次取員工名稱_空輸入與null回空結果() {
        assertThat(employeeNames.namesByIds(List.of())).isEmpty();
        assertThat(employeeNames.namesByIds(null)).isEmpty();
        assertThat(employeeNames.namesByIds(java.util.Arrays.asList(null, null))).isEmpty();
    }

    @Test
    void PIN驗證與解鎖檢查的實作在staff模組() {
        assertThat(pinVerifier.getClass().getName()).startsWith("restaurant_pos.staff.");
        assertThat(unlockGate.getClass().getName()).startsWith("restaurant_pos.staff.");
    }

    // ---- S2 驗收：員工、打卡、裝置、出勤更正、稽核查詢一條龍 ----

    private MvcResult call(MockHttpServletRequestBuilder request, TestDevice device, String unlockToken, String body)
            throws Exception {
        request = request.header("X-Device-Token", device.token());
        if (unlockToken != null) {
            request = request.header("X-Unlock-Token", unlockToken);
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 員工_打卡_裝置_出勤更正_稽核查詢一條龍() throws Exception {
        clock.set(Instant.parse("2026-03-10T04:00:00Z")); // 台北 12:00，營業日 2026-03-10
        TestDevice pos = registerDevice(DeviceType.POS);
        createEmployee("店長", "MANAGER", MANAGER_PIN);

        // 1. 店長解鎖員工管理，新增員工 B
        String employeeToken = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        MvcResult created = call(post("/api/staff/employees"), pos, employeeToken,
                "{\"name\":\"B\",\"role\":\"STAFF\",\"pin\":\"2468\"}");
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
        long employeeB = body(created).get("id").asLong();

        // 2. B 上班打卡，之後下班
        MvcResult in = call(post("/api/staff/clock-in"), pos, null, "{\"pin\":\"2468\"}");
        assertThat(body(in).get("state").asText()).isEqualTo("ON_DUTY");
        clock.advance(Duration.ofHours(4));
        MvcResult out = call(post("/api/staff/clock-out"), pos, null, "{\"pin\":\"2468\"}");
        assertThat(body(out).get("todayTotalMinutes").asInt()).isEqualTo(240);

        // 3. 店長補一筆前一天的班次（出勤更正）。時間已過 4 小時，先前的解鎖早已因閒置失效，需重新解鎖
        assertThat(call(get("/api/staff/employees"), pos, employeeToken, null).getResponse().getStatus()).isEqualTo(403);
        employeeToken = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        MvcResult shift = call(post("/api/staff/shifts"), pos, employeeToken, "{\"employeeId\":" + employeeB
                + ",\"clockInAt\":\"2026-03-09T01:00:00Z\",\"clockOutAt\":\"2026-03-09T09:00:00Z\",\"reason\":\"忘記打卡\"}");
        assertThat(shift.getResponse().getStatus()).isEqualTo(201);
        long shiftId = body(shift).get("shiftId").asLong();
        MvcResult fixed = call(patch("/api/staff/shifts/" + shiftId), pos, employeeToken,
                "{\"clockOutAt\":\"2026-03-09T08:00:00Z\",\"reason\":\"下班時間記錯\"}");
        assertThat(body(fixed).get("minutes").asInt()).isEqualTo(420);

        // 4. 打卡歷史看得到兩個班次，前一天的標示已更正
        JsonNode history = body(call(get("/api/staff/clock-history"), pos, employeeToken, null));
        assertThat(history.get("shifts")).hasSize(2);
        assertThat(history.get("shifts").get(1).get("corrected").asBoolean()).isTrue();

        // 5. 解鎖裝置管理，建立並配對廚房平板，再撤銷
        String deviceToken = unlock(pos, UnlockFunction.DEVICE_MANAGE);
        JsonNode device = body(call(post("/api/staff/devices"), pos, deviceToken, "{\"name\":\"廚房\",\"deviceType\":\"KDS\"}"));
        MvcResult paired = mvc.perform(post("/api/pairing").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pairingCode\":\"" + device.get("pairingCode").asText() + "\"}")).andReturn();
        String kdsToken = body(paired).get("deviceToken").asText();
        assertThat(mvc.perform(get("/api/kds/test-ping").header("X-Device-Token", kdsToken)).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
        call(post("/api/staff/devices/" + device.get("device").get("id").asLong() + "/revoke"), pos, deviceToken, null);
        assertThat(mvc.perform(get("/api/kds/test-ping").header("X-Device-Token", kdsToken)).andReturn()
                .getResponse().getStatus()).isEqualTo(401);

        // 6. 稽核查詢看得到整條軌跡
        JsonNode audit = body(call(get("/api/staff/audit-logs?size=100"), pos, employeeToken, null));
        List<String> actions = audit.get("content").findValuesAsText("action");
        assertThat(actions).contains("ADMIN_UNLOCKED", "SHIFT_CORRECTED", "DEVICE_CREATED", "DEVICE_PAIRED", "DEVICE_REVOKED");
        assertThat(actions.stream().filter("SHIFT_CORRECTED"::equals).count()).isEqualTo(2);

        // 7. 離開管控頁面釋放解鎖後，無法再用
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/staff/unlock"), pos, employeeToken, null);
        assertThat(call(get("/api/staff/employees"), pos, employeeToken, null).getResponse().getStatus()).isEqualTo(403);
    }
}
