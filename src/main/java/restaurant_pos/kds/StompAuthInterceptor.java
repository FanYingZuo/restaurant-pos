package restaurant_pos.kds;

import java.security.Principal;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import restaurant_pos.shared.security.DeviceAuthenticator;
import restaurant_pos.shared.security.DevicePrincipal;

/**
 * STOMP 連線驗證（§4.5、S6）：
 * <ul>
 *   <li>CONNECT 帧的 {@code X-Device-Token} 驗證裝置（POS 與 KDS 皆可）；缺少或無效 → 回 ERROR 並斷線。憑證不放網址參數。</li>
 *   <li>只允許訂閱 {@code /topic/kds/K1}、{@code /topic/kds/K2}。</li>
 *   <li>用戶端傳入的訊息（SEND）一律忽略：用戶端只訂閱。</li>
 * </ul>
 * 錯誤訊息固定，不含任何憑證或原因差異。
 */
public class StompAuthInterceptor implements ChannelInterceptor {

    static final String TOKEN_HEADER = "X-Device-Token";
    private static final Pattern ALLOWED_TOPIC = Pattern.compile("^/topic/kds/(K1|K2)$");

    private final DeviceAuthenticator devices;
    private final DeviceSessionRegistry registry;

    public StompAuthInterceptor(DeviceAuthenticator devices, DeviceSessionRegistry registry) {
        this.devices = devices;
        this.registry = registry;
    }

    /** 以裝置編號為名稱的連線身分。 */
    record DevicePrincipalAdapter(long deviceId) implements Principal {
        @Override
        public String getName() {
            return String.valueOf(deviceId);
        }
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            String token = accessor.getFirstNativeHeader(TOKEN_HEADER);
            Optional<DevicePrincipal> device = token == null || token.isBlank() || token.length() > 256
                    ? Optional.empty() : devices.authenticate(token.trim());
            if (device.isEmpty()) {
                throw new MessagingException("連線驗證失敗");
            }
            accessor.setUser(new DevicePrincipalAdapter(device.get().deviceId()));
            registry.register(accessor.getSessionId(), device.get().deviceId());
            return message;
        }
        if (StompCommand.SEND.equals(command)) {
            return null; // 傳入訊息一律忽略
        }
        if (StompCommand.SUBSCRIBE.equals(command)) {
            if (accessor.getUser() == null) {
                throw new MessagingException("尚未驗證");
            }
            String destination = accessor.getDestination();
            if (destination == null || !ALLOWED_TOPIC.matcher(destination).matches()) {
                throw new MessagingException("不允許的訂閱");
            }
        }
        return message;
    }
}
