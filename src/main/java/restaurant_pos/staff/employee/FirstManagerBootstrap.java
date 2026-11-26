package restaurant_pos.staff.employee;

import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.config.PosProperties;
import restaurant_pos.shared.security.HmacHasher;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 首位管理者（§4.5）：啟動時沒有在職管理者就建立「店長」。
 * PIN 來自環境變數 POS_FIRST_MANAGER_PIN；未設定（或格式不合法）則產生隨機六位數，**只印在主控台一次**，不寫入日誌。
 */
@Component
public class FirstManagerBootstrap implements ApplicationRunner {

    public static final String FIRST_MANAGER_NAME = "店長";
    private static final Logger log = LoggerFactory.getLogger(FirstManagerBootstrap.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmployeeRepository employees;
    private final HmacHasher hasher;
    private final BusinessDayService time;
    private final PosProperties properties;
    private final TransactionTemplate tx;

    public FirstManagerBootstrap(EmployeeRepository employees, HmacHasher hasher, BusinessDayService time,
            PosProperties properties, TransactionTemplate tx) {
        this.employees = employees;
        this.hasher = hasher;
        this.time = time;
        this.properties = properties;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        String printedPin = tx.execute(status -> {
            if (employees.countActiveManagers() > 0) {
                return null;
            }
            String configured = properties.firstManagerPin();
            boolean useConfigured = configured != null && !configured.isBlank();
            if (useConfigured && !PinService.isValidFormat(configured.trim())) {
                log.warn("POS_FIRST_MANAGER_PIN 格式不合法（需為 4～6 位數字），改用隨機 PIN");
                useConfigured = false;
            }
            if (useConfigured) {
                String pin = configured.trim();
                if (employees.findActiveByPinHash(hasher.pin(pin)).isPresent()) {
                    // 該 PIN 已被在職員工使用：不可重複，改用隨機 PIN
                    log.warn("POS_FIRST_MANAGER_PIN 已被其他在職員工使用，改用隨機 PIN");
                } else {
                    create(pin);
                    return "";
                }
            }
            String random = randomUnusedPin();
            create(random);
            return random;
        });
        if (printedPin == null) {
            return;
        }
        log.info("沒有在職管理者，已建立首位管理者「{}」", FIRST_MANAGER_NAME);
        if (!printedPin.isEmpty()) {
            System.out.println("==============================================================");
            System.out.println("首位店長 PIN（只顯示這一次，請立即記下）：" + printedPin);
            System.out.println("==============================================================");
        }
    }

    private void create(String pin) {
        employees.saveAndFlush(new Employee(FIRST_MANAGER_NAME, EmployeeRole.MANAGER, hasher.pin(pin), time.now()));
    }

    private String randomUnusedPin() {
        for (int i = 0; i < 50; i++) {
            String pin = String.format("%06d", RANDOM.nextInt(1_000_000));
            if (employees.findActiveByPinHash(hasher.pin(pin)).isEmpty()) {
                return pin;
            }
        }
        throw new IllegalStateException("無法產生未被使用的 PIN");
    }
}
