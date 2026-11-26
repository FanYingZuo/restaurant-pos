package restaurant_pos.table;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {

    @Query("SELECT t FROM DiningTable t WHERE t.deletedAt IS NULL")
    List<DiningTable> findAllLive();

    @Query("SELECT t FROM DiningTable t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<DiningTable> findLiveById(@Param("id") Long id);

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DiningTable t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<DiningTable> lockLiveById(@Param("id") Long id);
}
