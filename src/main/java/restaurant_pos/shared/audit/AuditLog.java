package restaurant_pos.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** 稽核紀錄（audit_log）：只新增，不提供任何修改方法（§4.2）。 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "actor_employee_id")
    private Long actorEmployeeId;

    @Column(name = "actor_device_id")
    private Long actorDeviceId;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "before_value", length = 1000)
    private String beforeValue;

    @Column(name = "after_value", length = 1000)
    private String afterValue;

    @Column(length = 255)
    private String reason;

    protected AuditLog() {
    }

    AuditLog(Instant occurredAt, LocalDate businessDate, AuditEntry entry, Long deviceId) {
        this.occurredAt = occurredAt;
        this.businessDate = businessDate;
        this.action = entry.action();
        this.actorEmployeeId = entry.employeeId();
        this.actorDeviceId = deviceId;
        this.targetType = entry.targetType();
        this.targetId = entry.targetId();
        this.beforeValue = entry.beforeValue();
        this.afterValue = entry.afterValue();
        this.reason = entry.reason();
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public String getAction() {
        return action;
    }

    public Long getActorEmployeeId() {
        return actorEmployeeId;
    }

    public Long getActorDeviceId() {
        return actorDeviceId;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public String getReason() {
        return reason;
    }
}
