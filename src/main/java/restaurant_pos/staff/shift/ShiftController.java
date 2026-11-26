package restaurant_pos.staff.shift;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.staff.shift.ShiftDtos.ShiftRecord;

/** 出勤更正 API（合約 createShift、correctShift）。班次不提供刪除。 */
@RestController
@RequestMapping("/api/staff/shifts")
public class ShiftController {

    public record CreateRequest(Long employeeId, Instant clockInAt, Instant clockOutAt, String reason) {
    }

    public record UpdateRequest(Instant clockInAt, Instant clockOutAt, String reason) {
    }

    private final ShiftCorrectionService service;
    private final UnlockGate unlockGate;

    public ShiftController(ShiftCorrectionService service, UnlockGate unlockGate) {
        this.service = service;
        this.unlockGate = unlockGate;
    }

    private long holder(String token) {
        return unlockGate.require(token, UnlockFunction.EMPLOYEE_MANAGE).employeeId();
    }

    @PostMapping
    public ResponseEntity<ShiftRecord> create(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @RequestBody CreateRequest request) {
        long holder = holder(token);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(holder, request.employeeId(),
                request.clockInAt(), request.clockOutAt(), request.reason()));
    }

    @PatchMapping("/{shiftId}")
    public ShiftRecord correct(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @PathVariable long shiftId, @RequestBody UpdateRequest request) {
        long holder = holder(token);
        return service.correct(holder, shiftId, request.clockInAt(), request.clockOutAt(), request.reason());
    }
}
