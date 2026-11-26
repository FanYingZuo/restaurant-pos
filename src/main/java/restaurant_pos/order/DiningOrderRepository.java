package restaurant_pos.order;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiningOrderRepository extends JpaRepository<DiningOrder, Long> {

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM DiningOrder o WHERE o.id = :id")
    Optional<DiningOrder> lockById(@Param("id") Long id);

    /** 該用餐紀錄的草稿並鎖定（命中唯一索引 draft_session_id）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM DiningOrder o WHERE o.draftSessionId = :sessionId")
    Optional<DiningOrder> lockDraft(@Param("sessionId") Long sessionId);

    @Query("SELECT o FROM DiningOrder o WHERE o.draftSessionId = :sessionId")
    Optional<DiningOrder> findDraft(@Param("sessionId") Long sessionId);

    /** 冪等：請求編號在用餐紀錄內唯一（命中唯一索引）。 */
    @Query("SELECT o FROM DiningOrder o WHERE o.sessionId = :sessionId AND o.requestId = :requestId")
    Optional<DiningOrder> findByRequest(@Param("sessionId") Long sessionId, @Param("requestId") String requestId);

    @Query("SELECT COALESCE(MAX(o.batchNo), 0) FROM DiningOrder o WHERE o.sessionId = :sessionId")
    int maxBatchNo(@Param("sessionId") Long sessionId);

    /** 正式單（含已取消），依批次號。 */
    @Query("SELECT o FROM DiningOrder o WHERE o.sessionId = :sessionId AND o.batchNo IS NOT NULL ORDER BY o.batchNo")
    List<DiningOrder> findFormal(@Param("sessionId") Long sessionId);

    @Query("SELECT COUNT(o) FROM DiningOrder o WHERE o.sessionId = :sessionId AND o.batchNo IS NOT NULL")
    long countFormal(@Param("sessionId") Long sessionId);
}
