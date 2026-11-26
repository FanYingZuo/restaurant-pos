package restaurant_pos.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import restaurant_pos.shared.push.Pusher;

/** 測試用推播器：收集所有推播，供斷言（§4.11「列印與推播用測試替身收集結果」）。 */
public class RecordingPusher implements Pusher {

    public record Sent(String topic, Object message) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void push(String topic, Object message) {
        sent.add(new Sent(topic, message));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }
}
