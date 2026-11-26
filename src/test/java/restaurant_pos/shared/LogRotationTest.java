package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.core.util.FileSize;
import org.springframework.test.util.ReflectionTestUtils;

/** 卡 0.4：日誌依日輪替、單檔超過 50 MB 也輪替、應用程式日誌保存 90 天（§4.12）。 */
class LogRotationTest extends restaurant_pos.support.AbstractIntegrationTest {

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    private static long maxFileSize(SizeAndTimeBasedRollingPolicy<?> policy) {
        FileSize size = (FileSize) ReflectionTestUtils.getField(policy, "maxFileSize");
        return size.getSize();
    }

    private SizeAndTimeBasedRollingPolicy<?> policyOf(Logger logger, String appenderName) {
        RollingFileAppender<?> appender = (RollingFileAppender<?>) logger.getAppender(appenderName);
        assertThat(appender).as(appenderName).isNotNull();
        assertThat(appender.getRollingPolicy()).isInstanceOf(SizeAndTimeBasedRollingPolicy.class);
        return (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
    }

    @Test
    void pos_log依日與50MB輪替並保存90天() {
        SizeAndTimeBasedRollingPolicy<?> policy = policyOf(context.getLogger(Logger.ROOT_LOGGER_NAME), "POS_FILE");
        assertThat(maxFileSize(policy)).isEqualTo(50L * 1024 * 1024);
        assertThat(policy.getMaxHistory()).isEqualTo(90);
        assertThat(policy.getFileNamePattern()).contains("%d{yyyy-MM-dd}").contains("%i");
    }

    @Test
    void print_log依日與50MB輪替() {
        SizeAndTimeBasedRollingPolicy<?> policy = policyOf(context.getLogger("restaurant_pos.print.file"), "PRINT_FILE");
        assertThat(maxFileSize(policy)).isEqualTo(50L * 1024 * 1024);
        assertThat(policy.getFileNamePattern()).contains("%d{yyyy-MM-dd}").contains("%i");
    }

    @Test
    void print_log記錄器不進pos_log() {
        Logger printLogger = context.getLogger("restaurant_pos.print.file");
        assertThat(printLogger.isAdditive()).isFalse();
    }
}
