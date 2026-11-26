package restaurant_pos.menu;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.menu.MenuDtos.MenuItemDto;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.image.ImageStorage;
import restaurant_pos.shared.model.Station;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 品項管理（S3）：名稱 1～80 字、價格 0～9,999,999 整數、站別 K1／K2；改價寫稽核；先下架才可刪除；
 * 售完（今天營業日，隔日自動恢復、可手動恢復，重新上架不清售完日）；圖片上傳與刪除（§4.10）。
 * 改價改名不影響既有訂單（訂單建立時存快照）。
 */
@Service
public class MenuItemService {

    private final MenuCategoryRepository categories;
    private final MenuItemRepository items;
    private final MenuQueryService query;
    private final AuditService audit;
    private final ImageStorage images;
    private final BusinessDayService time;
    private final TransactionTemplate tx;

    public MenuItemService(MenuCategoryRepository categories, MenuItemRepository items, MenuQueryService query,
            AuditService audit, ImageStorage images, BusinessDayService time, TransactionTemplate tx) {
        this.categories = categories;
        this.items = items;
        this.query = query;
        this.audit = audit;
        this.images = images;
        this.time = time;
        this.tx = tx;
    }

    public MenuItemDto create(Long categoryId, String name, BigDecimal price, Station station, Boolean active) {
        String cleanName = MenuRules.name(name, 80);
        if (categoryId == null || station == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        BigDecimal cleanPrice = MenuRules.price(price);
        return tx.execute(status -> {
            categories.lockLiveById(categoryId).orElseThrow(() -> new ApiException(ErrorCode.MENU_CATEGORY_NOT_FOUND));
            MenuItem saved = items.saveAndFlush(new MenuItem(categoryId, cleanName, cleanPrice, station,
                    Boolean.TRUE.equals(active), MenuRules.nextOrder(items.maxOrder(categoryId)), time.now()));
            return query.item(saved.getId());
        });
    }

    public MenuItemDto update(long id, String name, BigDecimal price, Station station, Boolean active) {
        if (name == null && price == null && station == null && active == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String cleanName = name == null ? null : MenuRules.name(name, 80);
        BigDecimal cleanPrice = price == null ? null : MenuRules.price(price);
        return tx.execute(status -> {
            MenuItem item = items.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            if (cleanName != null) {
                item.rename(cleanName);
            }
            if (cleanPrice != null && item.getPrice().compareTo(cleanPrice) != 0) {
                String before = String.valueOf(item.getPrice().longValue());
                item.changePrice(cleanPrice);
                audit.record(AuditEntry.of("MENU_ITEM_PRICE_CHANGED", "menu_item", id)
                        .withChange(before, String.valueOf(cleanPrice.longValue())));
            }
            if (station != null) {
                item.changeStation(station);
            }
            if (active != null) {
                item.setActive(active);
            }
            items.saveAndFlush(item);
            return query.item(id);
        });
    }

    /** 軟刪除；需先下架。 */
    public void delete(long id) {
        tx.executeWithoutResult(status -> {
            MenuItem item = items.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            if (item.isActive()) {
                throw new ApiException(ErrorCode.MENU_ITEM_NOT_ARCHIVED);
            }
            item.softDelete(time.now());
            items.saveAndFlush(item);
        });
    }

    /** 設為售完：記錄今天營業日；已售完時重複呼叫回目前狀態。 */
    public MenuItemDto markSoldOut(long id) {
        return tx.execute(status -> {
            MenuItem item = items.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            item.soldOutOn(time.today());
            items.saveAndFlush(item);
            return query.item(id);
        });
    }

    /** 手動恢復供應。 */
    public MenuItemDto clearSoldOut(long id) {
        return tx.execute(status -> {
            MenuItem item = items.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            item.clearSoldOut();
            items.saveAndFlush(item);
            return query.item(id);
        });
    }

    /** 排序：列表必須剛好等於該分類所有未刪除品項；依位置從 0 重新編號。 */
    public void reorder(long categoryId, List<Long> ids) {
        if (ids == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        tx.executeWithoutResult(status -> {
            categories.lockLiveById(categoryId).orElseThrow(() -> new ApiException(ErrorCode.MENU_CATEGORY_NOT_FOUND));
            List<MenuItem> existing = items.findLiveByCategory(categoryId);
            MenuRules.requireExactSet(ids, existing.stream().map(MenuItem::getId).toList());
            Map<Long, MenuItem> byId = new HashMap<>();
            existing.forEach(i -> byId.put(i.getId(), i));
            for (int i = 0; i < ids.size(); i++) {
                byId.get(ids.get(i)).moveTo(i);
            }
            items.saveAllAndFlush(existing);
        });
    }

    // ---- 圖片（§4.10） ----

    /**
     * 上傳圖片：新圖儲存成功後才刪舊檔（舊檔在交易提交後才刪；交易回滾則刪掉新檔），刪檔失敗只記日誌。
     * 不合法的圖片丟 INVALID_IMAGE，舊圖不受影響。
     */
    public MenuItemDto uploadImage(long id, byte[] content) {
        return tx.execute(status -> {
            MenuItem item = items.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            String oldKey = item.getImageKey();
            String newKey = images.replace(content, null);
            afterCompletion(committed -> images.delete(committed ? oldKey : newKey));
            item.setImageKey(newKey);
            items.saveAndFlush(item);
            return query.item(id);
        });
    }

    public MenuItemDto deleteImage(long id) {
        return tx.execute(status -> {
            MenuItem item = items.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            String oldKey = item.getImageKey();
            if (oldKey != null) {
                item.setImageKey(null);
                items.saveAndFlush(item);
                afterCompletion(committed -> {
                    if (committed) {
                        images.delete(oldKey);
                    }
                });
            }
            return query.item(id);
        });
    }

    private static void afterCompletion(java.util.function.Consumer<Boolean> action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.accept(status == STATUS_COMMITTED);
            }
        });
    }
}
