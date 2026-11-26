package restaurant_pos.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import restaurant_pos.menu.ItemSnapshot;
import restaurant_pos.menu.ItemSnapshot.GroupSnapshot;
import restaurant_pos.menu.ItemSnapshot.ModifierSnapshot;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;

/**
 * 選項驗證與快照（純函式，S5）。依序：
 * <ol>
 *   <li>數量 1～99（品項與每個選項）→ {@code INVALID_QUANTITY}</li>
 *   <li>同一選項不重複列出 → {@code INVALID_MODIFIER_SELECTION}</li>
 *   <li>選項屬於品項的群組 → {@code INVALID_MODIFIER_SELECTION}</li>
 *   <li>每群「不同選項數」在最少與最多之間 → {@code MODIFIER_GROUP_SELECTION_OUT_OF_RANGE}；
 *       不允許重複的群組數量不大於 1 → {@code MODIFIER_DUPLICATE_NOT_ALLOWED}</li>
 *   <li>依群組順序（再依選項順序）產生快照</li>
 * </ol>
 */
public final class SelectionValidator {

    private SelectionValidator() {
    }

    /** 顧客或店員選的一個選項。 */
    public record Selection(Long modifierId, Integer quantity) {
    }

    /** 快照行：名稱、加價、數量（建立訂單時存下，之後菜單改價不影響）。 */
    public record ModifierLine(long modifierId, String name, BigDecimal priceDelta, int quantity) {
    }

    /** 驗證通過的結果。 */
    public record Validated(int quantity, List<ModifierLine> modifiers, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** 第 1 步（數量）：不需要品項快照，所以可以在鎖定與查菜單之前先檢查（先格式後業務，§4.4）。 */
    public static void checkQuantities(Integer quantity, List<Selection> selections) {
        if (quantity == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        requireQuantity(quantity);
        for (Selection s : selections == null ? List.<Selection>of() : selections) {
            if (s == null || s.modifierId() == null || s.quantity() == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR);
            }
            requireQuantity(s.quantity());
        }
    }

    public static Validated validate(ItemSnapshot item, Integer quantity, List<Selection> selections) {
        List<Selection> chosen = selections == null ? List.of() : selections;

        // 1 數量
        checkQuantities(quantity, chosen);
        // 2 同一選項不重複
        Set<Long> seen = new HashSet<>();
        for (Selection s : chosen) {
            if (!seen.add(s.modifierId())) {
                throw new ApiException(ErrorCode.INVALID_MODIFIER_SELECTION);
            }
        }

        // 3 選項屬於品項的群組
        Map<Long, GroupSnapshot> groupOf = new HashMap<>();
        Map<Long, ModifierSnapshot> modifierOf = new HashMap<>();
        for (GroupSnapshot g : item.groups()) {
            for (ModifierSnapshot m : g.modifiers()) {
                groupOf.put(m.id(), g);
                modifierOf.put(m.id(), m);
            }
        }
        for (Selection s : chosen) {
            if (!modifierOf.containsKey(s.modifierId())) {
                throw new ApiException(ErrorCode.INVALID_MODIFIER_SELECTION);
            }
        }

        // 4 每群的不同選項數與重複規則（依群組順序逐群檢查）
        Map<Long, Integer> quantityOf = new LinkedHashMap<>();
        chosen.forEach(s -> quantityOf.put(s.modifierId(), s.quantity()));
        List<GroupSnapshot> groups = item.groups().stream()
                .sorted(Comparator.comparingInt(GroupSnapshot::displayOrder).thenComparingLong(GroupSnapshot::id)).toList();
        for (GroupSnapshot g : groups) {
            List<ModifierSnapshot> picked = g.modifiers().stream().filter(m -> quantityOf.containsKey(m.id())).toList();
            if (picked.size() < g.minSelect() || picked.size() > g.maxSelect()) {
                throw new ApiException(ErrorCode.MODIFIER_GROUP_SELECTION_OUT_OF_RANGE);
            }
            if (!g.allowDuplicate() && picked.stream().anyMatch(m -> quantityOf.get(m.id()) > 1)) {
                throw new ApiException(ErrorCode.MODIFIER_DUPLICATE_NOT_ALLOWED);
            }
        }

        // 5 快照依群組順序（再依選項順序）
        List<ModifierLine> lines = new ArrayList<>();
        for (GroupSnapshot g : groups) {
            g.modifiers().stream()
                    .sorted(Comparator.comparingInt(ModifierSnapshot::displayOrder).thenComparingLong(ModifierSnapshot::id))
                    .filter(m -> quantityOf.containsKey(m.id()))
                    .forEach(m -> lines.add(new ModifierLine(m.id(), m.name(), m.priceDelta(), quantityOf.get(m.id()))));
        }
        BigDecimal unit = OrderRules.unitPrice(item.price(), lines.stream()
                .map(l -> new OrderRules.ModifierPrice(l.priceDelta(), l.quantity())).toList());
        return new Validated(quantity, List.copyOf(lines), unit, OrderRules.lineTotal(unit, quantity));
    }

    private static void requireQuantity(int quantity) {
        if (quantity < 1 || quantity > 99) {
            throw new ApiException(ErrorCode.INVALID_QUANTITY);
        }
    }
}
