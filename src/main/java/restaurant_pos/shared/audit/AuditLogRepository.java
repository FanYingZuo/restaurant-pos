package restaurant_pos.shared.audit;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a WHERE (:from IS NULL OR a.businessDate >= :from) "
            + "AND (:to IS NULL OR a.businessDate <= :to) AND (:action IS NULL OR a.action = :action)")
    Page<AuditLog> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("action") String action, Pageable pageable);
}
