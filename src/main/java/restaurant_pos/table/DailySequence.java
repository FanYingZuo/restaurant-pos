package restaurant_pos.table;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** 每日帳單號流水：營業日為主鍵，悲觀鎖遞增；取消不回收。 */
@Entity
@Table(name = "daily_sequence")
public class DailySequence {

    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "last_no", nullable = false)
    private int lastValue;

    protected DailySequence() {
    }

    public DailySequence(LocalDate businessDate) {
        this.businessDate = businessDate;
        this.lastValue = 0;
    }

    public int next() {
        lastValue++;
        return lastValue;
    }
}
