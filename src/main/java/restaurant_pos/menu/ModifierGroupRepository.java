package restaurant_pos.menu;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, Long> {

    @Query("SELECT g FROM ModifierGroup g WHERE g.menuItemId IN :itemIds AND g.deletedAt IS NULL "
            + "ORDER BY g.displayOrder, g.id")
    List<ModifierGroup> findLiveByItemIds(@Param("itemIds") Collection<Long> itemIds);

    @Query("SELECT g FROM ModifierGroup g WHERE g.menuItemId = :itemId AND g.deletedAt IS NULL "
            + "ORDER BY g.displayOrder, g.id")
    List<ModifierGroup> findLiveByItem(@Param("itemId") Long itemId);

    @Query("SELECT g FROM ModifierGroup g WHERE g.id = :id AND g.deletedAt IS NULL")
    Optional<ModifierGroup> findLiveById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM ModifierGroup g WHERE g.id = :id AND g.deletedAt IS NULL")
    Optional<ModifierGroup> lockLiveById(@Param("id") Long id);

    @Query("SELECT COALESCE(MAX(g.displayOrder), -1) FROM ModifierGroup g WHERE g.menuItemId = :itemId AND g.deletedAt IS NULL")
    int maxOrder(@Param("itemId") Long itemId);
}
