package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.9：稽核查詢 API（EMPLOYEE_MANAGE，依日期與動作篩選，01-PLAN §4.4 分頁）。 */
class AuditQueryTest extends AbstractIntegrationTest {

    private TestDevice pos;
    private String token;

    @BeforeEach
    void setUp() {
        pos = registerDevice(DeviceType.POS);
        token = unlock(pos, UnlockFunction.EMPLOYEE_MANAGE); // 會寫一筆 ADMIN_UNLOCKED，之後的查詢都用 TEST_ 動作篩選
        for (int i = 1; i <= 25; i++) {
            String action = i % 2 == 1 ? "TEST_A" : "TEST_B";
            String date = i <= 10 ? "2026-03-01" : i <= 20 ? "2026-03-02" : "2026-03-03";
            jdbc.update("INSERT INTO audit_log (occurred_at, business_date, action, actor_employee_id, actor_device_id, "
                    + "target_type, target_id, before_value, after_value, reason) VALUES (NOW(6), ?, ?, 7, ?, 'thing', ?, '舊', '新', '理由')",
                    date, action, pos.id(), String.valueOf(i));
        }
    }

    private MvcResult query(String query, String unlockToken) throws Exception {
        var request = get("/api/staff/audit-logs" + query).header("X-Device-Token", pos.token());
        if (unlockToken != null) {
            request = request.header("X-Unlock-Token", unlockToken);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<String> targetIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(n.get("targetId").asText()));
        return ids;
    }

    @Test
    void 需要EMPLOYEE_MANAGE解鎖() throws Exception {
        assertThat(body(query("", null)).get("code").asText()).isEqualTo("FORBIDDEN");
        String wrong = unlock(pos, UnlockFunction.TABLE_LAYOUT);
        assertThat(body(query("", wrong)).get("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void 預設每頁10筆_依ID遞增_含分頁資訊() throws Exception {
        JsonNode page = body(query("?action=TEST_A", token));
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(10);
        assertThat(page.get("totalElements").asLong()).isEqualTo(13);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("content")).hasSize(10);
        assertThat(targetIds(page)).containsExactly("1", "3", "5", "7", "9", "11", "13", "15", "17", "19");
    }

    @Test
    void 第二頁取剩下的() throws Exception {
        JsonNode page = body(query("?action=TEST_A&page=1", token));
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(targetIds(page)).containsExactly("21", "23", "25");
    }

    @Test
    void 每頁最大100_小於1用預設_頁碼負數視為0() throws Exception {
        assertThat(body(query("?action=TEST_A&size=500", token)).get("size").asInt()).isEqualTo(100);
        assertThat(body(query("?action=TEST_A&size=0", token)).get("size").asInt()).isEqualTo(10);
        assertThat(body(query("?action=TEST_A&size=-3", token)).get("size").asInt()).isEqualTo(10);
        assertThat(body(query("?action=TEST_A&page=-5", token)).get("page").asInt()).isZero();
        assertThat(body(query("?action=TEST_A&size=5", token)).get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void 依動作篩選_完全相符() throws Exception {
        JsonNode b = body(query("?action=TEST_B&size=100", token));
        assertThat(b.get("totalElements").asLong()).isEqualTo(12);
        for (JsonNode n : b.get("content")) {
            assertThat(n.get("action").asText()).isEqualTo("TEST_B");
        }
        assertThat(body(query("?action=TEST", token)).get("totalElements").asLong()).isZero();
        MvcResult spaced = mvc.perform(get("/api/staff/audit-logs").header("X-Device-Token", pos.token())
                .header("X-Unlock-Token", token).param("action", " TEST_B ").param("size", "100")).andReturn();
        assertThat(body(spaced).get("totalElements").asLong()).isEqualTo(12);
    }

    @Test
    void 依營業日區間篩選_起訖都含() throws Exception {
        assertThat(body(query("?action=TEST_A&from=2026-03-02&to=2026-03-02&size=100", token)).get("totalElements").asLong()).isEqualTo(5);
        assertThat(body(query("?action=TEST_A&from=2026-03-02&size=100", token)).get("totalElements").asLong()).isEqualTo(8);
        assertThat(body(query("?action=TEST_A&to=2026-03-01&size=100", token)).get("totalElements").asLong()).isEqualTo(5);
        assertThat(body(query("?action=TEST_A&from=2026-03-01&to=2026-03-03&size=100", token)).get("totalElements").asLong()).isEqualTo(13);
    }

    @Test
    void 沒有符合的紀錄回空內容() throws Exception {
        JsonNode b = body(query("?action=NO_SUCH_ACTION", token));
        assertThat(b.get("content")).isEmpty();
        assertThat(b.get("totalElements").asLong()).isZero();
        assertThat(b.get("totalPages").asInt()).isZero();
    }

    @Test
    void 起日晚於訖日回INVALID_DATE_RANGE() throws Exception {
        MvcResult r = query("?from=2026-03-03&to=2026-03-01", token);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r).get("code").asText()).isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    void 日期格式不合法或動作過長回VALIDATION_ERROR() throws Exception {
        for (String q : new String[] {"?from=3/1/26", "?to=昨天", "?from=2026-13-01", "?action=" + "x".repeat(65), "?page=abc"}) {
            MvcResult r = query(q, token);
            assertThat(r.getResponse().getStatus()).as(q).isEqualTo(400);
            assertThat(body(r).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void 回應欄位完整() throws Exception {
        JsonNode first = body(query("?action=TEST_A&size=1", token)).get("content").get(0);
        List<String> fields = new ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "occurredAt", "businessDate", "action", "targetType",
                "targetId", "beforeValue", "afterValue", "employeeId", "deviceId", "reason");
        assertThat(first.get("businessDate").asText()).isEqualTo("2026-03-01");
        assertThat(first.get("beforeValue").asText()).isEqualTo("舊");
        assertThat(first.get("afterValue").asText()).isEqualTo("新");
        assertThat(first.get("reason").asText()).isEqualTo("理由");
        assertThat(first.get("employeeId").asLong()).isEqualTo(7);
        assertThat(first.get("deviceId").asLong()).isEqualTo(pos.id());
    }

    @Test
    void 解鎖本身也會出現在稽核中() throws Exception {
        JsonNode b = body(query("?action=ADMIN_UNLOCKED", token));
        assertThat(b.get("totalElements").asLong()).isEqualTo(1);
        assertThat(b.get("content").get(0).get("targetId").asText()).isEqualTo("EMPLOYEE_MANAGE");
    }
}
