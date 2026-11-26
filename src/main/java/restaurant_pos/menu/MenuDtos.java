package restaurant_pos.menu;

import java.time.LocalDate;
import java.util.List;
import restaurant_pos.shared.model.Station;

/** 菜單回應物件（欄位與 docs/contract/openapi.yaml 一致）。價格一律為整數元。 */
public final class MenuDtos {

    private MenuDtos() {
    }

    public record ModifierDto(long id, String name, long priceDelta, int displayOrder) {
        static ModifierDto of(Modifier m) {
            return new ModifierDto(m.getId(), m.getName(), m.getPriceDelta().longValue(), m.getDisplayOrder());
        }
    }

    public record ModifierGroupDto(long id, String name, int minSelect, int maxSelect, boolean allowDuplicate,
            int displayOrder, List<ModifierDto> modifiers) {
        static ModifierGroupDto of(ModifierGroup g, List<Modifier> modifiers) {
            return new ModifierGroupDto(g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(),
                    g.isAllowDuplicate(), g.getDisplayOrder(), modifiers.stream().map(ModifierDto::of).toList());
        }
    }

    public record MenuItemDto(long id, long categoryId, String name, long price, Station station, boolean active,
            LocalDate soldOutBusinessDate, boolean soldOut, boolean sellable, String imageKey, int displayOrder,
            List<ModifierGroupDto> modifierGroups) {
    }

    public record MenuCategoryDto(long id, String name, int displayOrder, boolean active, List<MenuItemDto> items) {
    }

    public record MenuTree(List<MenuCategoryDto> categories) {
    }

    // ---- 顧客菜單（只含上架分類中的可售品項） ----

    public record CustomerMenuItem(long id, String name, long price, String imageKey,
            List<ModifierGroupDto> modifierGroups) {
    }

    public record CustomerCategory(long id, String name, List<CustomerMenuItem> items) {
    }

    public record CustomerMenu(List<CustomerCategory> categories) {
    }
}
