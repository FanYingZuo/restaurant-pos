package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 卡 0.2 必測：遷移成功（MySQL 8、Flyway V1）。
 * 欄位與約束依 docs/plan/01-PLAN.md §4.2、§4.3；欄位草擬經使用者審定（待決 #5 選項 A）。
 */
@Testcontainers
@SpringBootTest
class MigrationV1Test {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Test
    void 執行環境是MySQL8() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        assertThat(version).startsWith("8.0");
    }

    @Test
    void V1已成功套用() {
        Integer success = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class);
        assertThat(success).isEqualTo(1);
    }

    @Test
    void S0三張表都存在() {
        for (String table : new String[] {"app_setting", "audit_log", "device"}) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, table);
            assertThat(n).as(table).isEqualTo(1);
        }
    }

    @Test
    void 時間欄位為DATETIME6() {
        String type = jdbc.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'created_at'",
                String.class);
        assertThat(type).isEqualTo("datetime(6)");
    }

    @Test
    void app_setting鍵唯一() {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) "
                + "VALUES ('test.unique.key', 'v1', NOW(6))");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_setting (setting_key, setting_value, updated_at) "
                        + "VALUES ('test.unique.key', 'v2', NOW(6))"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void device憑證雜湊唯一() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbc.update("INSERT INTO device (device_type, name, credential_hash, created_at) "
                + "VALUES ('POS', 'test-device-a', ?, NOW(6))", hash);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO device (device_type, name, credential_hash, created_at) "
                        + "VALUES ('KDS', 'test-device-b', ?, NOW(6))", hash))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 重複執行遷移不會重跑V1() {
        flyway.migrate();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
