package restaurant_pos.kds;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import restaurant_pos.shared.config.PosProperties;
import restaurant_pos.shared.security.DeviceAuthenticator;

/**
 * WebSocket（STOMP over 原生 WebSocket，不用 SockJS）設定（§4.5、S6）：
 * 端點 {@code /ws}；主題每站一個（{@code /topic/kds/K1}、{@code /topic/kds/K2}）；伺服器每 25 秒心跳；
 * 只允許公開基底網址的來源（Origin）。
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableScheduling
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** 伺服器心跳預設 25 秒（毫秒）。 */
    public static final long DEFAULT_HEARTBEAT_MS = 25_000;

    private final PosProperties properties;
    private final DeviceAuthenticator devices;
    private final DeviceSessionRegistry registry;
    private final long heartbeatMs;

    public WebSocketConfig(PosProperties properties, DeviceAuthenticator devices, DeviceSessionRegistry registry,
            @Value("${restaurantpos.ws.heartbeat-ms:25000}") long heartbeatMs) {
        this.properties = properties;
        this.devices = devices;
        this.registry = registry;
        this.heartbeatMs = heartbeatMs;
    }

    @Bean
    public ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigin());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {heartbeatMs, 0})
                .setTaskScheduler(wsHeartbeatScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(devices, registry));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(registry::decorate);
    }

    /** 只允許公開基底網址的來源：scheme://host[:port]。 */
    private String allowedOrigin() {
        String base = properties.baseUrl();
        if (base == null || base.isBlank()) {
            return "http://localhost:8080";
        }
        URI uri = URI.create(base.trim());
        return uri.getScheme() + "://" + uri.getAuthority();
    }
}
