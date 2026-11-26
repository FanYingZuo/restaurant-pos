package restaurant_pos.order;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 訂單規則（純函式，S5）：訂單狀態重算、品項狀態轉換、單價與小計。沒有任何外部依賴。
 */
public final class OrderRules {

    private OrderRules() {
    }

    /**
     * 訂單狀態重算（只看未取消品項，依序）：
     * 沒有 → 已取消；全部完成 → 已全部上餐；至少一項完成 → 部分上餐；至少一項製作中 → 製作中；其他 → 已送出。
     */
    public static OrderStatus recalculate(Collection<ItemStatus> itemStatuses) {
        List<ItemStatus> live = itemStatuses.stream().filter(s -> s != ItemStatus.CANCELLED).toList();
        if (live.isEmpty()) {
            return OrderStatus.CANCELLED;
        }
        if (live.stream().allMatch(s -> s == ItemStatus.DONE)) {
            return OrderStatus.SERVED;
        }
        if (live.stream().anyMatch(s -> s == ItemStatus.DONE)) {
            return OrderStatus.PARTIALLY_SERVED;
        }
        if (live.stream().anyMatch(s -> s == ItemStatus.IN_PROGRESS)) {
            return OrderStatus.IN_PROGRESS;
        }
        return OrderStatus.SUBMITTED;
    }

    /**
     * 品項狀態轉換：待處理 → 製作中、待處理 → 已完成、製作中 → 已完成、待處理或製作中 → 已取消；
     * 已完成與已取消是終點。
     */
    public static boolean isLegalTransition(ItemStatus from, ItemStatus to) {
        return switch (from) {
            case PENDING -> to == ItemStatus.IN_PROGRESS || to == ItemStatus.DONE || to == ItemStatus.CANCELLED;
            case IN_PROGRESS -> to == ItemStatus.DONE || to == ItemStatus.CANCELLED;
            case DONE, CANCELLED -> false;
        };
    }

    /** 選項加價行：加價與數量。 */
    public record ModifierPrice(BigDecimal priceDelta, int quantity) {
    }

    /** 單價＝基本價＋Σ（加價×數量）。 */
    public static BigDecimal unitPrice(BigDecimal basePrice, Collection<ModifierPrice> modifiers) {
        BigDecimal total = basePrice;
        for (ModifierPrice m : modifiers) {
            total = total.add(m.priceDelta().multiply(BigDecimal.valueOf(m.quantity())));
        }
        return total;
    }

    /** 小計＝單價×數量。 */
    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
