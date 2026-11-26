package restaurant_pos.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** 選項：加價不得為負（0 允許）；軟刪除。 */
@Entity
@Table(name = "modifier")
public class Modifier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "modifier_group_id", nullable = false)
    private Long modifierGroupId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceDelta;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Modifier() {
    }

    public Modifier(Long modifierGroupId, String name, BigDecimal priceDelta, int displayOrder, Instant createdAt) {
        this.modifierGroupId = modifierGroupId;
        this.name = name;
        this.priceDelta = priceDelta;
        this.displayOrder = displayOrder;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getModifierGroupId() {
        return modifierGroupId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPriceDelta() {
        return priceDelta;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void changePriceDelta(BigDecimal newPriceDelta) {
        this.priceDelta = newPriceDelta;
    }

    public void moveTo(int order) {
        this.displayOrder = order;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }
}
