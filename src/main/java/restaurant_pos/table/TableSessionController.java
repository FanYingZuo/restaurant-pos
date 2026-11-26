package restaurant_pos.table;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.table.TableDtos.SessionSummary;

/** 用餐紀錄 API（合約 getSessionQr、updateGuestCount、setServiceCharge、listPendingRecheckout）。 */
@RestController
@RequestMapping("/api/pos/sessions")
public class TableSessionController {

    public record GuestCountRequest(Integer guestCount) {
    }

    public record ServiceChargeRequest(Boolean enabled, String pin) {
        @Override
        public String toString() {
            return "ServiceChargeRequest[enabled=" + enabled + "]";
        }
    }

    private final TableSessionService sessions;

    public TableSessionController(TableSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/pending-recheckout")
    public List<SessionSummary> pendingRecheckout() {
        return sessions.pendingRecheckout();
    }

    /** 需裝置憑證標頭，前端須以程式取得 blob 後顯示。圖片含顧客 token，不可被快取。 */
    @GetMapping(value = "/{sessionId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable long sessionId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(sessions.qrImage(sessionId));
    }

    @PatchMapping("/{sessionId}/guest-count")
    public SessionSummary guestCount(@PathVariable long sessionId, @RequestBody GuestCountRequest request) {
        return sessions.updateGuestCount(sessionId, request.guestCount());
    }

    @PutMapping("/{sessionId}/service-charge")
    public SessionSummary serviceCharge(@PathVariable long sessionId, @RequestBody ServiceChargeRequest request) {
        return sessions.setServiceCharge(sessionId, request.enabled(), request.pin());
    }
}
