package restaurant_pos.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import restaurant_pos.shared.model.Station;

/**
 * 訂單品項：建立時存名稱、價格、站別快照；版本號在狀態、數量、選項、備註變更時加 1。
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "station_code", nullable = false, length = 8)
    private Station station;

    @Column(nullable = false, length = 255)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ItemStatus status;

    @Column(nullable = false)
    private int version;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_employee")
    private Long cancelledByEmployee;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderItem() {
    }

    public OrderItem(Long orderId, Long sessionId, Long menuItemId, String name, BigDecimal basePrice,
            BigDecimal unitPrice, int quantity, Station station, String note, Instant createdAt) {
        this.orderId = orderId;
        this.sessionId = sessionId;
        this.menuItemId = menuItemId;
        this.name = name;
        this.basePrice = basePrice;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.station = station;
        this.note = note;
        this.status = ItemStatus.PENDING;
        this.version = 0;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public Station getStation() {
        return station;
    }

    public String getNote() {
        return note;
    }

    public ItemStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public BigDecimal lineTotal() {
        return OrderRules.lineTotal(unitPrice, quantity);
    }

    /** 以新的快照整筆取代內容（草稿修改或修改已送出品項）。狀態不變；已送出的品項版本加 1。 */
    public void replaceContent(Long menuItemId, String name, BigDecimal basePrice, BigDecimal unitPrice, int quantity,
            Station station, String note, boolean bumpVersion) {
        this.menuItemId = menuItemId;
        this.name = name;
        this.basePrice = basePrice;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.station = station;
        this.note = note;
        if (bumpVersion) {
            this.version++;
        }
    }

    /** 狀態轉換（開始製作、完成）：版本加 1。呼叫者須先用 {@link OrderRules#isLegalTransition} 檢查。 */
    public void moveTo(ItemStatus target) {
        this.status = target;
        this.version++;
    }

    public void cancel(String reason, Long employeeId, Instant now) {
        this.status = ItemStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledByEmployee = employeeId;
        this.cancelledAt = now;
        this.version++;
    }
}
