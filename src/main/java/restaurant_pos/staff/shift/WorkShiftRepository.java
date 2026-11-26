package restaurant_pos.staff.shift;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkShiftRepository extends JpaRepository<WorkShift, Long> {

    List<WorkShift> findByEmployeeIdAndBusinessDateOrderByIdAsc(Long employeeId, LocalDate businessDate);

    /** 該員工所有「已上班、未下班」的班次，新的在前。 */
    @Query("SELECT s FROM WorkShift s WHERE s.employeeId = :employeeId AND s.clockInAt IS NOT NULL "
            + "AND s.clockOutAt IS NULL ORDER BY s.id DESC")
    List<WorkShift> findOpenShifts(@Param("employeeId") Long employeeId);

    @Query("SELECT s FROM WorkShift s WHERE s.employeeId = :employeeId ORDER BY s.id DESC")
    List<WorkShift> findLatest(@Param("employeeId") Long employeeId, Pageable pageable);

    @Query("SELECT MAX(s.clockInAt) FROM WorkShift s WHERE s.employeeId = :employeeId")
    Optional<Instant> maxClockIn(@Param("employeeId") Long employeeId);

    @Query("SELECT MAX(s.clockOutAt) FROM WorkShift s WHERE s.employeeId = :employeeId")
    Optional<Instant> maxClockOut(@Param("employeeId") Long employeeId);

    /** 打卡歷史：營業日區間，新的在前；employeeId 為 null 表示全部員工。 */
    @Query("SELECT s FROM WorkShift s WHERE s.businessDate BETWEEN :from AND :to "
            + "AND (:employeeId IS NULL OR s.employeeId = :employeeId) ORDER BY s.businessDate DESC, s.id DESC")
    List<WorkShift> findHistory(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("employeeId") Long employeeId);

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM WorkShift s WHERE s.id = :id")
    Optional<WorkShift> lockById(@Param("id") Long id);
}
