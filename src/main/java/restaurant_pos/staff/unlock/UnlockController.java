package restaurant_pos.staff.unlock;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.UnlockFunction;

/** 管理者解鎖 API（合約 unlock、releaseUnlock）。 */
@RestController
@RequestMapping("/api/staff/unlock")
public class UnlockController {

    public record UnlockRequest(String pin, UnlockFunction function) {
        @Override
        public String toString() {
            return "UnlockRequest[function=" + function + "]";
        }
    }

    public record UnlockResponse(String unlockToken, UnlockFunction function) {
        @Override
        public String toString() {
            return "UnlockResponse[function=" + function + "]";
        }
    }

    private final UnlockService unlocks;

    public UnlockController(UnlockService unlocks) {
        this.unlocks = unlocks;
    }

    @PostMapping
    public ResponseEntity<UnlockResponse> unlock(@RequestBody UnlockRequest request) {
        if (request == null || request.function() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        UnlockService.Unlocked unlocked = unlocks.unlock(request.pin(), request.function());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new UnlockResponse(unlocked.token(), unlocked.function()));
    }

    @DeleteMapping
    public ResponseEntity<Void> release(@RequestHeader(value = "X-Unlock-Token", required = false) String token) {
        unlocks.release(token);
        return ResponseEntity.noContent().build();
    }
}
