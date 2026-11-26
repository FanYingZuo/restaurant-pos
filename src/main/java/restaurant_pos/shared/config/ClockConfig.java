package restaurant_pos.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注入式時鐘：「現在」只能來自這個 Clock（§4.4）；測試以可推進的時鐘取代。 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
