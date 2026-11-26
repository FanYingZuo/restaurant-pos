package restaurant_pos.order;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.menu.ItemSnapshot;
import restaurant_pos.menu.MenuQueryService;
import restaurant_pos.order.OrderDtos.DraftItemView;
import restaurant_pos.order.OrderDtos.DraftView;
import restaurant_pos.order.OrderDtos.ItemInput;
import restaurant_pos.order.OrderDtos.SubmitResult;
import restaurant_pos.order.SelectionValidator.Validated;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.table.SessionView;
import restaurant_pos.table.TableSessionService;

/**
 * 草稿與送出（S5 卡 5.3、5.4）。
 * <ul>
 *   <li>每個用餐紀錄最多一張草稿；新增、修改（整筆取代、價格以最新菜單為準）、刪除（實體刪除）；
 *       不寫歷史、不發事件；變空時保留空草稿。</li>
 *   <li>送出：驗證請求編號 → 鎖用餐紀錄 → 冪等重播 → 結帳中拒絕 → 空草稿拒絕 → 每項<b>重新</b>檢查可售與選項
 *       （任一失敗整張失敗）→ 轉正式單 → 寫歷史 → 發佈訂單送出 → 回批次號、品項數、是否重播。</li>
 * </ul>
 * 交易內第一個動作就是鎖用餐紀錄（鎖順序：用餐紀錄 → 訂單 → 訂單品項，§4.6）。
 */
@Service
public class DraftService {

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final TableSessionService sessions;
    private final MenuQueryService menu;
    private final DiningOrderRepository orders;
    private final OrderItemRepository items;
    private final OrderItemSupport support;
    private final OrderHistoryRepository histories;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public DraftService(TableSessionService sessions, MenuQueryService menu, DiningOrderRepository orders,
            OrderItemRepository items, OrderItemSupport support, OrderHistoryRepository histories,
            ApplicationEventPublisher events, TransactionTemplate tx) {
        this.sessions = sessions;
        this.menu = menu;
        this.orders = orders;
        this.items = items;
        this.support = support;
        this.histories = histories;
        this.events = events;
        this.tx = tx;
    }

    // ---- 草稿 ----

    public DraftView add(long sessionId, ItemInput input) {
        String note = checkInput(input);
        return tx.execute(status -> {
            sessions.lockEditable(sessionId);
            ItemSnapshot snapshot = menu.sellableItem(input.menuItemId());
            Validated v = SelectionValidator.validate(snapshot, input.quantity(), input.modifiers());
            DiningOrder draft = orders.lockDraft(sessionId)
                    .orElseGet(() -> orders.saveAndFlush(DiningOrder.newDraft(sessionId, support.now())));
            support.create(draft.getId(), sessionId, snapshot, v, note);
            return draftView(draft);
        });
    }

    public DraftView replace(long sessionId, long draftItemId, ItemInput input) {
        String note = checkInput(input);
        return tx.execute(status -> {
            sessions.lockEditable(sessionId);
            DiningOrder draft = orders.lockDraft(sessionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.DRAFT_ITEM_NOT_FOUND));
            OrderItem item = draftItem(draft, draftItemId);
            ItemSnapshot snapshot = menu.sellableItem(input.menuItemId());
            Validated v = SelectionValidator.validate(snapshot, input.quantity(), input.modifiers());
            support.replace(item, snapshot, v, note, false);
            return draftView(draft);
        });
    }

    public DraftView delete(long sessionId, long draftItemId) {
        return tx.execute(status -> {
            sessions.lockEditable(sessionId);
            DiningOrder draft = orders.lockDraft(sessionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.DRAFT_ITEM_NOT_FOUND));
            OrderItem item = draftItem(draft, draftItemId);
            support.deleteWithModifiers(List.of(item.getId()));
            return draftView(draft);
        });
    }

    private OrderItem draftItem(DiningOrder draft, long draftItemId) {
        return items.findById(draftItemId).filter(i -> i.getOrderId().equals(draft.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.DRAFT_ITEM_NOT_FOUND));
    }

    private DraftView draftView(DiningOrder draft) {
        List<OrderItem> draftItems = items.findByOrder(draft.getId());
        Map<Long, List<OrderItemModifier>> mods = support.modifiersOf(draftItems.stream().map(OrderItem::getId).toList());
        List<DraftItemView> views = draftItems.stream().map(i -> support.draftView(i, mods)).toList();
        return new DraftView(draft.getId(), views);
    }

    /** 先格式後業務：品項編號、數量、選項數量、備註長度。回傳清理後的備註。 */
    private static String checkInput(ItemInput input) {
        if (input == null || input.menuItemId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        SelectionValidator.checkQuantities(input.quantity(), input.modifiers());
        return cleanNote(input.note());
    }

    static String cleanNote(String note) {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    // ---- 送出 ----

    public SubmitResult submit(long sessionId, String requestId, String orderNote) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String note = cleanNote(orderNote);
        return tx.execute(status -> doSubmit(sessionId, requestId, note));
    }

    private SubmitResult doSubmit(long sessionId, String requestId, String note) {
        SessionView view = sessions.lockById(sessionId);

        // 冪等：同請求編號回原結果，不論內容；放在狀態檢查之前
        Optional<DiningOrder> replay = orders.findByRequest(sessionId, requestId);
        if (replay.isPresent()) {
            DiningOrder o = replay.get();
            return new SubmitResult(o.getId(), o.getBatchNo(), items.findByOrder(o.getId()).size(), true);
        }

        view.requireEditable();

        DiningOrder draft = orders.lockDraft(sessionId).orElse(null);
        List<OrderItem> draftItems = draft == null ? List.of() : items.findByOrder(draft.getId());
        if (draftItems.isEmpty()) {
            throw new ApiException(ErrorCode.EMPTY_DRAFT);
        }

        // 每項重新檢查可售與選項（任一失敗整張失敗）；價格以最新菜單為準
        for (OrderItem item : draftItems) {
            ItemSnapshot snapshot = menu.sellableItem(item.getMenuItemId());
            Validated v = SelectionValidator.validate(snapshot, item.getQuantity(), support.selectionsOf(item.getId()));
            support.replace(item, snapshot, v, item.getNote(), false);
        }

        int batchNo = orders.maxBatchNo(sessionId) + 1;
        draft.submit(batchNo, requestId, note, support.now());
        orders.saveAndFlush(draft);

        Map<Long, List<OrderItemModifier>> mods = support.modifiersOf(draftItems.stream().map(OrderItem::getId).toList());
        String summary = draftItems.stream().map(i -> support.summary(i, mods.getOrDefault(i.getId(), List.of())))
                .collect(Collectors.joining("；"));
        histories.save(new OrderHistory(sessionId, draft.getId(), null, "SUBMITTED", null, "批次 " + batchNo + "：" + summary,
                null, null, support.now()));
        events.publishEvent(new OrderSubmittedEvent(draft.getId(), sessionId, batchNo));
        return new SubmitResult(draft.getId(), batchNo, draftItems.size(), false);
    }
}
