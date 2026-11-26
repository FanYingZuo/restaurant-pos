package restaurant_pos.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import restaurant_pos.shared.model.Station;

/** 菜單品項：價格為整數元（DECIMAL(12,2)）；圖片只存鍵；售完營業日；軟刪除。 */
@Entity
@Table(name = "menu_item")
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "station_code", nullable = false, length = 8)
    private Station station;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sold_out_date")
    private LocalDate soldOutDate;

    @Column(name = "image_key", length = 64)
    private String imageKey;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected MenuItem() {
    }

    public MenuItem(Long categoryId, String name, BigDecimal price, Station station, boolean active,
            int displayOrder, Instant createdAt) {
        this.categoryId = categoryId;
        this.name = name;
        this.price = price;
        this.station = station;
        this.active = active;
        this.displayOrder = displayOrder;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Station getStation() {
        return station;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDate getSoldOutDate() {
        return soldOutDate;
    }

    public String getImageKey() {
        return imageKey;
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

    public void changePrice(BigDecimal newPrice) {
        this.price = newPrice;
    }

    public void changeStation(Station newStation) {
        this.station = newStation;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void moveTo(int order) {
        this.displayOrder = order;
    }

    /** 設為售完：記錄售完的營業日（隔營業日自動恢復，查詢時判斷）。 */
    public void soldOutOn(LocalDate businessDate) {
        this.soldOutDate = businessDate;
    }

    /** 手動恢復供應。重新上架不會呼叫這個方法（不清售完日）。 */
    public void clearSoldOut() {
        this.soldOutDate = null;
    }

    public void setImageKey(String imageKey) {
        this.imageKey = imageKey;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }
}
