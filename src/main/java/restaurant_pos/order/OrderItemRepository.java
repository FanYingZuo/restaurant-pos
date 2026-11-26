package restaurant_pos.order;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("SELECT i FROM OrderItem i WHERE i.orderId = :orderId ORDER BY i.id")
    List<OrderItem> findByOrder(@Param("orderId") Long orderId);

    @Query("SELECT i FROM OrderItem i WHERE i.orderId IN :orderIds ORDER BY i.id")
    List<OrderItem> findByOrders(@Param("orderIds") Collection<Long> orderIds);

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM OrderItem i WHERE i.id = :id")
    Optional<OrderItem> lockById(@Param("id") Long id);

    /** 廚房清單：指定站別與狀態的正式單品項，連同其訂單（依品項編號遞增）。一次查詢。 */
    @Query("SELECT i, o FROM OrderItem i, DiningOrder o WHERE i.orderId = o.id AND o.batchNo IS NOT NULL "
            + "AND i.station = :station AND i.status IN :statuses ORDER BY i.id")
    List<Object[]> findKitchenRows(@Param("station") restaurant_pos.shared.model.Station station,
            @Param("statuses") Collection<ItemStatus> statuses);

    @Query("SELECT i, o FROM OrderItem i, DiningOrder o WHERE i.orderId = o.id AND o.batchNo IS NOT NULL AND i.id = :id")
    List<Object[]> findKitchenRow(@Param("id") Long id);

    /** 正式單中未取消的品項數（取消開桌的判斷）。 */
    @Query("SELECT COUNT(i) FROM OrderItem i, DiningOrder o WHERE i.orderId = o.id AND o.sessionId = :sessionId "
            + "AND o.batchNo IS NOT NULL AND i.status <> restaurant_pos.order.ItemStatus.CANCELLED")
    long countLiveFormal(@Param("sessionId") Long sessionId);
}
