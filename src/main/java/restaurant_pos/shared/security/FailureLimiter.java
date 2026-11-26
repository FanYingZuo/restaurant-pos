package restaurant_pos.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 記憶體內的失敗次數限制（防暴力，§4.5）：視窗期間內失敗達上限就鎖定一段時間；
 * 鎖定期間**任何**嘗試（含正確的）都視為被鎖。成功不清除失敗計數。重啟後歸零（已接受的限制 1）。
 *
 * <p>達到上限的那一次失敗本身仍回報一般錯誤，之後的嘗試才回報「被鎖」。
 */
public class FailureLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public FailureLimiter(int maxFailures, Duration window, Duration lockDuration, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    private static final class State {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant lockedUntil;
    }

    /** 目前是否被鎖定。 */
    public boolean isLocked(String key) {
        State state = states.get(key);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.lockedUntil == null) {
                return false;
            }
            if (state.lockedUntil.isAfter(clock.instant())) {
                return true;
            }
            // 鎖定已過期：清空狀態
            state.lockedUntil = null;
            state.failures.clear();
            return false;
        }
    }

    /** 記錄一次失敗。回傳這次失敗是否使該 key 進入鎖定。 */
    public boolean recordFailure(String key) {
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            Instant now = clock.instant();
            Instant cutoff = now.minus(window);
            while (!state.failures.isEmpty() && state.failures.peekFirst().isBefore(cutoff)) {
                state.failures.pollFirst();
            }
            state.failures.addLast(now);
            if (state.failures.size() >= maxFailures) {
                state.lockedUntil = now.plus(lockDuration);
                state.failures.clear();
                return true;
            }
            return false;
        }
    }

    /** 清除全部狀態（模擬重啟與測試用）。 */
    public void clear() {
        states.clear();
    }
}
