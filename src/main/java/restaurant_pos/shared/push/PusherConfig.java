package restaurant_pos.shared.push;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 沒有其他實作時的預設推播器（什麼都不做）。真正的 STOMP 實作在卡 6.3。 */
@Configuration
public class PusherConfig {

    @Bean
    @ConditionalOnMissingBean(Pusher.class)
    public Pusher noopPusher() {
        return (topic, message) -> {
        };
    }
}
