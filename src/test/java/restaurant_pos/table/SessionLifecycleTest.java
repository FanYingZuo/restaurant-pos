package restaurant_pos.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;

/** 卡 4.5：生命週期方法（結束、取消、兩種重開）與待重新結帳清單（01-PLAN S4）。 */
class SessionLifecycleTest extends TableTestBase {

    @Autowired
    TableSessionService service;

    @Autowired
    TransactionTemplate tx;

    private Map<String, Object> row(long sessionId) {
        return jdbc.queryForMap("SELECT status, occupying_table_id, pending_recheckout, checkout_in_progress, closed_at "
                + "FROM table_session WHERE id = ?", sessionId);
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(expected);
    }

    private long openSession(String tableNo) throws Exception {
        return open(table(tableNo), 2).get("id").asLong();
    }

    // ---- 要求既有交易 ----

    @Test
    void 生命週期方法必須在既有交易內呼叫() throws Exception {
        long id = openSession("A1");
        assertThatThrownBy(() -> service.lockById(id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.close(id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.reopen(id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.setCheckoutInProgress(id, true)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(row(id)).containsEntry("status", "OPEN");
    }

    @Test
    void 依編號鎖定回傳唯讀檢視_不存在回SESSION_NOT_FOUND() throws Exception {
        long id = openSession("A1");
        SessionView view = tx.execute(s -> service.lockById(id));
        assertThat(view.id()).isEqualTo(id);
        assertThat(view.billNo()).isEqualTo(1);
        assertThat(view.status()).isEqualTo(SessionStatus.OPEN);
        assertThat(view.occupiesTable()).isTrue();
        assertThat(view.toString()).doesNotContain(jdbc.queryForObject("SELECT token FROM table_session", String.class));
        assertCode(() -> tx.executeWithoutResult(s -> service.lockById(999999)), ErrorCode.SESSION_NOT_FOUND);
    }

    // ---- 結束 ----

    @Test
    void 結束用餐釋放桌位() throws Exception {
        long t = table("A1");
        long id = open(t, 2).get("id").asLong();
        tx.executeWithoutResult(s -> {
            service.lockById(id);
            service.close(id);
        });
        Map<String, Object> r = row(id);
        assertThat(r).containsEntry("status", "CLOSED").containsEntry("occupying_table_id", null);
        assertThat(r.get("closed_at")).isNotNull();
        assertThat(open(t, 2).get("billNo").asText()).isEqualTo("002"); // 桌位已可再開
        assertThat(events.cancelled).isEmpty();
    }

    @Test
    void 只有使用中可以結束或取消() throws Exception {
        long id = openSession("A1");
        tx.executeWithoutResult(s -> {
            service.lockById(id);
            service.close(id);
        });
        assertCode(() -> tx.executeWithoutResult(s -> service.close(id)), ErrorCode.SESSION_NOT_OPEN);
        assertCode(() -> tx.executeWithoutResult(s -> service.cancel(id)), ErrorCode.SESSION_NOT_OPEN);
        assertCode(() -> tx.executeWithoutResult(s -> service.close(999999)), ErrorCode.SESSION_NOT_FOUND);
    }

    // ---- 取消 ----

    @Test
    void 取消用餐釋放桌位_帳單號不回收_發佈用餐取消事件() throws Exception {
        long t = table("A1");
        long id = open(t, 2).get("id").asLong();
        tx.executeWithoutResult(s -> {
            service.lockById(id);
            service.cancel(id);
        });
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("occupying_table_id", null);
        assertThat(events.cancelled).containsExactly(id);
        assertThat(open(t, 2).get("billNo").asText()).isEqualTo("002");
    }

    @Test
    void 取消的交易回滾時不發佈事件也不改狀態() throws Exception {
        long id = openSession("A1");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            service.lockById(id);
            service.cancel(id);
            throw new IllegalStateException("之後的業務失敗");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(row(id)).containsEntry("status", "OPEN");
        assertThat(events.cancelled).isEmpty();
    }

    // ---- 占桌重開 ----

    @Test
    void 原桌空著_占桌重開() throws Exception {
        long t = table("A1");
        long id = open(t, 2).get("id").asLong();
        tx.executeWithoutResult(s -> {
            service.lockById(id);
            service.close(id);
        });
        SessionView view = tx.execute(s -> {
            service.lockById(id);
            return service.reopen(id);
        });
        assertThat(view.status()).isEqualTo(SessionStatus.OPEN);
        assertThat(view.occupiesTable()).isTrue();
        assertThat(view.pendingRecheckout()).isFalse();
        assertThat(row(id)).containsEntry("occupying_table_id", t).containsEntry("pending_recheckout", false);
        assertThat(row(id).get("closed_at")).isNull();
        assertThat(errorCode(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pos/tables/" + t + "/open"), "{\"guestCount\":2}")))
                .isEqualTo("TABLE_OCCUPIED");
    }

    // ---- 不占桌重開 ----

    @Test
    void 原桌已被使用_不占桌重開並標記待重新結帳() throws Exception {
        long t = table("A1");
        long first = open(t, 2).get("id").asLong();
        tx.executeWithoutResult(s -> {
            service.lockById(first);
            service.close(first);
        });
        long second = open(t, 3).get("id").asLong(); // 原桌被新的客人使用
        SessionView view = tx.execute(s -> {
            service.lockById(first);
            return service.reopen(first);
        });
        assertThat(view.status()).isEqualTo(SessionStatus.OPEN);
        assertThat(view.occupiesTable()).isFalse();
        assertThat(view.pendingRecheckout()).isTrue();
        assertThat(row(first)).containsEntry("occupying_table_id", null).containsEntry("pending_recheckout", true);
        // 新客人仍占著桌位
        assertThat(row(second)).containsEntry("occupying_table_id", t);
        assertThat(tableList().get(0).get("session").get("id").asLong()).isEqualTo(second);
    }

    @Test
    void 原桌已刪除_不占桌重開_清單仍顯示桌號() throws Exception {
        long t = table("A1");
        long id = open(t, 2).get("id").asLong();
        tx.executeWithoutResult(s -> {
            service.lockById(id);
            service.close(id);
        });
        layout(delete("/api/pos/tables/" + t), null);
        SessionView view = tx.execute(s -> {
            service.lockById(id);
            return service.reopen(id);
        });
        assertThat(view.occupiesTable()).isFalse();
        assertThat(view.pendingRecheckout()).isTrue();
        JsonNode pending = body(call(get("/api/pos/sessions/pending-recheckout"), null));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("tableNo").asText()).isEqualTo("A1");
        assertThat(pending.get(0).get("occupiesTable").asBoolean()).isFalse();
        assertThat(pending.get(0).get("pendingRecheckout").asBoolean()).isTrue();
        assertThat(pending.get(0).get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void 只有已結束的用餐可以重開() throws Exception {
        long id = openSession("A1");
        assertCode(() -> tx.executeWithoutResult(s -> service.reopen(id)), ErrorCode.SESSION_NOT_OPEN);
        tx.executeWithoutResult(s -> {
            service.lockById(id);
            service.cancel(id);
        });
        assertCode(() -> tx.executeWithoutResult(s -> service.reopen(id)), ErrorCode.SESSION_NOT_OPEN);
        assertCode(() -> tx.executeWithoutResult(s -> service.reopen(999999)), ErrorCode.SESSION_NOT_FOUND);
    }

    // ---- 資料庫保證 ----

    @Test
    void 每桌同時最多一筆占桌的使用中紀錄_資料庫唯一索引保證() throws Exception {
        long t = table("A1");
        open(t, 2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO table_session (table_id, business_date, bill_no, status, "
                + "occupying_table_id, guest_count, token, qr_expires_at, service_charge_enabled, checkout_in_progress, "
                + "pending_recheckout, opened_at) VALUES (?, '2026-03-02', 77, 'OPEN', ?, 1, 'other-token', NOW(6), TRUE, FALSE, FALSE, NOW(6))", t, t))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        // 不占桌的使用中紀錄可以有很多筆
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO table_session (table_id, business_date, bill_no, status, occupying_table_id, guest_count, "
                    + "token, qr_expires_at, service_charge_enabled, checkout_in_progress, pending_recheckout, opened_at) "
                    + "VALUES (?, '2026-03-02', ?, 'OPEN', NULL, 1, ?, NOW(6), TRUE, FALSE, TRUE, NOW(6))", t, 100 + i, "np-token-" + i);
        }
    }

    // ---- 結帳旗標與可編輯檢查 ----

    @Test
    void 結帳旗標可設定與清除() throws Exception {
        long id = openSession("A1");
        tx.executeWithoutResult(s -> service.setCheckoutInProgress(id, true));
        assertThat(row(id)).containsEntry("checkout_in_progress", true);
        tx.executeWithoutResult(s -> service.setCheckoutInProgress(id, false));
        assertThat(row(id)).containsEntry("checkout_in_progress", false);
    }

    @Test
    void 鎖定並要求可編輯_依序檢查使用中_待重新結帳_結帳中() throws Exception {
        long id = openSession("A1");
        assertThat(tx.execute(s -> service.lockEditable(id)).id()).isEqualTo(id);
        jdbc.update("UPDATE table_session SET checkout_in_progress = TRUE, pending_recheckout = TRUE WHERE id = ?", id);
        assertCode(() -> tx.executeWithoutResult(s -> service.lockEditable(id)), ErrorCode.SESSION_PENDING_RECHECKOUT);
        jdbc.update("UPDATE table_session SET pending_recheckout = FALSE WHERE id = ?", id);
        assertCode(() -> tx.executeWithoutResult(s -> service.lockEditable(id)), ErrorCode.CHECKOUT_IN_PROGRESS);
        jdbc.update("UPDATE table_session SET status = 'CLOSED' WHERE id = ?", id);
        assertCode(() -> tx.executeWithoutResult(s -> service.lockEditable(id)), ErrorCode.SESSION_NOT_OPEN);
    }

    // ---- 待重新結帳清單 ----

    @Test
    void 待重新結帳清單只含使用中且標記待重新結帳的紀錄() throws Exception {
        assertThat(body(call(get("/api/pos/sessions/pending-recheckout"), null))).isEmpty();
        long a = openSession("A1");
        long b = openSession("A2");
        jdbc.update("UPDATE table_session SET occupying_table_id = NULL, pending_recheckout = TRUE WHERE id = ?", a);
        JsonNode list = body(call(get("/api/pos/sessions/pending-recheckout"), null));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(a);
        assertThat(list.get(0).get("billNo").asText()).isEqualTo("001");
        assertThat(b).isNotEqualTo(a);
        // 已取消（例如結束不收費）後離開清單
        tx.executeWithoutResult(s -> {
            service.lockById(a);
            service.cancel(a);
        });
        assertThat(body(call(get("/api/pos/sessions/pending-recheckout"), null))).isEmpty();
    }
}
