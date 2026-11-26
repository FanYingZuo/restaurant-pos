package restaurant_pos.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 訂單歷史：只新增，沒有任何修改方法。 */
@Entity
@Table(name = "order_history")
public class OrderHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "before_summary", length = 1000)
    private String beforeSummary;

    @Column(name = "after_summary", length = 1000)
    private String afterSummary;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderHistory() {
    }

    public OrderHistory(Long sessionId, Long orderId, Long orderItemId, String action, String beforeSummary,
            String afterSummary, Long employeeId, String reason, Instant createdAt) {
        this.sessionId = sessionId;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.action = action;
        this.beforeSummary = abbreviate(beforeSummary);
        this.afterSummary = abbreviate(afterSummary);
        this.employeeId = employeeId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }
}
