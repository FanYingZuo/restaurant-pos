package restaurant_pos.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 菜單分類：單層、軟刪除、上下架。 */
@Entity
@Table(name = "menu_category")
public class MenuCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected MenuCategory() {
    }

    public MenuCategory(String name, int displayOrder, boolean active, Instant createdAt) {
        this.name = name;
        this.displayOrder = displayOrder;
        this.active = active;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void moveTo(int order) {
        this.displayOrder = order;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }
}
