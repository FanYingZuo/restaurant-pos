package restaurant_pos.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.event.DomainEvent;

/** 測試專用：模擬「業務交易內寫稽核並發佈事件」。只存在於 src/test。 */
@Service
public class AuditProbe {

    public record ProbeEvent(String name) implements DomainEvent {
    }

    @Component
    public static class ProbeListener {

        public final List<String> afterCommit = new CopyOnWriteArrayList<>();
        public final List<String> beforeCommit = new CopyOnWriteArrayList<>();
        public volatile boolean failBeforeCommit;

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void afterCommit(ProbeEvent event) {
            afterCommit.add(event.name());
        }

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        public void beforeCommit(ProbeEvent event) {
            beforeCommit.add(event.name());
            if (failBeforeCommit) {
                throw new IllegalStateException("列印監聽器失敗");
            }
        }

        public void reset() {
            afterCommit.clear();
            beforeCommit.clear();
            failBeforeCommit = false;
        }
    }

    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public AuditProbe(AuditService audit, ApplicationEventPublisher events) {
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public void commitWithAudit(String name) {
        audit.record(AuditEntry.of(name, "probe", 42).withChange("舊值", "新值").withReason("測試理由"));
        events.publishEvent(new ProbeEvent(name));
    }

    @Transactional
    public void rollbackWithAudit(String name) {
        audit.record(AuditEntry.of(name, "probe", 42));
        events.publishEvent(new ProbeEvent(name));
        throw new IllegalStateException("業務失敗");
    }

    @Transactional
    public void rollbackWithIndependentAudit(String name) {
        audit.recordInNewTransaction(AuditEntry.of(name, "probe", 42));
        throw new IllegalStateException("業務失敗");
    }

    @Transactional
    public void publishOnly(String name) {
        events.publishEvent(new ProbeEvent(name));
    }
}
