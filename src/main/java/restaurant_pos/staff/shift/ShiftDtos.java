package restaurant_pos.staff.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 班次相關的回應物件（欄位與 docs/contract/openapi.yaml 一致）。 */
public final class ShiftDtos {

    private ShiftDtos() {
    }

    public record ShiftBrief(long shiftId, LocalDate businessDate, Instant clockInAt, Instant clockOutAt,
            Integer minutes, ShiftState state, boolean corrected) {

        public static ShiftBrief of(WorkShift s, LocalDate today) {
            return new ShiftBrief(s.getId(), s.getBusinessDate(), s.getClockInAt(), s.getClockOutAt(), s.minutes(),
                    s.stateOn(today), s.isCorrected());
        }
    }

    public record ClockResult(long employeeId, String employeeName, ShiftState state, ClockAction nextAction,
            List<ClockWarning> warnings, List<ShiftBrief> todayShifts, int todayTotalMinutes) {
    }

    public record ShiftRecord(long shiftId, LocalDate businessDate, Instant clockInAt, Instant clockOutAt,
            Integer minutes, ShiftState state, boolean corrected, long employeeId, String employeeName) {

        public static ShiftRecord of(WorkShift s, LocalDate today, String employeeName) {
            return new ShiftRecord(s.getId(), s.getBusinessDate(), s.getClockInAt(), s.getClockOutAt(), s.minutes(),
                    s.stateOn(today), s.isCorrected(), s.getEmployeeId(), employeeName);
        }
    }

    public record ClockHistory(int days, LocalDate fromBusinessDate, LocalDate toBusinessDate,
            List<ShiftRecord> shifts) {
    }
}
