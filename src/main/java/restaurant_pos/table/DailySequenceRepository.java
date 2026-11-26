package restaurant_pos.table;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailySequenceRepository extends JpaRepository<DailySequence, LocalDate> {

    /** 依營業日（主鍵）查詢並鎖定。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DailySequence s WHERE s.businessDate = :date")
    Optional<DailySequence> lockByDate(@Param("date") LocalDate date);
}
