package restaurant_pos.staff.shift;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.staff.shift.ShiftDtos.ClockHistory;
import restaurant_pos.staff.shift.ShiftDtos.ClockResult;

/** 打卡 API（合約 clockIn、clockOut、getClockStatus、getClockHistory）。 */
@RestController
@RequestMapping("/api/staff")
public class ClockController {

    public record PinBody(String pin) {
        @Override
        public String toString() {
            return "PinBody[***]";
        }
    }

    private final ClockService clock;
    private final UnlockGate unlockGate;

    public ClockController(ClockService clock, UnlockGate unlockGate) {
        this.clock = clock;
        this.unlockGate = unlockGate;
    }

    @PostMapping("/clock-in")
    public ClockResult clockIn(@RequestBody PinBody body) {
        return clock.clockIn(body.pin());
    }

    @PostMapping("/clock-out")
    public ClockResult clockOut(@RequestBody PinBody body) {
        return clock.clockOut(body.pin());
    }

    @PostMapping("/clock-status")
    public ClockResult status(@RequestBody PinBody body) {
        return clock.status(body.pin());
    }

    /** 需管理者解鎖（EMPLOYEE_MANAGE，S1 決定 #10）。 */
    @GetMapping("/clock-history")
    public ClockHistory history(@RequestHeader(value = "X-Unlock-Token", required = false) String unlockToken,
            @RequestParam(required = false) Long employeeId, @RequestParam(required = false) Integer days) {
        unlockGate.require(unlockToken, UnlockFunction.EMPLOYEE_MANAGE);
        return clock.history(employeeId, days);
    }
}
