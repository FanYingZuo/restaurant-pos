package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditLog;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.push.Pusher;
import restaurant_pos.shared.security.DeviceContext;
import restaurant_pos.shared.security.DevicePrincipal;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.support.AbstractIntegrationTest;
import restaurant_pos.support.AuditProbe;
import restaurant_pos.support.RecordingPusher;
import restaurant_pos.support.TestSupportConfiguration;

/** 卡 0.8：事件介面、推播器介面、稽核元件與 audit_log（01-PLAN §4.5、§4.7）。 */
class AuditEventPusherTest extends AbstractIntegrationTest {

    @Autowired
    AuditProbe probe;

    @Autowired
    AuditProbe.ProbeListener listener;

    @Autowired
    AuditService auditService;

    @Autowired
    Pusher pusher;

    @BeforeEach
    void reset() {
        listener.reset();
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
    }

    // ---- 稽核與業務同交易 ----

    @Test
    void 提交時稽核寫入且欄位正確() {
        probe.commitWithAudit("probe.commit");
        Map<String, Object> row = jdbc.queryForMap("SELECT action, target_type, target_id, before_value, after_value, reason, actor_device_id, actor_employee_id, "
                + "DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:%s') AS occurred_text, CAST(business_date AS CHAR) AS business_text FROM audit_log");
        assertThat(row.get("action")).isEqualTo("probe.commit");
        assertThat(row.get("target_type")).isEqualTo("probe");
        assertThat(row.get("target_id")).isEqualTo("42");
        assertThat(row.get("before_value")).isEqualTo("舊值");
        assertThat(row.get("after_value")).isEqualTo("新值");
        assertThat(row.get("reason")).isEqualTo("測試理由");
        assertThat(row.get("actor_device_id")).isNull();
        assertThat(row.get("actor_employee_id")).isNull();
        // 時間與營業日來自注入的時鐘：UTC 2026-03-02 00:00 ＝ 台北 08:00 ＝ 營業日 2026-03-02
        assertThat(row.get("occurred_text")).isEqualTo("2026-03-02 00:00:00");
        assertThat(row.get("business_text")).isEqualTo("2026-03-02");
    }

    @Test
    void 業務回滾時稽核一起回滾() {
        assertThatThrownBy(() -> probe.rollbackWithAudit("probe.rollback")).isInstanceOf(IllegalStateException.class);
        assertThat(auditCount()).isZero();
    }

    @Test
    void 稽核必須在既有交易內呼叫() {
        assertThatThrownBy(() -> auditService.record(AuditEntry.of("probe.outside", "probe", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(auditCount()).isZero();
    }

    @Test
    void 需要獨立保留的稽核_業務回滾後仍存在() {
        assertThatThrownBy(() -> probe.rollbackWithIndependentAudit("probe.independent"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void 稽核實體只新增_沒有任何修改方法() {
        List<String> setters = Arrays.stream(AuditLog.class.getDeclaredMethods())
                .map(Method::getName).filter(n -> n.startsWith("set") || n.startsWith("change")).toList();
        assertThat(setters).isEmpty();
    }

    @Test
    void 裝置編號未指定時取目前請求的裝置() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceContext.ATTRIBUTE, new DevicePrincipal(7L, DeviceType.POS));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        probe.commitWithAudit("probe.device");
        assertThat(jdbc.queryForObject("SELECT actor_device_id FROM audit_log", Long.class)).isEqualTo(7L);
    }

    @Test
    void 明確指定的裝置編號優先() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceContext.ATTRIBUTE, new DevicePrincipal(7L, DeviceType.POS));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).executeWithoutResult(s -> auditService.record(
                        AuditEntry.of("probe.explicit", "probe", 1).byDevice(9L).byEmployee(3L)));
        Map<String, Object> row = jdbc.queryForMap("SELECT actor_device_id, actor_employee_id FROM audit_log");
        assertThat(row.get("actor_device_id")).isEqualTo(9L);
        assertThat(row.get("actor_employee_id")).isEqualTo(3L);
    }

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    // ---- 事件 ----

    @Test
    void 提交後事件到達提交後監聽器() {
        probe.commitWithAudit("probe.event");
        assertThat(listener.beforeCommit).containsExactly("probe.event");
        assertThat(listener.afterCommit).containsExactly("probe.event");
    }

    @Test
    void 回滾時提交後監聽器不會收到事件() {
        assertThatThrownBy(() -> probe.rollbackWithAudit("probe.event.rollback"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(listener.afterCommit).isEmpty();
    }

    @Test
    void 提交前監聽器丟例外時整個交易回滾() {
        listener.failBeforeCommit = true;
        assertThatThrownBy(() -> probe.commitWithAudit("probe.before.fail")).isInstanceOf(RuntimeException.class);
        assertThat(auditCount()).isZero();
        assertThat(listener.afterCommit).isEmpty();
    }

    // ---- 推播器介面 ----

    @Test
    void 推播器可被測試替身取代() {
        assertThat(pusher).isInstanceOf(RecordingPusher.class);
        RecordingPusher recording = (RecordingPusher) pusher;
        recording.clear();
        pusher.push("/topic/kds/K1", Map.of("type", "BOARD_RELOAD"));
        assertThat(recording.sent()).hasSize(1);
        assertThat(recording.sent().get(0).topic()).isEqualTo("/topic/kds/K1");
    }

    @Test
    void 稽核日期欄位使用營業日而非日曆日() {
        // 台北 2026-03-02 02:00（凌晨）仍屬營業日 2026-03-01
        clock.set(Instant.parse("2026-03-01T18:00:00Z"));
        probe.commitWithAudit("probe.cutover");
        assertThat(jdbc.queryForObject("SELECT business_date FROM audit_log", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        clock.set(TestSupportConfiguration.DEFAULT_NOW);
    }
}
