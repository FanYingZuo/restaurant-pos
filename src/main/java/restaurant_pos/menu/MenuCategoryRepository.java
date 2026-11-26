package restaurant_pos.menu;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 所有查詢都排除已刪除（§4.3「軟刪除記刪除時間，所有查詢排除已刪除」）。 */
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

    @Query("SELECT c FROM MenuCategory c WHERE c.deletedAt IS NULL ORDER BY c.displayOrder, c.id")
    List<MenuCategory> findAllLive();

    @Query("SELECT c FROM MenuCategory c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<MenuCategory> findLiveById(@Param("id") Long id);

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MenuCategory c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<MenuCategory> lockLiveById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MenuCategory c WHERE c.id IN :ids AND c.deletedAt IS NULL ORDER BY c.id")
    List<MenuCategory> lockLiveByIds(@Param("ids") Collection<Long> ids);

    @Query("SELECT c.id FROM MenuCategory c WHERE c.deletedAt IS NULL ORDER BY c.id")
    List<Long> findLiveIds();

    @Query("SELECT COALESCE(MAX(c.displayOrder), -1) FROM MenuCategory c WHERE c.deletedAt IS NULL")
    int maxOrder();
}
