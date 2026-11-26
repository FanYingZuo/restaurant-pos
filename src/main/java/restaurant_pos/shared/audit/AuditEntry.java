package restaurant_pos.shared.audit;

/**
 * 稽核輸入。前值、後值只放業務值（例如價格、開關），**不得放任何機密值**（PIN、憑證、配對碼、token）。
 */
public record AuditEntry(
        String action,
        String targetType,
        String targetId,
        String beforeValue,
        String afterValue,
        Long employeeId,
        Long deviceId,
        String reason) {

    public static AuditEntry of(String action, String targetType, Object targetId) {
        return new AuditEntry(action, targetType, targetId == null ? null : String.valueOf(targetId),
                null, null, null, null, null);
    }

    public AuditEntry withChange(Object before, Object after) {
        return new AuditEntry(action, targetType, targetId, before == null ? null : String.valueOf(before),
                after == null ? null : String.valueOf(after), employeeId, deviceId, reason);
    }

    public AuditEntry byEmployee(Long employeeId) {
        return new AuditEntry(action, targetType, targetId, beforeValue, afterValue, employeeId, deviceId, reason);
    }

    public AuditEntry byDevice(Long deviceId) {
        return new AuditEntry(action, targetType, targetId, beforeValue, afterValue, employeeId, deviceId, reason);
    }

    public AuditEntry withReason(String reason) {
        return new AuditEntry(action, targetType, targetId, beforeValue, afterValue, employeeId, deviceId, reason);
    }
}
