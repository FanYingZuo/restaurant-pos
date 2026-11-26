package restaurant_pos.menu;

import java.math.BigDecimal;
import java.util.List;
import restaurant_pos.shared.model.Station;

/**
 * 提供給訂單模組的品項快照（S3「提供給訂單」）：只含未刪除的群組與選項。
 * 訂單建立時存名稱、價格、站別、選項快照，之後菜單改價不影響既有訂單。
 */
public record ItemSnapshot(long id, String name, BigDecimal price, Station station, List<GroupSnapshot> groups) {

    public record GroupSnapshot(long id, String name, int minSelect, int maxSelect, boolean allowDuplicate,
            int displayOrder, List<ModifierSnapshot> modifiers) {
    }

    public record ModifierSnapshot(long id, String name, BigDecimal priceDelta, int displayOrder) {
    }
}
