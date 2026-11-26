package restaurant_pos.table;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 桌位（dining_table）：版面資料；軟刪除。 */
@Entity
@Table(name = "dining_table")
public class DiningTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_no", nullable = false, length = 20)
    private String tableNo;

    @Column(nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TableShape shape;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false, length = 32)
    private String area;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DiningTable() {
    }

    public DiningTable(String tableNo, int capacity, TableShape shape, int width, int height, int x, int y,
            String area, Instant createdAt) {
        this.tableNo = tableNo;
        this.capacity = capacity;
        this.shape = shape;
        this.width = width;
        this.height = height;
        this.x = x;
        this.y = y;
        this.area = area;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getTableNo() {
        return tableNo;
    }

    public int getCapacity() {
        return capacity;
    }

    public TableShape getShape() {
        return shape;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public String getArea() {
        return area;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void update(String tableNo, int capacity, TableShape shape, int width, int height, int x, int y, String area) {
        this.tableNo = tableNo;
        this.capacity = capacity;
        this.shape = shape;
        this.width = width;
        this.height = height;
        this.x = x;
        this.y = y;
        this.area = area;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
    }
}
