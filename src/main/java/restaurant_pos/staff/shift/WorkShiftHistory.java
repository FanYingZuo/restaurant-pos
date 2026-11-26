package restaurant_pos.staff.shift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 出勤更正歷史：只新增，沒有任何修改方法。 */
@Entity
@Table(name = "work_shift_history")
public class WorkShiftHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "actor_employee_id", nullable = false)
    private Long actorEmployeeId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "before_clock_in")
    private Instant beforeClockIn;

    @Column(name = "before_clock_out")
    private Instant beforeClockOut;

    @Column(name = "after_clock_in")
    private Instant afterClockIn;

    @Column(name = "after_clock_out")
    private Instant afterClockOut;

    protected WorkShiftHistory() {
    }

    public WorkShiftHistory(Long shiftId, Long actorEmployeeId, Instant changedAt, String reason,
            Instant beforeIn, Instant beforeOut, Instant afterIn, Instant afterOut) {
        this.shiftId = shiftId;
        this.actorEmployeeId = actorEmployeeId;
        this.changedAt = changedAt;
        this.reason = reason;
        this.beforeClockIn = beforeIn;
        this.beforeClockOut = beforeOut;
        this.afterClockIn = afterIn;
        this.afterClockOut = afterOut;
    }

    public Long getId() {
        return id;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public Long getActorEmployeeId() {
        return actorEmployeeId;
    }

    public String getReason() {
        return reason;
    }
}
