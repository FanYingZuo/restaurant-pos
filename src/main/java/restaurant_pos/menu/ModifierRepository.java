package restaurant_pos.menu;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModifierRepository extends JpaRepository<Modifier, Long> {

    @Query("SELECT m FROM Modifier m WHERE m.modifierGroupId IN :groupIds AND m.deletedAt IS NULL "
            + "ORDER BY m.displayOrder, m.id")
    List<Modifier> findLiveByGroupIds(@Param("groupIds") Collection<Long> groupIds);

    @Query("SELECT m FROM Modifier m WHERE m.modifierGroupId = :groupId AND m.deletedAt IS NULL "
            + "ORDER BY m.displayOrder, m.id")
    List<Modifier> findLiveByGroup(@Param("groupId") Long groupId);

    @Query("SELECT m FROM Modifier m WHERE m.id = :id AND m.deletedAt IS NULL")
    Optional<Modifier> findLiveById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Modifier m WHERE m.id = :id AND m.deletedAt IS NULL")
    Optional<Modifier> lockLiveById(@Param("id") Long id);

    @Query("SELECT COALESCE(MAX(m.displayOrder), -1) FROM Modifier m WHERE m.modifierGroupId = :groupId AND m.deletedAt IS NULL")
    int maxOrder(@Param("groupId") Long groupId);
}
