package restaurant_pos.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** 選項快照（order_item_modifier）：依群組順序。 */
@Entity
@Table(name = "order_item_modifier")
public class OrderItemModifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "modifier_id", nullable = false)
    private Long modifierId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceDelta;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected OrderItemModifier() {
    }

    public OrderItemModifier(Long orderItemId, Long modifierId, String name, BigDecimal priceDelta, int quantity,
            int sortOrder) {
        this.orderItemId = orderItemId;
        this.modifierId = modifierId;
        this.name = name;
        this.priceDelta = priceDelta;
        this.quantity = quantity;
        this.sortOrder = sortOrder;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public Long getModifierId() {
        return modifierId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPriceDelta() {
        return priceDelta;
    }

    public int getQuantity() {
        return quantity;
    }
}
