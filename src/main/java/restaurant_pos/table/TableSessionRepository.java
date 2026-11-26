package restaurant_pos.table;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TableSessionRepository extends JpaRepository<TableSession, Long> {

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TableSession s WHERE s.id = :id")
    Optional<TableSession> lockById(@Param("id") Long id);

    /** 目前占用該桌位的使用中紀錄（占桌欄位有唯一索引）。 */
    @Query("SELECT s FROM TableSession s WHERE s.occupyingTableId = :tableId")
    Optional<TableSession> findOccupying(@Param("tableId") Long tableId);

    @Query("SELECT s FROM TableSession s WHERE s.occupyingTableId IN :tableIds")
    List<TableSession> findOccupyingIn(@Param("tableIds") Collection<Long> tableIds);

    /** 待重新結帳的用餐紀錄：使用中且標記待重新結帳。 */
    @Query("SELECT s FROM TableSession s WHERE s.pendingRecheckout = true AND s.status = "
            + "restaurant_pos.table.SessionStatus.OPEN ORDER BY s.id")
    List<TableSession> findPendingRecheckout();
}
