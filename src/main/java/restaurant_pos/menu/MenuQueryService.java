package restaurant_pos.menu;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import restaurant_pos.menu.MenuDtos.CustomerCategory;
import restaurant_pos.menu.MenuDtos.CustomerMenu;
import restaurant_pos.menu.MenuDtos.CustomerMenuItem;
import restaurant_pos.menu.MenuDtos.MenuCategoryDto;
import restaurant_pos.menu.MenuDtos.MenuItemDto;
import restaurant_pos.menu.MenuDtos.MenuTree;
import restaurant_pos.menu.MenuDtos.ModifierGroupDto;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 菜單查詢與「可售判斷」（S3）。可售＝品項上架未刪除、分類上架未刪除、售完日不是今天、
 * 每個群組的選項數 ≥ 最少可選；全部在查詢時計算，不用排程。
 *
 * <p>提供給訂單模組：{@link #sellableItem(long)}（不存在 → 找不到；不可售 → 不可售）
 * 與 {@link #editableItem(long)}（只要求未刪除，供修改已送出品項用）。
 */
@Service
public class MenuQueryService {

    private final MenuCategoryRepository categories;
    private final MenuItemRepository items;
    private final ModifierGroupRepository groups;
    private final ModifierRepository modifiers;
    private final BusinessDayService time;

    public MenuQueryService(MenuCategoryRepository categories, MenuItemRepository items, ModifierGroupRepository groups,
            ModifierRepository modifiers, BusinessDayService time) {
        this.categories = categories;
        this.items = items;
        this.groups = groups;
        this.modifiers = modifiers;
        this.time = time;
    }

    /** 查詢某批品項要用的群組與選項（只含未刪除）。 */
    private record Loaded(Map<Long, List<ModifierGroup>> groupsByItem, Map<Long, List<Modifier>> modifiersByGroup) {
        List<ModifierGroup> groupsOf(long itemId) {
            return groupsByItem.getOrDefault(itemId, List.of());
        }

        List<Modifier> modifiersOf(long groupId) {
            return modifiersByGroup.getOrDefault(groupId, List.of());
        }
    }

    private Loaded load(List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return new Loaded(Map.of(), Map.of());
        }
        List<ModifierGroup> gs = groups.findLiveByItemIds(itemIds);
        Map<Long, List<ModifierGroup>> byItem = gs.stream()
                .collect(Collectors.groupingBy(ModifierGroup::getMenuItemId, LinkedHashMap::new, Collectors.toList()));
        List<Long> groupIds = gs.stream().map(ModifierGroup::getId).toList();
        Map<Long, List<Modifier>> byGroup = groupIds.isEmpty() ? Map.of() : modifiers.findLiveByGroupIds(groupIds).stream()
                .collect(Collectors.groupingBy(Modifier::getModifierGroupId, LinkedHashMap::new, Collectors.toList()));
        return new Loaded(byItem, byGroup);
    }

    /** 可售的四個條件之一：每個群組的選項數 ≥ 最少可選。 */
    private static boolean groupsSatisfiable(List<ModifierGroup> gs, Loaded loaded) {
        return gs.stream().allMatch(g -> loaded.modifiersOf(g.getId()).size() >= g.getMinSelect());
    }

    private static boolean sellable(MenuItem item, MenuCategory category, List<ModifierGroup> gs, Loaded loaded,
            LocalDate today) {
        return item.isActive() && !item.isDeleted() && category.isActive() && !category.isDeleted()
                && !today.equals(item.getSoldOutDate()) && groupsSatisfiable(gs, loaded);
    }

    private MenuItemDto toDto(MenuItem item, MenuCategory category, Loaded loaded, LocalDate today) {
        List<ModifierGroup> gs = loaded.groupsOf(item.getId());
        return new MenuItemDto(item.getId(), item.getCategoryId(), item.getName(), item.getPrice().longValue(),
                item.getStation(), item.isActive(), item.getSoldOutDate(), today.equals(item.getSoldOutDate()),
                sellable(item, category, gs, loaded, today), item.getImageKey(), item.getDisplayOrder(),
                gs.stream().map(g -> ModifierGroupDto.of(g, loaded.modifiersOf(g.getId()))).toList());
    }

    /** 完整菜單樹：分類 → 品項 → 選項群組 → 選項，皆依顯示順序，含下架項目。 */
    @Transactional(readOnly = true)
    public MenuTree tree() {
        LocalDate today = time.today();
        List<MenuCategory> cats = categories.findAllLive();
        Map<Long, MenuCategory> catById = cats.stream().collect(Collectors.toMap(MenuCategory::getId, Function.identity()));
        List<MenuItem> its = items.findAllLive().stream().filter(i -> catById.containsKey(i.getCategoryId())).toList();
        Loaded loaded = load(its.stream().map(MenuItem::getId).toList());
        Map<Long, List<MenuItem>> itemsByCat = its.stream()
                .collect(Collectors.groupingBy(MenuItem::getCategoryId, LinkedHashMap::new, Collectors.toList()));
        List<MenuCategoryDto> result = new ArrayList<>();
        for (MenuCategory c : cats) {
            List<MenuItemDto> dtos = itemsByCat.getOrDefault(c.getId(), List.of()).stream()
                    .map(i -> toDto(i, c, loaded, today)).toList();
            result.add(new MenuCategoryDto(c.getId(), c.getName(), c.getDisplayOrder(), c.isActive(), dtos));
        }
        return new MenuTree(result);
    }

    @Transactional(readOnly = true)
    public MenuCategoryDto category(long categoryId) {
        MenuCategory c = categories.findLiveById(categoryId)
                .orElseThrow(() -> new ApiException(ErrorCode.MENU_CATEGORY_NOT_FOUND));
        LocalDate today = time.today();
        List<MenuItem> its = items.findLiveByCategory(categoryId);
        Loaded loaded = load(its.stream().map(MenuItem::getId).toList());
        return new MenuCategoryDto(c.getId(), c.getName(), c.getDisplayOrder(), c.isActive(),
                its.stream().map(i -> toDto(i, c, loaded, today)).toList());
    }

    @Transactional(readOnly = true)
    public MenuItemDto item(long itemId) {
        MenuItem item = items.findLiveById(itemId).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
        MenuCategory category = categories.findById(item.getCategoryId())
                .orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
        return toDto(item, category, load(List.of(itemId)), time.today());
    }

    @Transactional(readOnly = true)
    public ModifierGroupDto group(long groupId) {
        ModifierGroup g = groups.findLiveById(groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.MODIFIER_GROUP_NOT_FOUND));
        return ModifierGroupDto.of(g, modifiers.findLiveByGroup(groupId));
    }

    /** 顧客菜單：只含上架分類中的可售品項；空分類不出現；加價為 0 的選項也回傳。 */
    @Transactional(readOnly = true)
    public CustomerMenu customerMenu() {
        LocalDate today = time.today();
        List<MenuCategory> cats = categories.findAllLive().stream().filter(MenuCategory::isActive).toList();
        Map<Long, MenuCategory> catById = cats.stream().collect(Collectors.toMap(MenuCategory::getId, Function.identity()));
        List<MenuItem> its = items.findAllLive().stream().filter(i -> catById.containsKey(i.getCategoryId())).toList();
        Loaded loaded = load(its.stream().map(MenuItem::getId).toList());
        Map<Long, List<MenuItem>> itemsByCat = its.stream()
                .collect(Collectors.groupingBy(MenuItem::getCategoryId, LinkedHashMap::new, Collectors.toList()));
        List<CustomerCategory> result = new ArrayList<>();
        for (MenuCategory c : cats) {
            List<CustomerMenuItem> sellable = itemsByCat.getOrDefault(c.getId(), List.of()).stream()
                    .filter(i -> sellable(i, c, loaded.groupsOf(i.getId()), loaded, today))
                    .map(i -> new CustomerMenuItem(i.getId(), i.getName(), i.getPrice().longValue(), i.getImageKey(),
                            loaded.groupsOf(i.getId()).stream()
                                    .map(g -> ModifierGroupDto.of(g, loaded.modifiersOf(g.getId()))).toList()))
                    .toList();
            if (!sellable.isEmpty()) {
                result.add(new CustomerCategory(c.getId(), c.getName(), sellable));
            }
        }
        return new CustomerMenu(result);
    }

    // ---- 提供給訂單模組 ----

    /** 可售品項快照：不存在（含已刪除）→ MENU_ITEM_NOT_FOUND；存在但不可售 → MENU_ITEM_NOT_SELLABLE。 */
    @Transactional(readOnly = true)
    public ItemSnapshot sellableItem(long itemId) {
        MenuItem item = items.findLiveById(itemId).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
        MenuCategory category = categories.findLiveById(item.getCategoryId()).orElse(null);
        Loaded loaded = load(List.of(itemId));
        if (category == null || !sellable(item, category, loaded.groupsOf(itemId), loaded, time.today())) {
            throw new ApiException(ErrorCode.MENU_ITEM_NOT_SELLABLE);
        }
        return snapshot(item, loaded);
    }

    /** 可編輯品項：只要求未刪除（修改已送出品項時，下架或售完的品項仍可編輯）。 */
    @Transactional(readOnly = true)
    public ItemSnapshot editableItem(long itemId) {
        MenuItem item = items.findLiveById(itemId).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
        return snapshot(item, load(List.of(itemId)));
    }

    private static ItemSnapshot snapshot(MenuItem item, Loaded loaded) {
        List<ItemSnapshot.GroupSnapshot> gs = loaded.groupsOf(item.getId()).stream()
                .map(g -> new ItemSnapshot.GroupSnapshot(g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(),
                        g.isAllowDuplicate(), g.getDisplayOrder(),
                        loaded.modifiersOf(g.getId()).stream()
                                .map(m -> new ItemSnapshot.ModifierSnapshot(m.getId(), m.getName(), m.getPriceDelta(),
                                        m.getDisplayOrder())).toList()))
                .toList();
        return new ItemSnapshot(item.getId(), item.getName(), item.getPrice(), item.getStation(), gs);
    }
}
