package restaurant_pos.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.order.OrderDtos.DraftItemView;
import restaurant_pos.order.OrderDtos.DraftView;
import restaurant_pos.order.OrderDtos.OrderItemView;
import restaurant_pos.order.OrderDtos.OrderView;
import restaurant_pos.order.OrderDtos.SessionDetail;
import restaurant_pos.table.TableDtos.SessionSummary;
import restaurant_pos.table.TableSessionService;

/**
 * 用餐詳情（S5 卡 5.9）：用餐紀錄、草稿、所有正式單（含已取消）、小計。
 * 小計只算正式單未取消品項，<b>不含草稿與服務費</b>。任何狀態的用餐紀錄都可查詢。
 */
@Service
public class SessionDetailService {

    private final TableSessionService sessions;
    private final DiningOrderRepository orders;
    private final OrderItemRepository items;
    private final OrderItemSupport support;

    public SessionDetailService(TableSessionService sessions, DiningOrderRepository orders, OrderItemRepository items,
            OrderItemSupport support) {
        this.sessions = sessions;
        this.orders = orders;
        this.items = items;
        this.support = support;
    }

    @Transactional(readOnly = true)
    public SessionDetail detail(long sessionId) {
        SessionSummary summary = sessions.summary(sessionId); // 不存在回 SESSION_NOT_FOUND

        DraftView draft = orders.findDraft(sessionId).map(d -> {
            List<OrderItem> draftItems = items.findByOrder(d.getId());
            Map<Long, List<OrderItemModifier>> mods = support.modifiersOf(draftItems.stream().map(OrderItem::getId).toList());
            List<DraftItemView> views = draftItems.stream().map(i -> support.draftView(i, mods)).toList();
            return new DraftView(d.getId(), views);
        }).orElse(null);

        List<DiningOrder> formal = orders.findFormal(sessionId);
        Map<Long, List<OrderItem>> itemsByOrder = formal.isEmpty() ? Map.of()
                : items.findByOrders(formal.stream().map(DiningOrder::getId).toList()).stream()
                        .collect(Collectors.groupingBy(OrderItem::getOrderId));
        Map<Long, List<OrderItemModifier>> mods = support.modifiersOf(
                itemsByOrder.values().stream().flatMap(List::stream).map(OrderItem::getId).toList());

        BigDecimal subtotal = BigDecimal.ZERO;
        List<OrderView> views = new ArrayList<>();
        for (DiningOrder o : formal) {
            List<OrderItem> orderItems = itemsByOrder.getOrDefault(o.getId(), List.of());
            List<OrderItemView> itemViews = new ArrayList<>();
            for (OrderItem i : orderItems) {
                itemViews.add(OrderItemView.of(i, mods.getOrDefault(i.getId(), List.of())));
                if (i.getStatus() != ItemStatus.CANCELLED) {
                    subtotal = subtotal.add(i.lineTotal());
                }
            }
            views.add(new OrderView(o.getId(), o.getBatchNo(), o.getStatus(), o.getSource(), o.getNote(),
                    o.getVersion(), o.getSubmittedAt(), itemViews));
        }
        return new SessionDetail(summary, draft, views, subtotal.longValue());
    }
}
