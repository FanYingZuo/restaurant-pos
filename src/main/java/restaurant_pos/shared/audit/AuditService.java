package restaurant_pos.shared.audit;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.shared.security.DeviceContext;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 稽核元件（§4.5「稽核紀錄」）：與業務**同一交易**寫入（業務回滾時一起回滾），只新增。
 * 裝置編號未指定時取目前請求的裝置。
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final BusinessDayService businessDay;

    public AuditService(AuditLogRepository repository, BusinessDayService businessDay) {
        this.repository = repository;
        this.businessDay = businessDay;
    }

    /** 必須在既有交易中呼叫（MANDATORY），確保稽核與業務同一交易。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        save(entry);
    }

    /**
     * 只給「業務本身失敗也必須留下紀錄」的事件使用（例如 PIN 鎖定）：另開交易，不受呼叫者回滾影響。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(AuditEntry entry) {
        save(entry);
    }

    private void save(AuditEntry entry) {
        Instant now = businessDay.now();
        Long deviceId = entry.deviceId() != null ? entry.deviceId()
                : DeviceContext.current().map(d -> d.deviceId()).orElse(null);
        repository.save(new AuditLog(now, businessDay.businessDateOf(now), entry, deviceId));
    }
}
