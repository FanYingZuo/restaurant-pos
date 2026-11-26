package restaurant_pos.order;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import restaurant_pos.menu.ItemSnapshot;
import restaurant_pos.order.OrderDtos.DraftItemView;
import restaurant_pos.order.OrderDtos.OrderItemView;
import restaurant_pos.order.SelectionValidator.ModifierLine;
import restaurant_pos.order.SelectionValidator.Selection;
import restaurant_pos.order.SelectionValidator.Validated;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 訂單品項的寫入與轉換共用邏輯（package-private 使用）：建立與取代快照、轉成回應、產生歷史摘要。
 * 本類別不開交易也不鎖，呼叫者必須已依鎖順序鎖好（§4.6）。
 */
@Component
public class OrderItemSupport {

    private final OrderItemRepository items;
    private final OrderItemModifierRepository modifiers;
    private final BusinessDayService time;

    public OrderItemSupport(OrderItemRepository items, OrderItemModifierRepository modifiers, BusinessDayService time) {
        this.items = items;
        this.modifiers = modifiers;
        this.time = time;
    }

    /** 以驗證通過的結果建立品項與選項快照。 */
    OrderItem create(Long orderId, Long sessionId, ItemSnapshot snapshot, Validated v, String note) {
        OrderItem item = items.saveAndFlush(new OrderItem(orderId, sessionId, snapshot.id(), snapshot.name(),
                snapshot.price(), v.unitPrice(), v.quantity(), snapshot.station(), note, time.now()));
        saveModifiers(item.getId(), v.modifiers());
        return item;
    }

    /** 整筆取代內容與選項快照（價格以最新菜單為準）。 */
    void replace(OrderItem item, ItemSnapshot snapshot, Validated v, String note, boolean bumpVersion) {
        item.replaceContent(snapshot.id(), snapshot.name(), snapshot.price(), v.unitPrice(), v.quantity(),
                snapshot.station(), note, bumpVersion);
        items.saveAndFlush(item);
        modifiers.deleteByItems(List.of(item.getId()));
        saveModifiers(item.getId(), v.modifiers());
    }

    void deleteWithModifiers(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }
        modifiers.deleteByItems(itemIds);
        items.deleteAllById(itemIds);
        items.flush();
    }

    private void saveModifiers(Long itemId, List<ModifierLine> lines) {
        int sort = 0;
        for (ModifierLine l : lines) {
            modifiers.save(new OrderItemModifier(itemId, l.modifierId(), l.name(), l.priceDelta(), l.quantity(), sort++));
        }
        modifiers.flush();
    }

    /** 品項目前的選項（以選項編號與數量表示，供送出時重新驗證）。 */
    List<Selection> selectionsOf(Long itemId) {
        return modifiers.findByItems(List.of(itemId)).stream()
                .map(m -> new Selection(m.getModifierId(), m.getQuantity())).toList();
    }

    Map<Long, List<OrderItemModifier>> modifiersOf(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return new HashMap<>();
        }
        return modifiers.findByItems(itemIds).stream().collect(Collectors.groupingBy(OrderItemModifier::getOrderItemId));
    }

    OrderItemView view(OrderItem item) {
        return OrderItemView.of(item, modifiersOf(List.of(item.getId())).getOrDefault(item.getId(), List.of()));
    }

    DraftItemView draftView(OrderItem item, Map<Long, List<OrderItemModifier>> mods) {
        return DraftItemView.of(item, mods.getOrDefault(item.getId(), List.of()));
    }

    /** 歷史用的文字摘要：「名稱 ×數量（選項…）備註」。不含任何機密值。 */
    String summary(OrderItem item, List<OrderItemModifier> mods) {
        StringBuilder sb = new StringBuilder(item.getName()).append(" x").append(item.getQuantity());
        if (!mods.isEmpty()) {
            sb.append(" [").append(mods.stream().map(m -> m.getName() + "x" + m.getQuantity())
                    .collect(Collectors.joining(","))).append(']');
        }
        if (item.getNote() != null && !item.getNote().isBlank()) {
            sb.append(" 備註:").append(item.getNote());
        }
        sb.append(" 單價").append(item.getUnitPrice().longValue());
        return sb.toString();
    }

    Instant now() {
        return time.now();
    }
}
