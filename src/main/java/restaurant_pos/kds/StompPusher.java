package restaurant_pos.kds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import restaurant_pos.shared.push.Pusher;

/** 真正的推播器（STOMP）。失敗只記日誌（不含訊息內容），不丟例外（§4.7）。 */
@Component
public class StompPusher implements Pusher {

    private static final Logger log = LoggerFactory.getLogger(StompPusher.class);

    private final SimpMessagingTemplate template;

    public StompPusher(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void push(String topic, Object message) {
        try {
            template.convertAndSend(topic, message);
        } catch (RuntimeException e) {
            log.warn("推播失敗（{}）：{}", topic, e.getClass().getSimpleName());
        }
    }
}
