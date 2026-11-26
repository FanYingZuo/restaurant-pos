package restaurant_pos.staff.employee;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.staff.employee.EmployeeService.EmployeeDto;
import restaurant_pos.staff.shift.ClockController.PinBody;

/** 員工管理 API（合約 listEmployees、createEmployee、updateEmployee、changeEmployeePin、deactivateEmployee）。 */
@RestController
@RequestMapping("/api/staff/employees")
public class EmployeeController {

    public record CreateRequest(String name, EmployeeRole role, String pin) {
        @Override
        public String toString() {
            return "CreateRequest[name=" + name + ", role=" + role + "]";
        }
    }

    public record UpdateRequest(String name, EmployeeRole role) {
    }

    private final EmployeeService service;
    private final UnlockGate unlockGate;

    public EmployeeController(EmployeeService service, UnlockGate unlockGate) {
        this.service = service;
        this.unlockGate = unlockGate;
    }

    private void unlocked(String token) {
        unlockGate.require(token, UnlockFunction.EMPLOYEE_MANAGE);
    }

    @GetMapping
    public List<EmployeeDto> list(@RequestHeader(value = "X-Unlock-Token", required = false) String token) {
        unlocked(token);
        return service.list();
    }

    @PostMapping
    public ResponseEntity<EmployeeDto> create(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @RequestBody CreateRequest request) {
        unlocked(token);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.name(), request.role(), request.pin()));
    }

    @PatchMapping("/{employeeId}")
    public EmployeeDto update(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @PathVariable long employeeId, @RequestBody UpdateRequest request) {
        unlocked(token);
        return service.update(employeeId, request.name(), request.role());
    }

    @PutMapping("/{employeeId}/pin")
    public ResponseEntity<Void> changePin(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @PathVariable long employeeId, @RequestBody PinBody body) {
        unlocked(token);
        service.changePin(employeeId, body.pin());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{employeeId}/deactivate")
    public EmployeeDto deactivate(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @PathVariable long employeeId) {
        unlocked(token);
        return service.deactivate(employeeId);
    }
}
