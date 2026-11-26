package restaurant_pos.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import restaurant_pos.shared.push.Pusher;

/**
 * 測試用推播器（§4.11「列印與推播用測試替身收集結果」）：先記錄每一則推播，再轉給真正的推播器（若有），
 * 所以既能斷言訊息內容，也能做端到端的 WebSocket 測試。可設定「下一次推播丟例外」來驗證推播失敗不影響 API。
 */
public class RecordingPusher implements Pusher {

    public record Sent(String topic, Object message) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Pusher delegate;
    private volatile boolean failNext;

    public RecordingPusher() {
        this(null);
    }

    public RecordingPusher(Pusher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void push(String topic, Object message) {
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("模擬推播失敗");
        }
        sent.add(new Sent(topic, message));
        if (delegate != null) {
            delegate.push(topic, message);
        }
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** 下一次 push 丟出例外（只一次）。 */
    public void failNextPush() {
        failNext = true;
    }

    public void clear() {
        sent.clear();
        failNext = false;
    }
}
