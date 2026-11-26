package restaurant_pos.order;

import java.time.Instant;
import java.util.List;
import restaurant_pos.order.SelectionValidator.Selection;
import restaurant_pos.shared.model.Station;
import restaurant_pos.table.TableDtos.SessionSummary;

/** 訂單的請求與回應物件（欄位與 docs/contract/openapi.yaml 一致）。金額一律為整數元。 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** 加入或取代品項的輸入（草稿與修改共用）。 */
    public record ItemInput(Long menuItemId, Integer quantity, List<Selection> modifiers, String note) {
    }

    public record ItemModifierView(long modifierId, String name, long priceDelta, int quantity) {
        static ItemModifierView of(OrderItemModifier m) {
            return new ItemModifierView(m.getModifierId(), m.getName(), m.getPriceDelta().longValue(), m.getQuantity());
        }
    }

    public record DraftItemView(long id, long menuItemId, String name, long basePrice, long unitPrice, int quantity,
            long lineTotal, Station station, List<ItemModifierView> modifiers, String note) {
        static DraftItemView of(OrderItem i, List<OrderItemModifier> mods) {
            return new DraftItemView(i.getId(), i.getMenuItemId(), i.getName(), i.getBasePrice().longValue(),
                    i.getUnitPrice().longValue(), i.getQuantity(), i.lineTotal().longValue(), i.getStation(),
                    mods.stream().map(ItemModifierView::of).toList(), i.getNote());
        }
    }

    public record DraftView(long orderId, List<DraftItemView> items) {
    }

    public record OrderItemView(long id, long orderId, long menuItemId, String name, long basePrice, long unitPrice,
            int quantity, long lineTotal, Station station, List<ItemModifierView> modifiers, String note,
            ItemStatus status, int version, String cancelReason, Instant cancelledAt) {
        static OrderItemView of(OrderItem i, List<OrderItemModifier> mods) {
            return new OrderItemView(i.getId(), i.getOrderId(), i.getMenuItemId(), i.getName(),
                    i.getBasePrice().longValue(), i.getUnitPrice().longValue(), i.getQuantity(),
                    i.lineTotal().longValue(), i.getStation(), mods.stream().map(ItemModifierView::of).toList(),
                    i.getNote(), i.getStatus(), i.getVersion(), i.getCancelReason(), i.getCancelledAt());
        }
    }

    public record OrderView(long id, int batchNo, OrderStatus status, OrderSource source, String note, int version,
            Instant submittedAt, List<OrderItemView> items) {
    }

    public record SessionDetail(SessionSummary session, DraftView draft, List<OrderView> orders, long subtotal) {
    }

    public record SubmitResult(long orderId, int batchNo, int itemCount, boolean replayed) {
    }
}
