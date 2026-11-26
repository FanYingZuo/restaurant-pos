package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.8：出勤更正（EMPLOYEE_MANAGE，01-PLAN S2）。 */
class ShiftCorrectionTest extends AbstractIntegrationTest {

    private TestDevice pos;
    private String token;
    private long managerId;
    private long xiaoMing;

    private static Instant taipei(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Taipei")).toInstant();
    }

    @BeforeEach
    void setUp() {
        clock.set(taipei("2026-03-10T12:00:00")); // 營業日 2026-03-10
        pos = registerDevice(DeviceType.POS);
        managerId = createEmployee("測試店長", "MANAGER", MANAGER_PIN);
        xiaoMing = createEmployee("小明", "STAFF", "1234");
        token = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
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

    private MvcResult createShift(long employeeId, String in, String out, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return call(post("/api/staff/shifts"), "{\"employeeId\":" + employeeId + ",\"clockInAt\":\"" + in
                + "\",\"clockOutAt\":\"" + out + "\"" + reasonJson + "}");
    }

    private MvcResult correct(long shiftId, String json) throws Exception {
        return call(patch("/api/staff/shifts/" + shiftId), json);
    }

    private MvcResult clockAct(String path, String pin) throws Exception {
        return mvc.perform(post("/api/staff/" + path).header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}")).andReturn();
    }

    private long onlyShiftId() {
        return jdbc.queryForObject("SELECT id FROM work_shift ORDER BY id DESC LIMIT 1", Long.class);
    }

    // ---- 權限 ----

    @Test
    void 沒有解鎖或功能不符回FORBIDDEN() throws Exception {
        String wrong = unlock(pos, UnlockFunction.DEVICE_MANAGE);
        for (String t : new String[] {null, wrong}) {
            assertThat(errorCode(call(post("/api/staff/shifts"), t, "{}"))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(patch("/api/staff/shifts/1"), t, "{}"))).isEqualTo("FORBIDDEN");
        }
    }

    // ---- 新增完整班次 ----

    @Test
    void 新增完整班次_標記已更正並寫歷史與稽核() throws Exception {
        MvcResult r = createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T09:30:00Z", "忘記打卡");
        assertThat(status(r)).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("employeeName").asText()).isEqualTo("小明");
        assertThat(b.get("businessDate").asText()).isEqualTo("2026-03-09");
        assertThat(b.get("minutes").asInt()).isEqualTo(510);
        assertThat(b.get("state").asText()).isEqualTo("COMPLETE");
        assertThat(b.get("corrected").asBoolean()).isTrue();

        Map<String, Object> history = jdbc.queryForMap("SELECT shift_id, actor_employee_id, reason, before_clock_in, before_clock_out, "
                + "DATE_FORMAT(after_clock_in, '%Y-%m-%d %H:%i:%s') AS a_in, DATE_FORMAT(after_clock_out, '%Y-%m-%d %H:%i:%s') AS a_out FROM work_shift_history");
        assertThat(history.get("actor_employee_id")).isEqualTo(managerId);
        assertThat(history.get("reason")).isEqualTo("忘記打卡");
        assertThat(history.get("before_clock_in")).isNull();
        assertThat(history.get("before_clock_out")).isNull();
        assertThat(history.get("a_in")).isEqualTo("2026-03-09 01:00:00");
        assertThat(history.get("a_out")).isEqualTo("2026-03-09 09:30:00");

        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_employee_id, actor_device_id, target_id, reason FROM audit_log WHERE action = 'SHIFT_CORRECTED'");
        assertThat(audit.get("actor_employee_id")).isEqualTo(managerId);
        assertThat(audit.get("actor_device_id")).isEqualTo(pos.id());
        assertThat(audit.get("reason")).isEqualTo("忘記打卡");
        assertThat(audit.get("target_id")).isEqualTo(String.valueOf(b.get("shiftId").asLong()));
    }

    @Test
    void 理由前後空白會去除() throws Exception {
        createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T02:00:00Z", "  補打卡  ");
        assertThat(jdbc.queryForObject("SELECT reason FROM work_shift_history", String.class)).isEqualTo("補打卡");
    }

    @Test
    void 新增的班次營業日依上班時間計算() throws Exception {
        // 台北 2026-03-09 03:00（凌晨，屬 03-08 營業日）上班
        MvcResult r = createShift(xiaoMing, "2026-03-08T19:00:00Z", "2026-03-09T04:00:00Z", "補");
        assertThat(body(r).get("businessDate").asText()).isEqualTo("2026-03-08");
    }

    // ---- 補上班或下班時間、修改既有班次 ----

    @Test
    void 補下班時間_下班未打卡的班次變完整() throws Exception {
        clock.set(taipei("2026-03-09T09:00:00"));
        clockAct("clock-in", "1234");
        clock.set(taipei("2026-03-10T12:00:00"));
        long shiftId = onlyShiftId();
        MvcResult r = correct(shiftId, "{\"clockOutAt\":\"2026-03-09T10:00:00Z\",\"reason\":\"忘記下班\"}");
        assertThat(status(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("state").asText()).isEqualTo("COMPLETE");
        assertThat(b.get("minutes").asInt()).isEqualTo(540); // 2026-03-09 09:00 +08 → 01:00Z，到 10:00Z
        assertThat(b.get("corrected").asBoolean()).isTrue();
        Map<String, Object> history = jdbc.queryForMap("SELECT before_clock_out, DATE_FORMAT(after_clock_out, '%Y-%m-%d %H:%i:%s') AS a_out FROM work_shift_history");
        assertThat(history.get("before_clock_out")).isNull();
        assertThat(history.get("a_out")).isEqualTo("2026-03-09 10:00:00");
    }

    @Test
    void 補上班時間_上班未打卡的班次變完整() throws Exception {
        clock.set(taipei("2026-03-09T18:00:00"));
        clockAct("clock-out", "1234"); // 沒上班就下班
        clock.set(taipei("2026-03-10T12:00:00"));
        long shiftId = onlyShiftId();
        MvcResult r = correct(shiftId, "{\"clockInAt\":\"2026-03-09T01:00:00Z\",\"reason\":\"忘記上班\"}");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("state").asText()).isEqualTo("COMPLETE");
        assertThat(body(r).get("minutes").asInt()).isEqualTo(540); // 01:00Z 到 10:00Z
    }

    @Test
    void 修改既有完整班次的兩個時間_歷史記錄前後值() throws Exception {
        long shiftId = body(createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T09:00:00Z", "第一次")).get("shiftId").asLong();
        MvcResult r = correct(shiftId, "{\"clockInAt\":\"2026-03-09T02:00:00Z\",\"clockOutAt\":\"2026-03-09T08:00:00Z\",\"reason\":\"第二次\"}");
        assertThat(body(r).get("minutes").asInt()).isEqualTo(360);
        // 歷史只新增：兩筆，且第二筆的 before ＝ 第一筆的 after
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift_history", Integer.class)).isEqualTo(2);
        Map<String, Object> second = jdbc.queryForMap("SELECT DATE_FORMAT(before_clock_in, '%H:%i') AS b_in, DATE_FORMAT(before_clock_out, '%H:%i') AS b_out, "
                + "DATE_FORMAT(after_clock_in, '%H:%i') AS a_in, DATE_FORMAT(after_clock_out, '%H:%i') AS a_out FROM work_shift_history ORDER BY id DESC LIMIT 1");
        assertThat(second).containsEntry("b_in", "01:00").containsEntry("b_out", "09:00")
                .containsEntry("a_in", "02:00").containsEntry("a_out", "08:00");
    }

    @Test
    void 修改上班時間後營業日重新計算() throws Exception {
        long shiftId = body(createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T09:00:00Z", "x")).get("shiftId").asLong();
        MvcResult r = correct(shiftId, "{\"clockInAt\":\"2026-03-08T01:00:00Z\",\"reason\":\"改日期\"}");
        assertThat(body(r).get("businessDate").asText()).isEqualTo("2026-03-08");
    }

    @Test
    void 更正後的班次在打卡狀態與歷史中標示已更正() throws Exception {
        createShift(xiaoMing, "2026-03-10T01:00:00Z", "2026-03-10T03:00:00Z", "補打卡");
        JsonNode status = body(clockAct("clock-status", "1234"));
        assertThat(status.get("todayShifts").get(0).get("corrected").asBoolean()).isTrue();
        JsonNode history = body(call(get("/api/staff/clock-history"), null));
        assertThat(history.get("shifts").get(0).get("corrected").asBoolean()).isTrue();
    }

    // ---- 理由必填 ----

    @Test
    void 理由必填() throws Exception {
        for (String reason : new String[] {null, "", "   ", "x".repeat(256)}) {
            MvcResult r = createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T09:00:00Z", reason);
            assertThat(status(r)).as(String.valueOf(reason)).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(status(createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T09:00:00Z", "x".repeat(255)))).isEqualTo(201);
    }

    @Test
    void 修改時理由必填_且至少給一個時間() throws Exception {
        long shiftId = body(createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T09:00:00Z", "先建立")).get("shiftId").asLong();
        assertThat(errorCode(correct(shiftId, "{\"clockOutAt\":\"2026-03-09T08:00:00Z\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(correct(shiftId, "{\"reason\":\"沒給時間\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(correct(shiftId, "{\"clockOutAt\":\"不是時間\",\"reason\":\"r\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift_history", Integer.class)).isEqualTo(1);
    }

    @Test
    void 新增缺少欄位回VALIDATION_ERROR() throws Exception {
        assertThat(errorCode(call(post("/api/staff/shifts"), "{\"reason\":\"r\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post("/api/staff/shifts"), "{\"employeeId\":" + xiaoMing + ",\"clockInAt\":\"2026-03-09T01:00:00Z\",\"reason\":\"r\"}"))).isEqualTo("VALIDATION_ERROR");
    }

    // ---- 時間先後與不晚於現在 ----

    @Test
    void 下班必須晚於上班() throws Exception {
        MvcResult same = createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T01:00:00Z", "r");
        assertThat(status(same)).isEqualTo(400);
        assertThat(errorCode(same)).isEqualTo("SHIFT_CORRECTION_INVALID");
        assertThat(errorCode(createShift(xiaoMing, "2026-03-09T05:00:00Z", "2026-03-09T01:00:00Z", "r"))).isEqualTo("SHIFT_CORRECTION_INVALID");
    }

    @Test
    void 修改後的下班不晚於既有上班也不合法() throws Exception {
        long shiftId = body(createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T09:00:00Z", "r")).get("shiftId").asLong();
        assertThat(errorCode(correct(shiftId, "{\"clockOutAt\":\"2026-03-09T00:30:00Z\",\"reason\":\"r\"}"))).isEqualTo("SHIFT_CORRECTION_INVALID");
        assertThat(errorCode(correct(shiftId, "{\"clockInAt\":\"2026-03-09T10:00:00Z\",\"reason\":\"r\"}"))).isEqualTo("SHIFT_CORRECTION_INVALID");
    }

    @Test
    void 時間不可晚於現在_剛好等於現在可以() throws Exception {
        // 現在 ＝ 台北 2026-03-10 12:00 ＝ 2026-03-10T04:00:00Z
        MvcResult future = createShift(xiaoMing, "2026-03-10T01:00:00Z", "2026-03-10T04:00:01Z", "r");
        assertThat(status(future)).isEqualTo(400);
        assertThat(errorCode(future)).isEqualTo("SHIFT_CORRECTION_INVALID");
        assertThat(status(createShift(xiaoMing, "2026-03-10T01:00:00Z", "2026-03-10T04:00:00Z", "r"))).isEqualTo(201);
    }

    @Test
    void 修改時給未來時間不合法() throws Exception {
        clock.set(taipei("2026-03-10T08:00:00"));
        clockAct("clock-in", "1234");
        long shiftId = onlyShiftId();
        assertThat(errorCode(correct(shiftId, "{\"clockOutAt\":\"2026-03-11T00:00:00Z\",\"reason\":\"r\"}"))).isEqualTo("SHIFT_CORRECTION_INVALID");
    }

    // ---- 31 天 ----

    @Test
    void 剛好31天內可以_第32天回SHIFT_CORRECTION_TOO_OLD() throws Exception {
        // 今天營業日 2026-03-10；31 天前 ＝ 2026-02-07
        assertThat(status(createShift(xiaoMing, "2026-02-07T01:00:00Z", "2026-02-07T05:00:00Z", "r"))).isEqualTo(201);
        MvcResult tooOld = createShift(xiaoMing, "2026-02-06T01:00:00Z", "2026-02-06T05:00:00Z", "r");
        assertThat(status(tooOld)).isEqualTo(400);
        assertThat(errorCode(tooOld)).isEqualTo("SHIFT_CORRECTION_TOO_OLD");
    }

    @Test
    void 修改太久以前的班次回TOO_OLD() throws Exception {
        jdbc.update("INSERT INTO work_shift (employee_id, business_date, clock_in_at, clock_out_at, corrected, created_at) "
                + "VALUES (?, '2026-01-01', '2026-01-01 01:00:00', NULL, FALSE, NOW(6))", xiaoMing);
        long shiftId = onlyShiftId();
        assertThat(errorCode(correct(shiftId, "{\"clockOutAt\":\"2026-01-01T05:00:00Z\",\"reason\":\"r\"}"))).isEqualTo("SHIFT_CORRECTION_TOO_OLD");
    }

    @Test
    void 保存天數由設定決定() throws Exception {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('shift.correction.max.days', '3', NOW(6))");
        assertThat(status(createShift(xiaoMing, "2026-03-07T01:00:00Z", "2026-03-07T05:00:00Z", "r"))).isEqualTo(201);
        assertThat(errorCode(createShift(xiaoMing, "2026-03-06T01:00:00Z", "2026-03-06T05:00:00Z", "r"))).isEqualTo("SHIFT_CORRECTION_TOO_OLD");
    }

    // ---- 不能改自己的（唯一管理者例外） ----

    @Test
    void 唯一的管理者可以更正自己的班次() throws Exception {
        assertThat(status(createShift(managerId, "2026-03-09T01:00:00Z", "2026-03-09T05:00:00Z", "店長自己補"))).isEqualTo(201);
    }

    @Test
    void 有其他在職管理者時不能更正自己_新增與修改都一樣() throws Exception {
        createEmployee("副店長", "MANAGER", "4444");
        MvcResult create = createShift(managerId, "2026-03-09T01:00:00Z", "2026-03-09T05:00:00Z", "r");
        assertThat(status(create)).isEqualTo(403);
        assertThat(errorCode(create)).isEqualTo("SHIFT_CORRECTION_SELF");

        jdbc.update("INSERT INTO work_shift (employee_id, business_date, clock_in_at, clock_out_at, corrected, created_at) "
                + "VALUES (?, '2026-03-09', '2026-03-09 01:00:00', NULL, FALSE, NOW(6))", managerId);
        MvcResult update = correct(onlyShiftId(), "{\"clockOutAt\":\"2026-03-09T05:00:00Z\",\"reason\":\"r\"}");
        assertThat(errorCode(update)).isEqualTo("SHIFT_CORRECTION_SELF");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift_history", Integer.class)).isZero();
    }

    @Test
    void 其他管理者可以更正這位管理者的班次() throws Exception {
        createEmployee("副店長", "MANAGER", "4444");
        MvcResult unlockSecond = mvc.perform(post("/api/staff/unlock").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"4444\",\"function\":\"EMPLOYEE_MANAGE\"}")).andReturn();
        String secondToken = body(unlockSecond).get("unlockToken").asText();
        MvcResult r = call(post("/api/staff/shifts"), secondToken, "{\"employeeId\":" + managerId
                + ",\"clockInAt\":\"2026-03-09T01:00:00Z\",\"clockOutAt\":\"2026-03-09T05:00:00Z\",\"reason\":\"副店長幫忙補\"}");
        assertThat(status(r)).isEqualTo(201);
    }

    // ---- 存在 ----

    @Test
    void 不存在的員工與班次回404() throws Exception {
        MvcResult noEmployee = createShift(999999, "2026-03-09T01:00:00Z", "2026-03-09T05:00:00Z", "r");
        assertThat(status(noEmployee)).isEqualTo(404);
        assertThat(errorCode(noEmployee)).isEqualTo("EMPLOYEE_NOT_FOUND");
        MvcResult noShift = correct(999999, "{\"clockOutAt\":\"2026-03-09T05:00:00Z\",\"reason\":\"r\"}");
        assertThat(status(noShift)).isEqualTo(404);
        assertThat(errorCode(noShift)).isEqualTo("NOT_FOUND");
    }

    @Test
    void 可以更正已停用員工的班次() throws Exception {
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", xiaoMing);
        assertThat(status(createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T05:00:00Z", "離職前補"))).isEqualTo(201);
    }

    // ---- 檢查順序 ----

    @Test
    void 檢查順序_自己優先於時間不合法_時間不合法優先於太久以前() throws Exception {
        createEmployee("副店長", "MANAGER", "4444");
        assertThat(errorCode(createShift(managerId, "2026-03-09T05:00:00Z", "2026-03-09T01:00:00Z", "r"))).isEqualTo("SHIFT_CORRECTION_SELF");
        assertThat(errorCode(createShift(xiaoMing, "2025-01-01T05:00:00Z", "2025-01-01T01:00:00Z", "r"))).isEqualTo("SHIFT_CORRECTION_INVALID");
        assertThat(errorCode(createShift(xiaoMing, "2025-01-01T01:00:00Z", "2025-01-01T05:00:00Z", "r"))).isEqualTo("SHIFT_CORRECTION_TOO_OLD");
    }

    // ---- 班次不提供刪除 ----

    @Test
    void 班次不提供刪除() throws Exception {
        long shiftId = body(createShift(xiaoMing, "2026-03-09T01:00:00Z", "2026-03-09T05:00:00Z", "r")).get("shiftId").asLong();
        MvcResult r = call(delete("/api/staff/shifts/" + shiftId), null);
        assertThat(status(r)).isEqualTo(405);
        assertThat(errorCode(r)).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift", Integer.class)).isEqualTo(1);
    }

    @Test
    void 更正與打卡同時進行不會死結() throws Exception {
        clock.set(taipei("2026-03-10T08:00:00"));
        clockAct("clock-in", "1234");
        long shiftId = onlyShiftId();
        clock.set(taipei("2026-03-10T12:00:00"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var a = pool.submit(() -> {
            start.await();
            return status(correct(shiftId, "{\"clockOutAt\":\"2026-03-10T03:00:00Z\",\"reason\":\"補下班\"}"));
        });
        var b = pool.submit(() -> {
            start.await();
            return status(clockAct("clock-out", "1234"));
        });
        start.countDown();
        int sa = a.get(20, java.util.concurrent.TimeUnit.SECONDS);
        int sb = b.get(20, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(sa).isIn(200, 409);
        assertThat(sb).isIn(200, 409, 429);
        assertThat(Duration.ofSeconds(0)).isNotNull();
    }
}
