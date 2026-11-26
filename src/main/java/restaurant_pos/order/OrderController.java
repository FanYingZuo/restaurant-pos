package restaurant_pos.order;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.order.OrderDtos.DraftView;
import restaurant_pos.order.OrderDtos.ItemInput;
import restaurant_pos.order.OrderDtos.OrderItemView;
import restaurant_pos.order.OrderDtos.OrderView;
import restaurant_pos.order.OrderDtos.SessionDetail;
import restaurant_pos.order.OrderDtos.SubmitResult;
import restaurant_pos.order.SelectionValidator.Selection;
import restaurant_pos.table.TableDtos.SessionSummary;

/**
 * 訂單 API（合約 order 標籤）：用餐詳情、草稿、送出、修改、取消。Controller 只做格式轉換與驗證、呼叫 Service、回 DTO。
 */
@RestController
@RequestMapping("/api/pos")
public class OrderController {

    public record SubmitDraftRequest(String requestId, String note) {
    }

    public record ModifyItemRequest(Integer expectedVersion, Integer quantity, java.util.List<Selection> modifiers,
            String note) {
    }

    public record UpdateNoteRequest(Integer expectedVersion, String note) {
    }

    public record PinReasonRequest(String pin, String reason) {
        @Override
        public String toString() {
            return "PinReasonRequest[reason=" + reason + "]";
        }
    }

    private final SessionDetailService detail;
    private final DraftService drafts;
    private final OrderEditService edits;

    public OrderController(SessionDetailService detail, DraftService drafts, OrderEditService edits) {
        this.detail = detail;
        this.drafts = drafts;
        this.edits = edits;
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionDetail sessionDetail(@PathVariable long sessionId) {
        return detail.detail(sessionId);
    }

    // ---- 草稿 ----

    @PostMapping("/sessions/{sessionId}/draft/items")
    public ResponseEntity<DraftView> addDraftItem(@PathVariable long sessionId, @RequestBody ItemInput request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(drafts.add(sessionId, request));
    }

    @PutMapping("/sessions/{sessionId}/draft/items/{draftItemId}")
    public DraftView replaceDraftItem(@PathVariable long sessionId, @PathVariable long draftItemId,
            @RequestBody ItemInput request) {
        return drafts.replace(sessionId, draftItemId, request);
    }

    @DeleteMapping("/sessions/{sessionId}/draft/items/{draftItemId}")
    public DraftView deleteDraftItem(@PathVariable long sessionId, @PathVariable long draftItemId) {
        return drafts.delete(sessionId, draftItemId);
    }

    /** 新建立回 201；同請求編號重播回 200。 */
    @PostMapping("/sessions/{sessionId}/draft/submit")
    public ResponseEntity<SubmitResult> submit(@PathVariable long sessionId, @RequestBody SubmitDraftRequest request) {
        SubmitResult result = drafts.submit(sessionId, request.requestId(), request.note());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    // ---- 修改與取消 ----

    @PatchMapping("/order-items/{orderItemId}")
    public OrderItemView modifyItem(@PathVariable long orderItemId, @RequestBody ModifyItemRequest request) {
        return edits.modifyItem(orderItemId, request.expectedVersion(),
                new ItemInput(null, request.quantity(), request.modifiers(), request.note()));
    }

    @PostMapping("/order-items/{orderItemId}/cancel")
    public OrderItemView cancelItem(@PathVariable long orderItemId, @RequestBody PinReasonRequest request) {
        return edits.cancelItem(orderItemId, request.pin(), request.reason());
    }

    @PatchMapping("/orders/{orderId}/note")
    public OrderView updateNote(@PathVariable long orderId, @RequestBody UpdateNoteRequest request) {
        return edits.updateOrderNote(orderId, request.expectedVersion(), request.note());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderView cancelOrder(@PathVariable long orderId, @RequestBody PinReasonRequest request) {
        return edits.cancelOrder(orderId, request.pin(), request.reason());
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    public SessionSummary cancelSession(@PathVariable long sessionId, @RequestBody PinReasonRequest request) {
        return edits.cancelSession(sessionId, request.pin(), request.reason());
    }
}
