package restaurant_pos.order;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemModifierRepository extends JpaRepository<OrderItemModifier, Long> {

    @Query("SELECT m FROM OrderItemModifier m WHERE m.orderItemId IN :itemIds ORDER BY m.orderItemId, m.id")
    List<OrderItemModifier> findByItems(@Param("itemIds") Collection<Long> itemIds);

    @Modifying
    @Query("DELETE FROM OrderItemModifier m WHERE m.orderItemId IN :itemIds")
    void deleteByItems(@Param("itemIds") Collection<Long> itemIds);
}
