package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.6：員工管理（EMPLOYEE_MANAGE，01-PLAN S2）。 */
class EmployeeManagementTest extends AbstractIntegrationTest {

    private TestDevice pos;
    private String token;
    private long managerId;

    @BeforeEach
    void setUp() {
        pos = registerDevice(DeviceType.POS);
        managerId = createEmployee("測試店長", "MANAGER", MANAGER_PIN);
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

    private MvcResult create(String name, String role, String pin) throws Exception {
        return call(post("/api/staff/employees"), "{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"pin\":\"" + pin + "\"}");
    }

    // ---- 權限 ----

    @Test
    void 沒有解鎖或功能不符_所有員工管理API都回FORBIDDEN() throws Exception {
        long id = createEmployee("小明", "STAFF", "1234");
        String wrongFunction = unlock(pos, UnlockFunction.TABLE_LAYOUT);
        for (String t : new String[] {null, wrongFunction}) {
            assertThat(errorCode(call(get("/api/staff/employees"), t, null))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(post("/api/staff/employees"), t, "{\"name\":\"a\",\"role\":\"STAFF\",\"pin\":\"1111\"}"))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(patch("/api/staff/employees/" + id), t, "{\"name\":\"b\"}"))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(put("/api/staff/employees/" + id + "/pin"), t, "{\"pin\":\"2222\"}"))).isEqualTo("FORBIDDEN");
            assertThat(errorCode(call(post("/api/staff/employees/" + id + "/deactivate"), t, null))).isEqualTo("FORBIDDEN");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee", Integer.class)).isEqualTo(2);
    }

    // ---- 列表 ----

    @Test
    void 列表含已停用員工_且不回PIN或雜湊() throws Exception {
        long id = createEmployee("離職者", "STAFF", "2222");
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", id);
        MvcResult r = call(get("/api/staff/employees"), null);
        assertThat(status(r)).isEqualTo(200);
        JsonNode list = body(r);
        assertThat(list).hasSize(2);
        JsonNode inactive = list.get(1);
        assertThat(inactive.get("name").asText()).isEqualTo("離職者");
        assertThat(inactive.get("active").asBoolean()).isFalse();
        assertThat(list.get(0).get("role").asText()).isEqualTo("MANAGER");
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(raw).doesNotContain("pin").doesNotContain("hash").doesNotContain(MANAGER_PIN);
        List<String> fields = new ArrayList<>();
        list.get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "name", "role", "active", "createdAt");
    }

    // ---- 新增 ----

    @Test
    void 新增員工_PIN存雜湊() throws Exception {
        MvcResult r = create("小明", "STAFF", "1234");
        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r).get("name").asText()).isEqualTo("小明");
        assertThat(body(r).get("role").asText()).isEqualTo("STAFF");
        assertThat(body(r).get("active").asBoolean()).isTrue();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("1234");
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM employee WHERE name = '小明'", String.class))
                .isEqualTo(hasher.pin("1234"));
    }

    @Test
    void 名稱前後空白會去除() throws Exception {
        assertThat(body(create("  小明  ", "STAFF", "1234")).get("name").asText()).isEqualTo("小明");
    }

    @Test
    void PIN與在職員工重複回PIN_ALREADY_USED() throws Exception {
        create("甲", "STAFF", "1111");
        MvcResult r = create("乙", "STAFF", "1111");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("PIN_ALREADY_USED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE name = '乙'", Integer.class)).isZero();
    }

    @Test
    void 已停用員工原本的PIN可被新員工使用() throws Exception {
        long id = body(create("離職者", "STAFF", "3333")).get("id").asLong();
        call(post("/api/staff/employees/" + id + "/deactivate"), null);
        assertThat(status(create("新人", "STAFF", "3333"))).isEqualTo(201);
    }

    @Test
    void 新增的輸入驗證() throws Exception {
        assertThat(errorCode(create("", "STAFF", "1111"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(create("   ", "STAFF", "1111"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(create("x".repeat(51), "STAFF", "1111"))).isEqualTo("VALIDATION_ERROR");
        assertThat(status(create("x".repeat(50), "STAFF", "1112"))).isEqualTo(201);
        assertThat(errorCode(create("甲", "BOSS", "1111"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(create("甲", "STAFF", "123"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(create("甲", "STAFF", "12a4"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(post("/api/staff/employees"), "{\"name\":\"甲\",\"pin\":\"1111\"}"))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 同時新增相同PIN只有一個成功() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            results.add(pool.submit(() -> {
                start.await();
                return status(create("員工" + n, "STAFF", "7777"));
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) {
                created++;
            } else {
                assertThat(s).isEqualTo(409);
            }
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE pin_hash = ?", Integer.class, hasher.pin("7777"))).isEqualTo(1);
    }

    // ---- 改名與角色 ----

    @Test
    void 改名與改角色() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        MvcResult r = call(patch("/api/staff/employees/" + id), "{\"name\":\"大明\",\"role\":\"MANAGER\"}");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("name").asText()).isEqualTo("大明");
        assertThat(body(r).get("role").asText()).isEqualTo("MANAGER");
        MvcResult nameOnly = call(patch("/api/staff/employees/" + id), "{\"name\":\"小小明\"}");
        assertThat(body(nameOnly).get("role").asText()).isEqualTo("MANAGER");
    }

    @Test
    void 改名改角色的錯誤() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        assertThat(errorCode(call(patch("/api/staff/employees/" + id), "{}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(patch("/api/staff/employees/" + id), "{\"name\":\"\"}"))).isEqualTo("VALIDATION_ERROR");
        MvcResult notFound = call(patch("/api/staff/employees/999999"), "{\"name\":\"a\"}");
        assertThat(status(notFound)).isEqualTo(404);
        assertThat(errorCode(notFound)).isEqualTo("EMPLOYEE_NOT_FOUND");
    }

    @Test
    void 最後一位管理者不可降級() throws Exception {
        MvcResult r = call(patch("/api/staff/employees/" + managerId), "{\"role\":\"STAFF\"}");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("LAST_MANAGER");
        assertThat(jdbc.queryForObject("SELECT role FROM employee WHERE id = ?", String.class, managerId)).isEqualTo("MANAGER");
    }

    @Test
    void 有兩位管理者時可降級其中一位() throws Exception {
        long second = body(create("副店長", "MANAGER", "4444")).get("id").asLong();
        assertThat(status(call(patch("/api/staff/employees/" + second), "{\"role\":\"STAFF\"}"))).isEqualTo(200);
        MvcResult last = call(patch("/api/staff/employees/" + managerId), "{\"role\":\"STAFF\"}");
        assertThat(errorCode(last)).isEqualTo("LAST_MANAGER");
    }

    @Test
    void 管理者改名不受最後一位限制() throws Exception {
        assertThat(status(call(patch("/api/staff/employees/" + managerId), "{\"name\":\"新店長名\"}"))).isEqualTo(200);
    }

    // ---- 換 PIN ----

    @Test
    void 換PIN後新PIN有效_舊PIN失效() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        MvcResult r = call(put("/api/staff/employees/" + id + "/pin"), "{\"pin\":\"5678\"}");
        assertThat(status(r)).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM employee WHERE id = ?", String.class, id)).isEqualTo(hasher.pin("5678"));
        MvcResult newPin = mvc.perform(post("/api/pos/test-pin").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"5678\"}")).andReturn();
        assertThat(status(newPin)).isEqualTo(200);
        MvcResult oldPin = mvc.perform(post("/api/pos/test-pin").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}")).andReturn();
        assertThat(errorCode(oldPin)).isEqualTo("INVALID_PIN");
    }

    @Test
    void 換成與其他在職員工相同的PIN回PIN_ALREADY_USED() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        create("小華", "STAFF", "5678");
        MvcResult r = call(put("/api/staff/employees/" + id + "/pin"), "{\"pin\":\"5678\"}");
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("PIN_ALREADY_USED");
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM employee WHERE id = ?", String.class, id)).isEqualTo(hasher.pin("1234"));
    }

    @Test
    void 換成自己目前的PIN不算重複() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        assertThat(status(call(put("/api/staff/employees/" + id + "/pin"), "{\"pin\":\"1234\"}"))).isEqualTo(204);
    }

    @Test
    void 換PIN的錯誤() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        assertThat(errorCode(call(put("/api/staff/employees/" + id + "/pin"), "{\"pin\":\"12\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(put("/api/staff/employees/999999/pin"), "{\"pin\":\"9876\"}"))).isEqualTo("EMPLOYEE_NOT_FOUND");
        call(post("/api/staff/employees/" + id + "/deactivate"), null);
        assertThat(errorCode(call(put("/api/staff/employees/" + id + "/pin"), "{\"pin\":\"9876\"}"))).isEqualTo("EMPLOYEE_NOT_FOUND");
    }

    // ---- 停用 ----

    @Test
    void 停用員工_清空PIN雜湊_之後驗PIN回INVALID_PIN() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        MvcResult r = call(post("/api/staff/employees/" + id + "/deactivate"), null);
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM employee WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT deactivated_at FROM employee WHERE id = ?", Object.class, id)).isNotNull();
        MvcResult pin = mvc.perform(post("/api/pos/test-pin").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}")).andReturn();
        assertThat(errorCode(pin)).isEqualTo("INVALID_PIN");
    }

    @Test
    void 重複停用是冪等的() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        call(post("/api/staff/employees/" + id + "/deactivate"), null);
        assertThat(status(call(post("/api/staff/employees/" + id + "/deactivate"), null))).isEqualTo(200);
    }

    @Test
    void 最後一位在職管理者不可停用() throws Exception {
        MvcResult r = call(post("/api/staff/employees/" + managerId + "/deactivate"), null);
        assertThat(status(r)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("LAST_MANAGER");
        assertThat(jdbc.queryForObject("SELECT deactivated_at FROM employee WHERE id = ?", Object.class, managerId)).isNull();
    }

    @Test
    void 停用不存在的員工回404() throws Exception {
        assertThat(errorCode(call(post("/api/staff/employees/999999/deactivate"), null))).isEqualTo("EMPLOYEE_NOT_FOUND");
    }

    @Test
    void 已停用的員工不能打卡() throws Exception {
        long id = body(create("小明", "STAFF", "1234")).get("id").asLong();
        call(post("/api/staff/employees/" + id + "/deactivate"), null);
        MvcResult r = mvc.perform(post("/api/staff/clock-in").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}")).andReturn();
        assertThat(errorCode(r)).isEqualTo("INVALID_PIN");
    }

    @Test
    void 同時停用兩位管理者_至少保留一位在職() throws Exception {
        // 有兩位管理者（持有解鎖的店長與副店長），兩個執行緒同時互相停用
        long second = body(create("副店長", "MANAGER", "4444")).get("id").asLong();
        String secondToken = unlockAs(second);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> a = pool.submit(() -> {
            start.await();
            return status(call(post("/api/staff/employees/" + second + "/deactivate"), token, null));
        });
        Future<Integer> b = pool.submit(() -> {
            start.await();
            return status(call(post("/api/staff/employees/" + managerId + "/deactivate"), secondToken, null));
        });
        start.countDown();
        int sa = a.get();
        int sb = b.get();
        pool.shutdown();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE role = 'MANAGER' AND deactivated_at IS NULL", Integer.class))
                .as("狀態 %d / %d", sa, sb).isGreaterThanOrEqualTo(1);
    }

    private String unlockAs(long employeeId) throws Exception {
        MvcResult r = mvc.perform(post("/api/staff/unlock").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"4444\",\"function\":\"EMPLOYEE_MANAGE\"}")).andReturn();
        return body(r).get("unlockToken").asText();
    }
}
