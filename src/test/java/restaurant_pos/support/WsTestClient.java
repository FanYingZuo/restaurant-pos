package restaurant_pos.support;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** 測試用 STOMP 用戶端：記錄連線結果、錯誤、斷線與收到的訊息。只存在於 src/test。 */
public class WsTestClient implements AutoCloseable {

    public static final String DEFAULT_ORIGIN = "http://localhost:8080";

    public final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
    public final CompletableFuture<StompHeaders> connected = new CompletableFuture<>();
    public final CompletableFuture<String> failure = new CompletableFuture<>();
    public final CompletableFuture<Void> closed = new CompletableFuture<>();

    private final WebSocketStompClient client;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private volatile StompSession session;

    private WsTestClient() {
        scheduler.setPoolSize(1);
        scheduler.initialize();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setTaskScheduler(scheduler);
    }

    /** 連線。token 為 null 表示不帶憑證；origin 為 null 表示不帶 Origin 標頭。 */
    public static WsTestClient connect(int port, String token, String origin) {
        WsTestClient c = new WsTestClient();
        WebSocketHttpHeaders http = new WebSocketHttpHeaders();
        if (origin != null) {
            http.setOrigin(origin);
        }
        StompHeaders stomp = new StompHeaders();
        if (token != null) {
            stomp.add("X-Device-Token", token);
        }
        stomp.setHeartbeat(new long[] {0, 500});
        c.client.connectAsync("ws://localhost:" + port + "/ws", http, stomp, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession s, StompHeaders headers) {
                c.session = s;
                c.connected.complete(headers);
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                c.failure.complete("STOMP ERROR：" + headers.getFirst("message"));
            }

            @Override
            public void handleException(StompSession s, org.springframework.messaging.simp.stomp.StompCommand command,
                    StompHeaders headers, byte[] payload, Throwable exception) {
                c.failure.complete("例外：" + exception.getClass().getSimpleName());
            }

            @Override
            public void handleTransportError(StompSession s, Throwable exception) {
                c.failure.complete("傳輸錯誤：" + exception.getClass().getSimpleName());
                c.closed.complete(null);
            }
        }).whenComplete((s, error) -> {
            if (error != null) {
                c.failure.complete("連線失敗：" + error.getClass().getSimpleName());
                c.closed.complete(null);
            }
        });
        return c;
    }

    @SuppressWarnings("unchecked")
    public void subscribe(String destination) {
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });
    }

    public void send(String destination, Object payload) {
        session.send(destination, payload);
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /** 等待下一則訊息；逾時回 null。 */
    public Map<String, Object> next(Duration timeout) throws InterruptedException {
        return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } finally {
            client.stop();
            scheduler.shutdown();
        }
    }
}
