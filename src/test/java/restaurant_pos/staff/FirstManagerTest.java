package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.config.PosProperties;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.staff.employee.EmployeeRepository;
import restaurant_pos.staff.employee.FirstManagerBootstrap;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 2.2：首位管理者（01-PLAN §4.5「PIN」最後一點）。 */
class FirstManagerTest extends AbstractIntegrationTest {

    @Autowired
    FirstManagerBootstrap runner;

    @Autowired
    EmployeeRepository employees;

    @Autowired
    BusinessDayService time;

    @Autowired
    TransactionTemplate tx;

    private record Result(String console, ListAppender<ILoggingEvent> logs) {
    }

    private FirstManagerBootstrap withEnvPin(String pin) {
        return new FirstManagerBootstrap(employees, hasher, time,
                new PosProperties("http://localhost:8080", null, null, pin, "images"), tx);
    }

    private Result run(FirstManagerBootstrap bootstrap) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            bootstrap.run(null);
        } finally {
            System.setOut(original);
            root.detachAppender(appender);
        }
        return new Result(captured.toString(StandardCharsets.UTF_8), appender);
    }

    private static String pinFrom(String console) {
        Matcher m = Pattern.compile("：([0-9]{6})").matcher(console);
        assertThat(m.find()).as("主控台輸出：" + console).isTrue();
        return m.group(1);
    }

    private int employeeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM employee", Integer.class);
    }

    @Test
    void 沒有在職管理者時建立店長_PIN為隨機六位數且只印在主控台一次() throws Exception {
        Result result = run(runner);
        String pin = pinFrom(result.console());
        assertThat(employeeCount()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT name, role FROM employee")).containsEntry("name", "店長").containsEntry("role", "MANAGER");
        assertThat(result.console().split(pin, -1)).hasSize(2); // PIN 只出現一次
        assertThat(result.logs().list).noneMatch(e -> e.getFormattedMessage().contains(pin));

        TestDevice pos = registerDevice(DeviceType.POS);
        mvc.perform(post("/api/pos/test-pin").header("X-Device-Token", pos.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.manager").value(true)).andExpect(jsonPath("$.name").value("店長"));
    }

    @Test
    void PIN來自環境變數時使用該PIN_不印出() throws Exception {
        Result result = run(withEnvPin("4821"));
        assertThat(result.console()).doesNotContain("4821").doesNotContain("PIN");
        assertThat(result.logs().list).noneMatch(e -> e.getFormattedMessage().contains("4821"));
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM employee", String.class)).isEqualTo(hasher.pin("4821"));
    }

    @Test
    void 已有在職管理者時什麼都不做() {
        createEmployee("經理", "MANAGER", "5555");
        Result result = run(runner);
        assertThat(result.console()).isEmpty();
        assertThat(employeeCount()).isEqualTo(1);
    }

    @Test
    void 只有員工沒有管理者時仍建立店長() {
        createEmployee("小明", "STAFF", "1234");
        run(runner);
        assertThat(employeeCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE role = 'MANAGER'", Integer.class)).isEqualTo(1);
    }

    @Test
    void 管理者都已停用時重新建立店長() {
        long id = createEmployee("舊店長", "MANAGER", "5555");
        jdbc.update("UPDATE employee SET deactivated_at = NOW(6), pin_hash = NULL WHERE id = ?", id);
        run(runner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE role = 'MANAGER' AND deactivated_at IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 環境變數PIN格式不合法時改用隨機PIN_警告不含該值() {
        Result result = run(withEnvPin("abcd"));
        String pin = pinFrom(result.console());
        assertThat(pin).hasSize(6);
        assertThat(result.logs().list).anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(result.logs().list).noneMatch(e -> e.getFormattedMessage().contains("abcd"));
    }

    @Test
    void 環境變數PIN已被在職員工使用時改用隨機PIN() {
        createEmployee("小明", "STAFF", "4821");
        Result result = run(withEnvPin("4821"));
        String pin = pinFrom(result.console());
        assertThat(pin).isNotEqualTo("4821");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE role = 'MANAGER'", Integer.class)).isEqualTo(1);
        assertThat(result.logs().list).noneMatch(e -> e.getFormattedMessage().contains("4821"));
    }

    @Test
    void 重複執行不會建立第二位店長() {
        run(runner);
        Result second = run(runner);
        assertThat(second.console()).isEmpty();
        assertThat(employeeCount()).isEqualTo(1);
    }
}
