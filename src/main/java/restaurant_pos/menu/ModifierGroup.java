package restaurant_pos.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 選項群組：最少不得大於最多；軟刪除（刪群組連同選項一起軟刪除）。 */
@Entity
@Table(name = "modifier_group")
public class ModifierGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "min_select", nullable = false)
    private int minSelect;

    @Column(name = "max_select", nullable = false)
    private int maxSelect;

    @Column(name = "allow_duplicate", nullable = false)
    private boolean allowDuplicate;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ModifierGroup() {
    }

    public ModifierGroup(Long menuItemId, String name, int minSelect, int maxSelect, boolean allowDuplicate,
            int displayOrder, Instant createdAt) {
        this.menuItemId = menuItemId;
        this.name = name;
        this.minSelect = minSelect;
        this.maxSelect = maxSelect;
        this.allowDuplicate = allowDuplicate;
        this.displayOrder = displayOrder;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public String getName() {
        return name;
    }

    public int getMinSelect() {
        return minSelect;
    }

    public int getMaxSelect() {
        return maxSelect;
    }

    public boolean isAllowDuplicate() {
        return allowDuplicate;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void update(String newName, int newMin, int newMax, boolean newAllowDuplicate) {
        this.name = newName;
        this.minSelect = newMin;
        this.maxSelect = newMax;
        this.allowDuplicate = newAllowDuplicate;
    }

    public void moveTo(int order) {
        this.displayOrder = order;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }
}
