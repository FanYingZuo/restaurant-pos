package restaurant_pos.kds;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import restaurant_pos.shared.security.DeviceAuthenticator;

/**
 * 記錄目前已連線的 WebSocket 與其裝置，並在每次「心跳週期」重新檢查裝置（§4.5「撤銷後下次心跳斷線」）。
 * 不記錄任何憑證；只存連線編號與裝置編號。
 */
@Component
public class DeviceSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceSessionRegistry.class);

    private final Map<String, Long> deviceBySession = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final DeviceAuthenticator devices;

    public DeviceSessionRegistry(DeviceAuthenticator devices) {
        this.devices = devices;
    }

    void register(String sessionId, long deviceId) {
        deviceBySession.put(sessionId, deviceId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        deviceBySession.remove(event.getSessionId());
    }

    /** 追蹤底層 WebSocket 連線，才能在裝置被撤銷時關閉它。 */
    WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sockets.remove(session.getId());
                deviceBySession.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** 每個心跳週期重新檢查所有連線的裝置；已撤銷（或不再有效）就斷線。 */
    @Scheduled(fixedDelayString = "${restaurantpos.ws.heartbeat-ms:25000}")
    public void recheckDevices() {
        deviceBySession.forEach((sessionId, deviceId) -> {
            boolean active;
            try {
                active = devices.isActive(deviceId);
            } catch (RuntimeException e) {
                log.warn("重新檢查裝置失敗：{}", e.getClass().getSimpleName());
                return;
            }
            if (!active) {
                WebSocketSession socket = sockets.get(sessionId);
                deviceBySession.remove(sessionId);
                if (socket != null && socket.isOpen()) {
                    try {
                        socket.close(CloseStatus.POLICY_VIOLATION);
                    } catch (Exception e) {
                        log.warn("關閉 WebSocket 失敗：{}", e.getClass().getSimpleName());
                    }
                }
                log.info("裝置 {} 已失效，已中斷其 WebSocket 連線", deviceId);
            }
        });
    }

    int connectedCount() {
        return deviceBySession.size();
    }

}
