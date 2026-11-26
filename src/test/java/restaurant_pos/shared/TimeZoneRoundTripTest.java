package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 卡 0.3 必測：JDBC 與 Hibernate 時區 UTC，寫入讀出不位移（docs/plan/01-PLAN.md §4.3）。
 * 測試 JVM 預設時區刻意設為非 UTC，才測得出位移。
 */
@Testcontainers
@SpringBootTest
class TimeZoneRoundTripTest {

    static final TimeZone ORIGINAL = TimeZone.getDefault();

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
    }

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    static final AtomicInteger SEQ = new AtomicInteger();

    @PersistenceContext
    EntityManager em;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private Long save(Instant value) {
        String key = "tz.probe." + SEQ.incrementAndGet();
        return tx().execute(status -> {
            TimeProbeSetting row = new TimeProbeSetting(key, value);
            em.persist(row);
            em.flush();
            return row.getId();
        });
    }

    private Instant load(Long id) {
        return tx().execute(status -> {
            em.clear();
            return em.find(TimeProbeSetting.class, id).getUpdatedAt();
        });
    }

    @Test
    void JVM為台北時區時寫入讀出不位移() {
        Instant value = Instant.parse("2026-11-26T20:30:00.123456Z");
        assertThat(load(save(value))).isEqualTo(value);
    }

    @Test
    void 資料庫內實際存的是UTC() {
        Instant value = Instant.parse("2026-11-26T20:30:00.123456Z");
        Long id = save(value);
        String raw = jdbc.queryForObject(
                "SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f') FROM app_setting WHERE id = ?",
                String.class, id);
        assertThat(raw).isEqualTo("2026-11-26 20:30:00.123456");
    }

    @Test
    void JVM改為洛杉磯時區後讀出不變() {
        Instant value = Instant.parse("2026-11-26T20:30:00.123456Z");
        Long id = save(value);
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        assertThat(load(id)).isEqualTo(value);
    }

    @Test
    void 夏令時間邊界寫入讀出不位移() {
        // 2026-03-08 洛杉磯進入夏令時間（10:00Z 為 02:00 -> 03:00 的跳躍點之後）
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        Instant value = Instant.parse("2026-03-08T10:30:00Z");
        assertThat(load(save(value))).isEqualTo(value);
    }

    @Test
    void 微秒精度完整保留() {
        Instant value = Instant.parse("2026-11-26T20:30:00.654321Z");
        assertThat(load(save(value)).getNano()).isEqualTo(654_321_000);
    }

    @Test
    void 連線時區為UTC() {
        String zone = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
        assertThat(zone).isEqualTo("+00:00");
    }
}
