package restaurant_pos.support;

import java.util.List;
import java.util.UUID;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.HmacHasher;
import restaurant_pos.staff.device.PairingService;
import restaurant_pos.staff.employee.PinService;
import restaurant_pos.staff.unlock.UnlockService;
import restaurant_pos.shared.security.UnlockFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

/**
 * 整合測試基底：全部測試共用一個 MySQL 8 容器（01-PLAN §4.11、D-21），每個測試開始前清空資料表
 * （保留 flyway_schema_history 與種子資料表 station、payment_method）並重設時鐘。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestSupportConfiguration.class)
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    /** 測試用圖片目錄（暫存，不污染專案）。 */
    public static final java.nio.file.Path IMAGE_DIR;

    static {
        try {
            IMAGE_DIR = java.nio.file.Files.createTempDirectory("pos-test-images");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("restaurantpos.image-dir", () -> IMAGE_DIR.toString());
    }

    private static final List<String> KEEP_TABLES = List.of("flyway_schema_history", "station", "payment_method");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected HmacHasher hasher;

    @Autowired
    protected PairingService pairingService;

    @Autowired
    protected PinService pinService;

    @Autowired
    protected UnlockService unlockService;

    @Autowired
    protected ObjectMapper json;

    /** 直接寫入一位員工（PIN 為明顯假值），回傳員工編號。role 為 STAFF 或 MANAGER。 */
    protected long createEmployee(String name, String role, String pin) {
        jdbc.update("INSERT INTO employee (name, role, pin_hash, created_at) VALUES (?, ?, ?, NOW(6))",
                name, role, hasher.pin(pin));
        return jdbc.queryForObject("SELECT id FROM employee WHERE pin_hash = ?", Long.class, hasher.pin(pin));
    }

    /** 測試用管理者的 PIN（明顯假值）。 */
    protected static final String MANAGER_PIN = "9999";

    /** 確保有一位測試管理者，並以他的 PIN 取得某功能的解鎖憑證。 */
    protected String unlock(TestDevice device, UnlockFunction function) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE pin_hash = ?", Integer.class, hasher.pin(MANAGER_PIN));
        if (exists == 0) {
            createEmployee("測試店長", "MANAGER", MANAGER_PIN);
        }
        try {
            String body = mvc.perform(post("/api/staff/unlock").header("X-Device-Token", device.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pin\":\"" + MANAGER_PIN + "\",\"function\":\"" + function + "\"}"))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            return json.readTree(body).get("unlockToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 測試用已配對裝置（憑證為明顯假值）。 */
    public record TestDevice(long id, String token, DeviceType type) {
    }

    /** 直接寫入一台已配對的裝置，回傳明碼憑證供測試帶標頭。 */
    protected TestDevice registerDevice(DeviceType type) {
        String token = "test-device-token-" + UUID.randomUUID();
        jdbc.update("INSERT INTO device (device_type, name, credential_hash, created_at) VALUES (?, ?, ?, NOW(6))",
                type.name(), "測試裝置", hasher.deviceCredential(token));
        Long id = jdbc.queryForObject("SELECT id FROM device WHERE credential_hash = ?", Long.class,
                hasher.deviceCredential(token));
        return new TestDevice(id, token, type);
    }

    @BeforeEach
    void resetDatabaseAndClock() {
        clock.set(TestSupportConfiguration.DEFAULT_NOW);
        pairingService.resetLimiter();
        pinService.resetLimiter();
        unlockService.clear();
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                String.class);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : tables) {
            if (!KEEP_TABLES.contains(table.toLowerCase())) {
                jdbc.execute("TRUNCATE TABLE `" + table + "`");
            }
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
