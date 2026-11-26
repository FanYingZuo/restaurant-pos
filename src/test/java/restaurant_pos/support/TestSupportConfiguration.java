package restaurant_pos.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestSupportConfiguration {

    /** 預設時間：2026-03-02T00:00:00Z（台北 08:00，營業日 2026-03-02）。 */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-03-02T00:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(DEFAULT_NOW);
    }

    @Bean
    @Primary
    public RecordingPusher recordingPusher() {
        return new RecordingPusher();
    }
}
