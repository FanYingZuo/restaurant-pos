package restaurant_pos.staff.shift;

/** 班次狀態（S2）：上班未打卡、下班未打卡（查詢時判斷）、上班中、完整班次。 */
public enum ShiftState {
    MISSING_CLOCK_IN,
    MISSING_CLOCK_OUT,
    ON_DUTY,
    COMPLETE
}
