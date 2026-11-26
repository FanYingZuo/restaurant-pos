package restaurant_pos.staff.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.setting.Setting;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.staff.employee.Employee;
import restaurant_pos.staff.employee.EmployeeNames;
import restaurant_pos.staff.employee.EmployeeRepository;
import restaurant_pos.staff.shift.ShiftDtos.ShiftRecord;

/**
 * 出勤更正（EMPLOYEE_MANAGE，S2）：補上班或下班時間、修改既有班次、新增完整班次。
 * 規則：理由必填；下班晚於上班且不晚於現在；限 31 天內（設定 shift.correction.max.days）；
 * 不能更正自己的班次（唯一在職管理者例外）；寫更正歷史與稽核；班次標記「已更正」；班次不能刪除。
 *
 * <p>鎖順序：先鎖員工、再鎖班次（與打卡一致，避免死結）；交易內第一個動作就是鎖（REPEATABLE READ，§4.6）。
 * 檢查順序：格式（400 VALIDATION_ERROR）→ 存在（404）→ 自己（403）→ 時間不合法（400）→ 太久以前（400）。
 */
@Service
public class ShiftCorrectionService {

    private final EmployeeRepository employees;
    private final WorkShiftRepository shifts;
    private final WorkShiftHistoryRepository histories;
    private final BusinessDayService time;
    private final AppSettingService settings;
    private final AuditService audit;
    private final EmployeeNames names;
    private final TransactionTemplate tx;

    public ShiftCorrectionService(EmployeeRepository employees, WorkShiftRepository shifts,
            WorkShiftHistoryRepository histories, BusinessDayService time, AppSettingService settings,
            AuditService audit, EmployeeNames names, TransactionTemplate tx) {
        this.employees = employees;
        this.shifts = shifts;
        this.histories = histories;
        this.time = time;
        this.settings = settings;
        this.audit = audit;
        this.names = names;
        this.tx = tx;
    }

    /** 新增完整班次。 */
    public ShiftRecord create(long holderId, Long employeeId, Instant clockIn, Instant clockOut, String reason) {
        String cleanReason = cleanReason(reason);
        if (employeeId == null || clockIn == null || clockOut == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return tx.execute(status -> {
            Employee target = employees.lockById(employeeId)
                    .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND));
            checkNotSelf(holderId, target.getId());
            Instant now = time.now();
            validateTimes(clockIn, clockOut, now);
            LocalDate date = time.businessDateOf(clockIn);
            checkAge(date, time.businessDateOf(now));
            WorkShift shift = shifts.saveAndFlush(WorkShift.corrected(target.getId(), date, clockIn, clockOut, now));
            record(holderId, shift, now, cleanReason, null, null);
            return ShiftRecord.of(shift, time.businessDateOf(now),
                    names.namesByIds(List.of(target.getId())).getOrDefault(target.getId(), ""));
        });
    }

    /** 補上班或下班時間、修改既有班次（只能設定時間，不能清空）。 */
    public ShiftRecord correct(long holderId, long shiftId, Instant clockIn, Instant clockOut, String reason) {
        String cleanReason = cleanReason(reason);
        if (clockIn == null && clockOut == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        // 先（不鎖）讀出班次的員工，才能依「員工 → 班次」的順序鎖定
        WorkShift peek = shifts.findById(shiftId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return tx.execute(status -> {
            Employee owner = employees.lockById(peek.getEmployeeId())
                    .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND));
            WorkShift shift = shifts.lockById(shiftId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            checkNotSelf(holderId, owner.getId());
            Instant now = time.now();
            Instant newIn = clockIn != null ? clockIn : shift.getClockInAt();
            Instant newOut = clockOut != null ? clockOut : shift.getClockOutAt();
            validateProvided(clockIn, clockOut, newIn, newOut, now);
            LocalDate today = time.businessDateOf(now);
            LocalDate newDate = newIn != null ? time.businessDateOf(newIn) : time.businessDateOf(newOut);
            checkAge(shift.getBusinessDate(), today);
            checkAge(newDate, today);

            Instant beforeIn = shift.getClockInAt();
            Instant beforeOut = shift.getClockOutAt();
            shift.applyCorrection(newIn, newOut, newDate);
            shifts.saveAndFlush(shift);
            record(holderId, shift, now, cleanReason, beforeIn, beforeOut);
            return ShiftRecord.of(shift, today,
                    names.namesByIds(List.of(owner.getId())).getOrDefault(owner.getId(), ""));
        });
    }

    // ---- 規則 ----

    /** 不能更正自己的班次；唯一在職管理者例外。 */
    private void checkNotSelf(long holderId, long targetEmployeeId) {
        if (holderId == targetEmployeeId && employees.countActiveManagers() != 1) {
            throw new ApiException(ErrorCode.SHIFT_CORRECTION_SELF);
        }
    }

    /** 新增完整班次：下班晚於上班且不晚於現在。 */
    private static void validateTimes(Instant in, Instant out, Instant now) {
        if (!out.isAfter(in) || out.isAfter(now)) {
            throw new ApiException(ErrorCode.SHIFT_CORRECTION_INVALID);
        }
    }

    /** 修改：有給的時間不能晚於現在；兩者皆有時下班必須晚於上班。 */
    private static void validateProvided(Instant providedIn, Instant providedOut, Instant newIn, Instant newOut,
            Instant now) {
        if ((providedIn != null && providedIn.isAfter(now)) || (providedOut != null && providedOut.isAfter(now))) {
            throw new ApiException(ErrorCode.SHIFT_CORRECTION_INVALID);
        }
        if (newIn != null && newOut != null && !newOut.isAfter(newIn)) {
            throw new ApiException(ErrorCode.SHIFT_CORRECTION_INVALID);
        }
    }

    /** 限 31 天內：班次營業日不得早於「今天營業日 − 31 天」。 */
    private void checkAge(LocalDate shiftDate, LocalDate today) {
        int maxDays = settings.getPositiveInt(Setting.SHIFT_CORRECTION_MAX_DAYS);
        if (shiftDate.isBefore(today.minusDays(maxDays))) {
            throw new ApiException(ErrorCode.SHIFT_CORRECTION_TOO_OLD);
        }
    }

    private static String cleanReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    private void record(long holderId, WorkShift shift, Instant now, String reason, Instant beforeIn, Instant beforeOut) {
        histories.save(new WorkShiftHistory(shift.getId(), holderId, now, reason, beforeIn, beforeOut,
                shift.getClockInAt(), shift.getClockOutAt()));
        audit.record(AuditEntry.of("SHIFT_CORRECTED", "work_shift", shift.getId())
                .withChange(describe(beforeIn, beforeOut), describe(shift.getClockInAt(), shift.getClockOutAt()))
                .byEmployee(holderId).withReason(reason));
    }

    private static String describe(Instant in, Instant out) {
        return "in=" + (in == null ? "無" : in) + ",out=" + (out == null ? "無" : out);
    }
}
