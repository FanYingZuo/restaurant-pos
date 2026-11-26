package restaurant_pos.table;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 用餐紀錄（table_session）。顧客 token 是機密：不提供任何會輸出它的 toString 或 DTO 欄位。
 * 「占桌」＝ {@code occupyingTableId} 有值；其唯一索引保證每桌同時最多一筆占桌的使用中紀錄。
 */
@Entity
@Table(name = "table_session")
public class TableSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "bill_no", nullable = false)
    private int billNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionStatus status;

    @Column(name = "occupying_table_id")
    private Long occupyingTableId;

    @Column(name = "guest_count", nullable = false)
    private int guestCount;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(name = "qr_expires_at", nullable = false)
    private Instant qrExpiresAt;

    @Column(name = "service_charge_enabled", nullable = false)
    private boolean serviceChargeEnabled;

    @Column(name = "checkout_in_progress", nullable = false)
    private boolean checkoutInProgress;

    @Column(name = "pending_recheckout", nullable = false)
    private boolean pendingRecheckout;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected TableSession() {
    }

    public TableSession(Long tableId, LocalDate businessDate, int billNo, int guestCount, String token,
            Instant qrExpiresAt, boolean serviceChargeEnabled, Instant openedAt) {
        this.tableId = tableId;
        this.businessDate = businessDate;
        this.billNo = billNo;
        this.status = SessionStatus.OPEN;
        this.occupyingTableId = tableId;
        this.guestCount = guestCount;
        this.token = token;
        this.qrExpiresAt = qrExpiresAt;
        this.serviceChargeEnabled = serviceChargeEnabled;
        this.openedAt = openedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTableId() {
        return tableId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public int getBillNo() {
        return billNo;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public boolean isOccupyingTable() {
        return occupyingTableId != null;
    }

    public int getGuestCount() {
        return guestCount;
    }

    /** 顧客 token（機密）：只給 QR 圖片與 QR 列印單使用。 */
    String getToken() {
        return token;
    }

    public Instant getQrExpiresAt() {
        return qrExpiresAt;
    }

    public boolean isServiceChargeEnabled() {
        return serviceChargeEnabled;
    }

    public boolean isCheckoutInProgress() {
        return checkoutInProgress;
    }

    public boolean isPendingRecheckout() {
        return pendingRecheckout;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public boolean isOpen() {
        return status == SessionStatus.OPEN;
    }

    public void changeGuestCount(int count) {
        this.guestCount = count;
    }

    public void changeServiceCharge(boolean enabled) {
        this.serviceChargeEnabled = enabled;
    }

    public void setCheckoutInProgress(boolean flag) {
        this.checkoutInProgress = flag;
    }

    /** 結束：狀態已結束，釋放桌位。 */
    public void close(Instant now) {
        this.status = SessionStatus.CLOSED;
        this.occupyingTableId = null;
        this.checkoutInProgress = false;
        this.closedAt = now;
    }

    /** 取消：狀態已取消，釋放桌位；帳單號不回收。 */
    public void cancel(Instant now) {
        this.status = SessionStatus.CANCELLED;
        this.occupyingTableId = null;
        this.checkoutInProgress = false;
        this.pendingRecheckout = false;
        this.closedAt = now;
    }

    /** 占桌重開：原桌空著，回到使用中並重新占桌。 */
    public void reopenOccupying() {
        this.status = SessionStatus.OPEN;
        this.occupyingTableId = tableId;
        this.pendingRecheckout = false;
        this.checkoutInProgress = false;
        this.closedAt = null;
    }

    /** 不占桌重開：原桌已被使用或已刪除；標記待重新結帳。 */
    public void reopenWithoutTable() {
        this.status = SessionStatus.OPEN;
        this.occupyingTableId = null;
        this.pendingRecheckout = true;
        this.checkoutInProgress = false;
        this.closedAt = null;
    }

    @Override
    public String toString() {
        return "TableSession[id=" + id + ", status=" + status + "]";
    }
}
