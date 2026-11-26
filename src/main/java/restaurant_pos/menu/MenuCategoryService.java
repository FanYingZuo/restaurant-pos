package restaurant_pos.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.menu.MenuDtos.MenuCategoryDto;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 分類管理（S3）：名稱 1～50 字、顯示順序 0～9999、上下架；單層；軟刪除。
 * 分類下仍有上架品項不可下架；分類下仍有品項不可刪。
 * 交易內第一個動作就是鎖（REPEATABLE READ，§4.6）。
 */
@Service
public class MenuCategoryService {

    private final MenuCategoryRepository categories;
    private final MenuItemRepository items;
    private final MenuQueryService query;
    private final BusinessDayService time;
    private final TransactionTemplate tx;

    public MenuCategoryService(MenuCategoryRepository categories, MenuItemRepository items, MenuQueryService query,
            BusinessDayService time, TransactionTemplate tx) {
        this.categories = categories;
        this.items = items;
        this.query = query;
        this.time = time;
        this.tx = tx;
    }

    public MenuCategoryDto create(String name, Integer displayOrder, Boolean active) {
        String cleanName = MenuRules.name(name, 50);
        Integer explicitOrder = displayOrder == null ? null : MenuRules.displayOrder(displayOrder);
        return tx.execute(status -> {
            int order = explicitOrder != null ? explicitOrder : MenuRules.nextOrder(categories.maxOrder());
            MenuCategory saved = categories.saveAndFlush(
                    new MenuCategory(cleanName, order, active == null || active, time.now()));
            return query.category(saved.getId());
        });
    }

    public MenuCategoryDto update(long id, String name, Integer displayOrder, Boolean active) {
        if (name == null && displayOrder == null && active == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String cleanName = name == null ? null : MenuRules.name(name, 50);
        Integer order = displayOrder == null ? null : MenuRules.displayOrder(displayOrder);
        return tx.execute(status -> {
            MenuCategory category = categories.lockLiveById(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MENU_CATEGORY_NOT_FOUND));
            if (Boolean.FALSE.equals(active) && category.isActive() && items.countActiveByCategory(id) > 0) {
                throw new ApiException(ErrorCode.MENU_CATEGORY_IN_USE);
            }
            if (cleanName != null) {
                category.rename(cleanName);
            }
            if (order != null) {
                category.moveTo(order);
            }
            if (active != null) {
                category.setActive(active);
            }
            categories.saveAndFlush(category);
            return query.category(id);
        });
    }

    public void delete(long id) {
        tx.executeWithoutResult(status -> {
            MenuCategory category = categories.lockLiveById(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MENU_CATEGORY_NOT_FOUND));
            if (items.countLiveByCategory(id) > 0) {
                throw new ApiException(ErrorCode.MENU_CATEGORY_IN_USE);
            }
            category.softDelete(time.now());
            categories.saveAndFlush(category);
        });
    }

    /** 排序：提交的編號列表必須剛好等於所有未刪除分類；依位置從 0 重新編號。 */
    public void reorder(List<Long> ids) {
        if (ids == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        tx.executeWithoutResult(status -> {
            // 先（不鎖）讀出編號，再依編號遞增鎖定，之後用鎖到的最新資料判斷
            List<MenuCategory> locked = categories.lockLiveByIds(categories.findLiveIds());
            MenuRules.requireExactSet(ids, locked.stream().map(MenuCategory::getId).toList());
            Map<Long, MenuCategory> byId = new HashMap<>();
            locked.forEach(c -> byId.put(c.getId(), c));
            for (int i = 0; i < ids.size(); i++) {
                byId.get(ids.get(i)).moveTo(i);
            }
            categories.saveAllAndFlush(locked);
        });
    }
}
