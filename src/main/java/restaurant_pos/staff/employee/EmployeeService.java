package restaurant_pos.staff.employee;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.HmacHasher;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 員工管理（EMPLOYEE_MANAGE，S2）：列表（不回 PIN）、新增、改名與角色、換 PIN、停用。
 * 至少保留一位在職管理者；停用清空 PIN 雜湊；角色只有員工與管理者。
 *
 * <p>併發：「至少一位管理者」靠鎖定管理者列維持。先（不鎖）讀出管理者編號，再依編號遞增鎖定
 * （命中主鍵），之後用「鎖到的最新資料」判斷，避免 REPEATABLE READ 快照過期（§4.6）。
 */
@Service
public class EmployeeService {

    public record EmployeeDto(long id, String name, EmployeeRole role, boolean active, Instant createdAt) {
        static EmployeeDto of(Employee e) {
            return new EmployeeDto(e.getId(), e.getName(), e.getRole(), e.isActive(), e.getCreatedAt());
        }
    }

    private final EmployeeRepository employees;
    private final HmacHasher hasher;
    private final BusinessDayService time;
    private final TransactionTemplate tx;

    public EmployeeService(EmployeeRepository employees, HmacHasher hasher, BusinessDayService time,
            TransactionTemplate tx) {
        this.employees = employees;
        this.hasher = hasher;
        this.time = time;
        this.tx = tx;
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> list() {
        return employees.findAllByOrderByIdAsc().stream().map(EmployeeDto::of).toList();
    }

    public EmployeeDto create(String name, EmployeeRole role, String pin) {
        String cleanName = cleanName(name);
        if (role == null || !PinService.isValidFormat(pin)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String hash = hasher.pin(pin);
        return tx.execute(status -> {
            if (employees.findActiveByPinHash(hash).isPresent()) {
                throw new ApiException(ErrorCode.PIN_ALREADY_USED);
            }
            try {
                return EmployeeDto.of(employees.saveAndFlush(new Employee(cleanName, role, hash, time.now())));
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(ErrorCode.PIN_ALREADY_USED);
            }
        });
    }

    public EmployeeDto update(long id, String name, EmployeeRole role) {
        if (name == null && role == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String cleanName = name == null ? null : cleanName(name);
        return tx.execute(status -> {
            List<Employee> locked = lockWithManagers(id);
            Employee target = find(locked, id);
            if (role == EmployeeRole.STAFF && target.isActive() && target.isManager() && activeManagers(locked) <= 1) {
                throw new ApiException(ErrorCode.LAST_MANAGER);
            }
            if (cleanName != null) {
                target.rename(cleanName);
            }
            if (role != null) {
                target.changeRole(role);
            }
            return EmployeeDto.of(employees.saveAndFlush(target));
        });
    }

    public void changePin(long id, String pin) {
        if (!PinService.isValidFormat(pin)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String hash = hasher.pin(pin);
        tx.executeWithoutResult(status -> {
            Employee target = employees.lockById(id).orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND));
            if (!target.isActive()) {
                throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND);
            }
            employees.findActiveByPinHash(hash).ifPresent(owner -> {
                if (!owner.getId().equals(id)) {
                    throw new ApiException(ErrorCode.PIN_ALREADY_USED);
                }
            });
            target.changePin(hash);
            try {
                employees.saveAndFlush(target);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(ErrorCode.PIN_ALREADY_USED);
            }
        });
    }

    /** 停用（軟刪除、清空 PIN 雜湊）。已停用則原樣回傳（冪等）；最後一位在職管理者不可停用。 */
    public EmployeeDto deactivate(long id) {
        return tx.execute(status -> {
            List<Employee> locked = lockWithManagers(id);
            Employee target = find(locked, id);
            if (!target.isActive()) {
                return EmployeeDto.of(target);
            }
            if (target.isManager() && activeManagers(locked) <= 1) {
                throw new ApiException(ErrorCode.LAST_MANAGER);
            }
            target.deactivate(time.now());
            return EmployeeDto.of(employees.saveAndFlush(target));
        });
    }

    // ---- 內部 ----

    /** 依編號遞增鎖定「目標員工＋目前所有在職管理者」，回傳鎖到的最新資料。 */
    private List<Employee> lockWithManagers(long targetId) {
        Set<Long> ids = new TreeSet<>(employees.findActiveManagerIds());
        ids.add(targetId);
        return employees.lockAllByIds(ids);
    }

    private static Employee find(List<Employee> locked, long id) {
        return locked.stream().filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    private static long activeManagers(List<Employee> locked) {
        return locked.stream().filter(e -> e.isActive() && e.isManager()).count();
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 50) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }
}
