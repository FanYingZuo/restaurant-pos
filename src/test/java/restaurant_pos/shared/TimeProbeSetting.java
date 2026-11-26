package restaurant_pos.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 測試專用實體（只在 src/test），對應 app_setting，用來驗證 Hibernate 的時間讀寫路徑。
 * 不是正式實體；正式實體由各卡的檔案清單決定。
 */
@Entity
@Table(name = "app_setting")
public class TimeProbeSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "setting_value", nullable = false)
    private String settingValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TimeProbeSetting() {
    }

    public TimeProbeSetting(String settingKey, Instant updatedAt) {
        this.settingKey = settingKey;
        this.settingValue = "probe";
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
