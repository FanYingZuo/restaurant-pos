package restaurant_pos.staff.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.audit.AuditLog;
import restaurant_pos.shared.audit.AuditLogRepository;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;

/**
 * 稽核查詢 API（合約 listAuditLogs，EMPLOYEE_MANAGE）：依營業日與動作篩選，預設依 ID 遞增（§4.4 分頁規則）。
 */
@RestController
@RequestMapping("/api/staff/audit-logs")
public class AuditQueryController {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 100;

    public record AuditLogEntry(long id, Instant occurredAt, LocalDate businessDate, String action, String targetType,
            String targetId, String beforeValue, String afterValue, Long employeeId, Long deviceId, String reason) {
        static AuditLogEntry of(AuditLog a) {
            return new AuditLogEntry(a.getId(), a.getOccurredAt(), a.getBusinessDate(), a.getAction(),
                    a.getTargetType(), a.getTargetId(), a.getBeforeValue(), a.getAfterValue(),
                    a.getActorEmployeeId(), a.getActorDeviceId(), a.getReason());
        }
    }

    public record AuditLogPage(int page, int size, long totalElements, int totalPages, List<AuditLogEntry> content) {
    }

    private final AuditLogRepository repository;
    private final UnlockGate unlockGate;

    public AuditQueryController(AuditLogRepository repository, UnlockGate unlockGate) {
        this.repository = repository;
        this.unlockGate = unlockGate;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public AuditLogPage list(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        unlockGate.require(token, UnlockFunction.EMPLOYEE_MANAGE);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(ErrorCode.INVALID_DATE_RANGE);
        }
        String actionFilter = action == null || action.isBlank() ? null : action.trim();
        if (actionFilter != null && actionFilter.length() > 64) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Page<AuditLog> result = repository.search(from, to, actionFilter,
                PageRequest.of(pageNo, pageSize, Sort.by("id").ascending()));
        return new AuditLogPage(pageNo, pageSize, result.getTotalElements(), result.getTotalPages(),
                result.getContent().stream().map(AuditLogEntry::of).toList());
    }
}
