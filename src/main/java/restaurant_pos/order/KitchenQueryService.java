package restaurant_pos.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.shared.model.Station;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.table.SessionStatus;
import restaurant_pos.table.TableSessionService;
import restaurant_pos.table.TableSessionService.KitchenSessionView;

/**
 * 廚房站別清單查詢（S6 卡 6.1），供 KDS 模組使用。
 *
 * <p>清單規則：站別符合；待處理或製作中；屬於正式單（草稿不列）；用餐紀錄為使用中，或<b>今天營業日已結束</b>
 * （先付後吃）；已取消不列；依品項編號遞增。
 * 一次查詢取回品項與訂單、一次取選項、批次取桌號——固定次數，不在迴圈內逐筆查詢。
 */
@Service
public class KitchenQueryService {

    public record KitchenModifier(String name, int quantity) {
    }

    /** 廚房清單的一個品項（欄位與合約 KdsItem 一致）。 */
    public record KitchenItem(long orderItemId, long orderId, long sessionId, String tableNo, int batchNo, String name,
            int quantity, String note, List<KitchenModifier> modifiers, ItemStatus status, int version,
            Instant submittedAt, String orderNote) {
    }

    /** 品項與它所屬的站別（站別只用於決定推播主題，不屬於對外的 JSON 欄位）。 */
    public record StationItem(Station station, KitchenItem item) {
    }

    private static final List<ItemStatus> ON_BOARD = List.of(ItemStatus.PENDING, ItemStatus.IN_PROGRESS);

    private final OrderItemRepository items;
    private final OrderItemModifierRepository modifiers;
    private final TableSessionService sessions;
    private final BusinessDayService time;

    public KitchenQueryService(OrderItemRepository items, OrderItemModifierRepository modifiers,
            TableSessionService sessions, BusinessDayService time) {
        this.items = items;
        this.modifiers = modifiers;
        this.sessions = sessions;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public List<KitchenItem> list(Station station) {
        return build(items.findKitchenRows(station, ON_BOARD)).stream().map(StationItem::item).toList();
    }

    /**
     * 單一品項目前是否在廚房清單上；不在（已完成、已取消、用餐已取消、讀不到）回空。
     * 使用獨立交易：推播監聽器在「提交後」執行，原交易的資源仍綁定，不能加入。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<StationItem> findOnBoard(long orderItemId) {
        return build(items.findKitchenRow(orderItemId)).stream().findFirst();
    }

    /** 某張訂單目前在廚房清單上的品項（推播「訂單送出」用）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<StationItem> findOnBoardByOrder(long orderId) {
        List<Long> ids = items.findByOrder(orderId).stream().map(OrderItem::getId).toList();
        return ids.isEmpty() ? List.of() : build(ids.stream().flatMap(id -> items.findKitchenRow(id).stream()).toList());
    }

    private List<StationItem> build(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = rows.stream().map(r -> ((OrderItem) r[0]).getId()).toList();
        Map<Long, List<OrderItemModifier>> mods = modifiers.findByItems(itemIds).stream()
                .collect(Collectors.groupingBy(OrderItemModifier::getOrderItemId));
        Map<Long, KitchenSessionView> sessionViews = sessions.kitchenViews(
                rows.stream().map(r -> ((OrderItem) r[0]).getSessionId()).distinct().toList());
        LocalDate today = time.today();

        return rows.stream().map(r -> {
            OrderItem i = (OrderItem) r[0];
            DiningOrder o = (DiningOrder) r[1];
            KitchenSessionView s = sessionViews.get(i.getSessionId());
            if (s == null || !onBoard(s, i, today)) {
                return null;
            }
            List<KitchenModifier> kitchenMods = mods.getOrDefault(i.getId(), List.of()).stream()
                    .map(m -> new KitchenModifier(m.getName(), m.getQuantity())).toList();
            return new StationItem(i.getStation(), new KitchenItem(i.getId(), o.getId(), i.getSessionId(), s.tableNo(), o.getBatchNo(), i.getName(),
                    i.getQuantity(), i.getNote(), kitchenMods, i.getStatus(), i.getVersion(), o.getSubmittedAt(),
                    o.getNote()));
        }).filter(java.util.Objects::nonNull).toList();
    }

    /** 用餐紀錄使用中，或今天營業日已結束；且品項待處理或製作中。 */
    private static boolean onBoard(KitchenSessionView s, OrderItem item, LocalDate today) {
        boolean statusOk = item.getStatus() == ItemStatus.PENDING || item.getStatus() == ItemStatus.IN_PROGRESS;
        boolean sessionOk = s.status() == SessionStatus.OPEN
                || (s.status() == SessionStatus.CLOSED && today.equals(s.businessDate()));
        return statusOk && sessionOk;
    }
}
