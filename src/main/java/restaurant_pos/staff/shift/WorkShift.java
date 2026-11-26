package restaurant_pos.staff.shift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 班次。上下班至少一個有值；營業日為上班營業日（只有下班時為下班的營業日）。
 * 分鐘數無條件捨去；只有完整班次才計工時。
 */
@Entity
@Table(name = "work_shift")
public class WorkShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "clock_in_at")
    private Instant clockInAt;

    @Column(name = "clock_out_at")
    private Instant clockOutAt;

    @Column(nullable = false)
    private boolean corrected;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkShift() {
    }

    private WorkShift(Long employeeId, LocalDate businessDate, Instant clockInAt, Instant clockOutAt, Instant createdAt) {
        this.employeeId = employeeId;
        this.businessDate = businessDate;
        this.clockInAt = clockInAt;
        this.clockOutAt = clockOutAt;
        this.createdAt = createdAt;
    }

    /** 上班打卡產生的班次。 */
    public static WorkShift clockedIn(Long employeeId, LocalDate businessDate, Instant now) {
        return new WorkShift(employeeId, businessDate, now, null, now);
    }

    /** 沒上班就下班：另記「上班未打卡」班次（不計工時）。 */
    public static WorkShift clockedOutWithoutIn(Long employeeId, LocalDate businessDate, Instant now) {
        return new WorkShift(employeeId, businessDate, null, now, now);
    }

    /** 出勤更正新增的完整班次。 */
    public static WorkShift corrected(Long employeeId, LocalDate businessDate, Instant in, Instant out, Instant now) {
        WorkShift shift = new WorkShift(employeeId, businessDate, in, out, now);
        shift.corrected = true;
        return shift;
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Instant getClockInAt() {
        return clockInAt;
    }

    public Instant getClockOutAt() {
        return clockOutAt;
    }

    public boolean isCorrected() {
        return corrected;
    }

    public boolean isOpen() {
        return clockInAt != null && clockOutAt == null;
    }

    public void clockOut(Instant now) {
        this.clockOutAt = now;
    }

    /** 完整班次的分鐘數（無條件捨去）；非完整班次為 null。 */
    public Integer minutes() {
        if (clockInAt == null || clockOutAt == null) {
            return null;
        }
        return (int) (Duration.between(clockInAt, clockOutAt).getSeconds() / 60);
    }

    /** 查詢時判斷狀態：開著的班次若屬於更早的營業日，就是「下班未打卡」。 */
    public ShiftState stateOn(LocalDate today) {
        if (clockInAt != null && clockOutAt != null) {
            return ShiftState.COMPLETE;
        }
        if (clockInAt == null) {
            return ShiftState.MISSING_CLOCK_IN;
        }
        return businessDate.isBefore(today) ? ShiftState.MISSING_CLOCK_OUT : ShiftState.ON_DUTY;
    }

    /** 出勤更正：套用新的上下班時間並標記已更正。 */
    public void applyCorrection(Instant newIn, Instant newOut, LocalDate newBusinessDate) {
        this.clockInAt = newIn;
        this.clockOutAt = newOut;
        this.businessDate = newBusinessDate;
        this.corrected = true;
    }
}
