package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.3、2.4：上下班打卡、打卡狀態查詢與打卡歷史（01-PLAN S2）。 */
class ClockTest extends AbstractIntegrationTest {

    private TestDevice pos;
    private long xiaoMing;

    @BeforeEach
    void setUp() {
        pos = registerDevice(DeviceType.POS);
        xiaoMing = createEmployee("小明", "STAFF", "1234");
    }

    /** 台北本地時間轉 Instant。 */
    private static Instant taipei(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Taipei")).toInstant();
    }

    private MvcResult act(String path, String pin) throws Exception {
        return mvc.perform(post("/api/staff/" + path).header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}")).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int code(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String errorCode(MvcResult r) throws Exception {
        return body(r).get("code").asText();
    }

    // ---- 上班 ----

    @Test
    void 上班打卡成功_回應欄位完整() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        MvcResult r = act("clock-in", "1234");
        assertThat(code(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("employeeId").asLong()).isEqualTo(xiaoMing);
        assertThat(b.get("employeeName").asText()).isEqualTo("小明");
        assertThat(b.get("state").asText()).isEqualTo("ON_DUTY");
        assertThat(b.get("nextAction").asText()).isEqualTo("CLOCK_OUT");
        assertThat(b.get("warnings")).isEmpty();
        assertThat(b.get("todayTotalMinutes").asInt()).isZero();
        JsonNode shift = b.get("todayShifts").get(0);
        assertThat(shift.get("businessDate").asText()).isEqualTo("2026-03-02");
        assertThat(shift.get("clockInAt").asText()).isEqualTo("2026-03-02T01:00:00Z");
        assertThat(shift.get("clockOutAt").isNull()).isTrue();
        assertThat(shift.get("minutes").isNull()).isTrue();
        assertThat(shift.get("state").asText()).isEqualTo("ON_DUTY");
        assertThat(shift.get("corrected").asBoolean()).isFalse();
    }

    @Test
    void 錯誤PIN打卡失敗() throws Exception {
        assertThat(code(act("clock-in", "0000"))).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift", Integer.class)).isZero();
    }

    // ---- 間隔 60 秒 ----

    @Test
    void 兩次打卡至少間隔60秒() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        clock.advance(Duration.ofSeconds(59));
        MvcResult tooFast = act("clock-out", "1234");
        assertThat(code(tooFast)).isEqualTo(429);
        assertThat(errorCode(tooFast)).isEqualTo("CLOCK_TOO_FAST");
        clock.advance(Duration.ofSeconds(1)); // 剛好 60 秒
        assertThat(code(act("clock-out", "1234"))).isEqualTo(200);
    }

    @Test
    void 間隔秒數由設定決定() throws Exception {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('clock.min.interval.seconds', '10', NOW(6))");
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        clock.advance(Duration.ofSeconds(10));
        assertThat(code(act("clock-out", "1234"))).isEqualTo(200);
    }

    @Test
    void 間隔限制以員工分別計算() throws Exception {
        createEmployee("小華", "STAFF", "5678");
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        assertThat(code(act("clock-in", "5678"))).isEqualTo(200);
    }

    // ---- 上班中不能再上班 ----

    @Test
    void 上班中再上班回ALREADY_CLOCKED_IN() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        clock.advance(Duration.ofMinutes(5));
        MvcResult r = act("clock-in", "1234");
        assertThat(code(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("ALREADY_CLOCKED_IN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift", Integer.class)).isEqualTo(1);
    }

    // ---- 下班與分鐘數 ----

    @Test
    void 下班後狀態為完整班次_分鐘數無條件捨去() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        clock.advance(Duration.ofMinutes(59).plusSeconds(59));
        MvcResult r = act("clock-out", "1234");
        JsonNode b = body(r);
        assertThat(b.get("state").asText()).isEqualTo("COMPLETE");
        assertThat(b.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(b.get("todayShifts").get(0).get("minutes").asInt()).isEqualTo(59);
        assertThat(b.get("todayShifts").get(0).get("state").asText()).isEqualTo("COMPLETE");
        assertThat(b.get("todayTotalMinutes").asInt()).isEqualTo(59);
    }

    @Test
    void 分鐘數邊界_61秒是1分_120秒是2分() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        clock.advance(Duration.ofSeconds(61));
        assertThat(body(act("clock-out", "1234")).get("todayTotalMinutes").asInt()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(60));
        act("clock-in", "1234");
        clock.advance(Duration.ofSeconds(120));
        assertThat(body(act("clock-out", "1234")).get("todayTotalMinutes").asInt()).isEqualTo(3); // 1 + 2
    }

    @Test
    void 一天多個班次_累計分鐘數相加() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        clock.advance(Duration.ofHours(3));
        act("clock-out", "1234");
        clock.advance(Duration.ofHours(1));
        act("clock-in", "1234");
        clock.advance(Duration.ofHours(2));
        JsonNode b = body(act("clock-out", "1234"));
        assertThat(b.get("todayShifts")).hasSize(2);
        assertThat(b.get("todayTotalMinutes").asInt()).isEqualTo(300);
    }

    // ---- 上班未打卡 ----

    @Test
    void 沒上班就下班_另記上班未打卡_不計工時() throws Exception {
        clock.set(taipei("2026-03-02T18:00:00"));
        MvcResult r = act("clock-out", "1234");
        assertThat(code(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("state").asText()).isEqualTo("MISSING_CLOCK_IN");
        assertThat(b.get("warnings").get(0).asText()).isEqualTo("MISSING_CLOCK_IN");
        assertThat(b.get("todayTotalMinutes").asInt()).isZero();
        JsonNode shift = b.get("todayShifts").get(0);
        assertThat(shift.get("clockInAt").isNull()).isTrue();
        assertThat(shift.get("clockOutAt").asText()).isEqualTo("2026-03-02T10:00:00Z");
        assertThat(shift.get("minutes").isNull()).isTrue();
        assertThat(shift.get("state").asText()).isEqualTo("MISSING_CLOCK_IN");
    }

    // ---- 下班未打卡 ----

    @Test
    void 前一營業日忘記下班_不擋今天上班_警告並不計工時() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234"); // 沒有下班
        clock.set(taipei("2026-03-03T09:00:00"));
        MvcResult r = act("clock-in", "1234");
        assertThat(code(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("state").asText()).isEqualTo("ON_DUTY");
        assertThat(b.get("warnings").get(0).asText()).isEqualTo("MISSING_CLOCK_OUT");
        assertThat(b.get("todayShifts")).hasSize(1);
        assertThat(b.get("todayTotalMinutes").asInt()).isZero();
    }

    @Test
    void 前一營業日忘記下班後今天直接下班_不會補上昨天的班次() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        clock.set(taipei("2026-03-03T09:00:00"));
        JsonNode b = body(act("clock-out", "1234"));
        assertThat(b.get("state").asText()).isEqualTo("MISSING_CLOCK_IN");
        assertThat(b.get("warnings")).extracting(JsonNode::asText).contains("MISSING_CLOCK_IN", "MISSING_CLOCK_OUT");
        // 昨天的班次仍然開著、沒有被關閉
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift WHERE clock_in_at IS NOT NULL AND clock_out_at IS NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void 營業日以凌晨4點切換_凌晨2點上班屬於前一營業日() throws Exception {
        clock.set(taipei("2026-03-03T02:00:00"));
        JsonNode b = body(act("clock-in", "1234"));
        assertThat(b.get("todayShifts").get(0).get("businessDate").asText()).isEqualTo("2026-03-02");
    }

    @Test
    void 跨營業日切換時間打卡會產生兩筆班次() throws Exception {
        clock.set(taipei("2026-03-02T22:00:00"));
        act("clock-in", "1234");
        clock.set(taipei("2026-03-03T05:00:00")); // 已是下一個營業日
        JsonNode b = body(act("clock-out", "1234"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift", Integer.class)).isEqualTo(2);
        assertThat(b.get("state").asText()).isEqualTo("MISSING_CLOCK_IN");
    }

    // ---- 同時打卡 ----

    @Test
    void 同一員工同時上班打卡只會產生一個班次() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return code(act("clock-in", "1234"));
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift", Integer.class)).isEqualTo(1);
    }

    // ---- 2.4：狀態查詢 ----

    @Test
    void 查詢狀態不打卡_也不受間隔限制() throws Exception {
        clock.set(taipei("2026-03-02T09:00:00"));
        act("clock-in", "1234");
        for (int i = 0; i < 3; i++) {
            MvcResult r = act("clock-status", "1234");
            assertThat(code(r)).isEqualTo(200);
            assertThat(body(r).get("state").asText()).isEqualTo("ON_DUTY");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_shift", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT clock_out_at FROM work_shift", Object.class)).isNull();
    }

    @Test
    void 沒有任何班次時查詢狀態_下一步是上班() throws Exception {
        JsonNode b = body(act("clock-status", "1234"));
        assertThat(b.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(b.get("todayShifts")).isEmpty();
        assertThat(b.get("todayTotalMinutes").asInt()).isZero();
    }

    @Test
    void 查詢狀態必須驗PIN() throws Exception {
        assertThat(code(act("clock-status", "0000"))).isEqualTo(403);
    }

    // ---- 2.4：打卡歷史 ----

    private MvcResult history(String query, String unlockToken) throws Exception {
        var request = get("/api/staff/clock-history" + query).header("X-Device-Token", pos.token());
        if (unlockToken != null) {
            request = request.header("X-Unlock-Token", unlockToken);
        }
        return mvc.perform(request).andReturn();
    }

    private void workOneShift(String pin, String startLocal) throws Exception {
        clock.set(taipei(startLocal));
        act("clock-in", pin);
        clock.advance(Duration.ofHours(1));
        act("clock-out", pin);
    }

    @Test
    void 打卡歷史需要管理者解鎖() throws Exception {
        MvcResult none = history("", null);
        assertThat(code(none)).isEqualTo(403);
        assertThat(errorCode(none)).isEqualTo("FORBIDDEN");
        String wrongFunction = unlock(pos, UnlockFunction.DEVICE_MANAGE);
        assertThat(errorCode(history("", wrongFunction))).isEqualTo("FORBIDDEN");
    }

    @Test
    void 預設天數是2_以營業日計含今天() throws Exception {
        workOneShift("1234", "2026-02-28T09:00:00"); // 前天之前
        workOneShift("1234", "2026-03-01T09:00:00"); // 昨天
        workOneShift("1234", "2026-03-02T09:00:00"); // 今天
        clock.set(taipei("2026-03-02T20:00:00"));
        String token = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        JsonNode b = body(history("", token));
        assertThat(b.get("days").asInt()).isEqualTo(2);
        assertThat(b.get("fromBusinessDate").asText()).isEqualTo("2026-03-01");
        assertThat(b.get("toBusinessDate").asText()).isEqualTo("2026-03-02");
        assertThat(b.get("shifts")).hasSize(2);
        assertThat(b.get("shifts").get(0).get("businessDate").asText()).isEqualTo("2026-03-02"); // 新到舊
    }

    @Test
    void 天數1只含今天_31含30天前() throws Exception {
        workOneShift("1234", "2026-02-01T09:00:00"); // 29 天前
        workOneShift("1234", "2026-03-02T09:00:00");
        clock.set(taipei("2026-03-02T20:00:00"));
        String token = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        assertThat(body(history("?days=1", token)).get("shifts")).hasSize(1);
        assertThat(body(history("?days=31", token)).get("shifts")).hasSize(2);
    }

    @Test
    void 天數不合法回VALIDATION_ERROR() throws Exception {
        String token = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        for (String days : new String[] {"0", "32", "-1", "abc"}) {
            MvcResult r = history("?days=" + days, token);
            assertThat(code(r)).as(days).isEqualTo(400);
            assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void 可依員工篩選_不存在的員工回404() throws Exception {
        long other = createEmployee("小華", "STAFF", "5678");
        workOneShift("1234", "2026-03-02T09:00:00");
        clock.advance(Duration.ofMinutes(5));
        act("clock-in", "5678");
        String token = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        JsonNode mine = body(history("?employeeId=" + xiaoMing, token));
        assertThat(mine.get("shifts")).hasSize(1);
        assertThat(mine.get("shifts").get(0).get("employeeName").asText()).isEqualTo("小明");
        JsonNode theirs = body(history("?employeeId=" + other, token));
        assertThat(theirs.get("shifts")).hasSize(1);
        assertThat(theirs.get("shifts").get(0).get("state").asText()).isEqualTo("ON_DUTY");
        MvcResult none = history("?employeeId=999999", token);
        assertThat(code(none)).isEqualTo(404);
        assertThat(errorCode(none)).isEqualTo("EMPLOYEE_NOT_FOUND");
    }

    @Test
    void 歷史包含已停用員工_名稱仍顯示_且標示班次狀態() throws Exception {
        workOneShift("1234", "2026-03-01T09:00:00");
        clock.advance(Duration.ofMinutes(2));
        act("clock-in", "1234"); // 下班之後又上班但沒下班：隔天視角會變成「下班未打卡」
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", xiaoMing);
        clock.set(taipei("2026-03-02T10:00:00"));
        String token = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE);
        JsonNode shifts = body(history("?days=2", token)).get("shifts");
        assertThat(shifts).hasSize(2);
        for (JsonNode s : shifts) {
            assertThat(s.get("employeeName").asText()).isEqualTo("小明");
        }
        assertThat(shifts).extracting(s -> s.get("state").asText()).contains("COMPLETE", "MISSING_CLOCK_OUT");
    }
}
