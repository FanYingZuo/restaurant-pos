package restaurant_pos.staff.employee;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    /** 只比對在職員工；停用員工的 PIN 雜湊已清空，同樣查不到。 */
    @Query("SELECT e FROM Employee e WHERE e.pinHash = :hash AND e.deactivatedAt IS NULL")
    Optional<Employee> findActiveByPinHash(@Param("hash") String hash);

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id = :id")
    Optional<Employee> lockById(@Param("id") Long id);

    /** 依編號遞增鎖定一批員工（命中主鍵，固定順序避免死結）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id IN :ids ORDER BY e.id")
    List<Employee> lockAllByIds(@Param("ids") Collection<Long> ids);

    @Query("SELECT e.id FROM Employee e WHERE e.role = restaurant_pos.staff.employee.EmployeeRole.MANAGER "
            + "AND e.deactivatedAt IS NULL ORDER BY e.id")
    List<Long> findActiveManagerIds();

    @Query("SELECT COUNT(e) FROM Employee e WHERE e.role = restaurant_pos.staff.employee.EmployeeRole.MANAGER "
            + "AND e.deactivatedAt IS NULL")
    long countActiveManagers();

    List<Employee> findByIdIn(Collection<Long> ids);

    List<Employee> findAllByOrderByIdAsc();
}
