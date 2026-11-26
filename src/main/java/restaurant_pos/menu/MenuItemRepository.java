package restaurant_pos.menu;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    @Query("SELECT i FROM MenuItem i WHERE i.deletedAt IS NULL ORDER BY i.displayOrder, i.id")
    List<MenuItem> findAllLive();

    @Query("SELECT i FROM MenuItem i WHERE i.id = :id AND i.deletedAt IS NULL")
    Optional<MenuItem> findLiveById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM MenuItem i WHERE i.id = :id AND i.deletedAt IS NULL")
    Optional<MenuItem> lockLiveById(@Param("id") Long id);

    @Query("SELECT i FROM MenuItem i WHERE i.categoryId = :categoryId AND i.deletedAt IS NULL "
            + "ORDER BY i.displayOrder, i.id")
    List<MenuItem> findLiveByCategory(@Param("categoryId") Long categoryId);

    @Query("SELECT COUNT(i) FROM MenuItem i WHERE i.categoryId = :categoryId AND i.deletedAt IS NULL")
    long countLiveByCategory(@Param("categoryId") Long categoryId);

    @Query("SELECT COUNT(i) FROM MenuItem i WHERE i.categoryId = :categoryId AND i.deletedAt IS NULL AND i.active = true")
    long countActiveByCategory(@Param("categoryId") Long categoryId);

    @Query("SELECT COALESCE(MAX(i.displayOrder), -1) FROM MenuItem i WHERE i.categoryId = :categoryId AND i.deletedAt IS NULL")
    int maxOrder(@Param("categoryId") Long categoryId);

    @Query("SELECT i FROM MenuItem i WHERE i.id IN :ids AND i.deletedAt IS NULL")
    List<MenuItem> findLiveByIds(@Param("ids") Collection<Long> ids);
}
