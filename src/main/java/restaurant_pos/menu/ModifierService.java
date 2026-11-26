package restaurant_pos.menu;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.menu.MenuDtos.ModifierDto;
import restaurant_pos.menu.MenuDtos.ModifierGroupDto;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.time.BusinessDayService;

/**
 * 選項群組與選項管理（S3）：群組最少 0～20、最多 1～20、最少 ≤ 最多、是否允許重複；選項加價 0～9,999,999（0 允許）；
 * 刪群組連同選項軟刪除；選項加價變更寫稽核。
 */
@Service
public class ModifierService {

    private final MenuItemRepository items;
    private final ModifierGroupRepository groups;
    private final ModifierRepository modifiers;
    private final MenuQueryService query;
    private final AuditService audit;
    private final BusinessDayService time;
    private final TransactionTemplate tx;

    public ModifierService(MenuItemRepository items, ModifierGroupRepository groups, ModifierRepository modifiers,
            MenuQueryService query, AuditService audit, BusinessDayService time, TransactionTemplate tx) {
        this.items = items;
        this.groups = groups;
        this.modifiers = modifiers;
        this.query = query;
        this.audit = audit;
        this.time = time;
        this.tx = tx;
    }

    // ---- 群組 ----

    private static void requireRange(int min, int max) {
        if (min < 0 || min > 20 || max < 1 || max > 20 || min > max) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
    }

    public ModifierGroupDto createGroup(long itemId, String name, Integer minSelect, Integer maxSelect,
            Boolean allowDuplicate) {
        String cleanName = MenuRules.name(name, 50);
        if (minSelect == null || maxSelect == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        requireRange(minSelect, maxSelect);
        return tx.execute(status -> {
            items.lockLiveById(itemId).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            ModifierGroup saved = groups.saveAndFlush(new ModifierGroup(itemId, cleanName, minSelect, maxSelect,
                    Boolean.TRUE.equals(allowDuplicate), MenuRules.nextOrder(groups.maxOrder(itemId)), time.now()));
            return query.group(saved.getId());
        });
    }

    public ModifierGroupDto updateGroup(long id, String name, Integer minSelect, Integer maxSelect,
            Boolean allowDuplicate) {
        if (name == null && minSelect == null && maxSelect == null && allowDuplicate == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String cleanName = name == null ? null : MenuRules.name(name, 50);
        return tx.execute(status -> {
            ModifierGroup group = groups.lockLiveById(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MODIFIER_GROUP_NOT_FOUND));
            int newMin = minSelect != null ? minSelect : group.getMinSelect();
            int newMax = maxSelect != null ? maxSelect : group.getMaxSelect();
            requireRange(newMin, newMax);
            group.update(cleanName != null ? cleanName : group.getName(), newMin, newMax,
                    allowDuplicate != null ? allowDuplicate : group.isAllowDuplicate());
            groups.saveAndFlush(group);
            return query.group(id);
        });
    }

    /** 刪群組連同選項軟刪除。 */
    public void deleteGroup(long id) {
        tx.executeWithoutResult(status -> {
            ModifierGroup group = groups.lockLiveById(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MODIFIER_GROUP_NOT_FOUND));
            var now = time.now();
            for (Modifier m : modifiers.findLiveByGroup(id)) {
                m.softDelete(now);
                modifiers.save(m);
            }
            group.softDelete(now);
            groups.saveAndFlush(group);
        });
    }

    public void reorderGroups(long itemId, List<Long> ids) {
        if (ids == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        tx.executeWithoutResult(status -> {
            items.lockLiveById(itemId).orElseThrow(() -> new ApiException(ErrorCode.MENU_ITEM_NOT_FOUND));
            List<ModifierGroup> existing = groups.findLiveByItem(itemId);
            MenuRules.requireExactSet(ids, existing.stream().map(ModifierGroup::getId).toList());
            Map<Long, ModifierGroup> byId = new HashMap<>();
            existing.forEach(g -> byId.put(g.getId(), g));
            for (int i = 0; i < ids.size(); i++) {
                byId.get(ids.get(i)).moveTo(i);
            }
            groups.saveAllAndFlush(existing);
        });
    }

    // ---- 選項 ----

    public ModifierDto createModifier(long groupId, String name, BigDecimal priceDelta) {
        String cleanName = MenuRules.name(name, 50);
        BigDecimal cleanPrice = MenuRules.price(priceDelta);
        return tx.execute(status -> {
            groups.lockLiveById(groupId).orElseThrow(() -> new ApiException(ErrorCode.MODIFIER_GROUP_NOT_FOUND));
            Modifier saved = modifiers.saveAndFlush(new Modifier(groupId, cleanName, cleanPrice,
                    MenuRules.nextOrder(modifiers.maxOrder(groupId)), time.now()));
            return ModifierDto.of(saved);
        });
    }

    public ModifierDto updateModifier(long id, String name, BigDecimal priceDelta) {
        if (name == null && priceDelta == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String cleanName = name == null ? null : MenuRules.name(name, 50);
        BigDecimal cleanPrice = priceDelta == null ? null : MenuRules.price(priceDelta);
        return tx.execute(status -> {
            Modifier modifier = modifiers.lockLiveById(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MODIFIER_NOT_FOUND));
            if (cleanName != null) {
                modifier.rename(cleanName);
            }
            if (cleanPrice != null && modifier.getPriceDelta().compareTo(cleanPrice) != 0) {
                String before = String.valueOf(modifier.getPriceDelta().longValue());
                modifier.changePriceDelta(cleanPrice);
                audit.record(AuditEntry.of("MODIFIER_PRICE_CHANGED", "modifier", id)
                        .withChange(before, String.valueOf(cleanPrice.longValue())));
            }
            return ModifierDto.of(modifiers.saveAndFlush(modifier));
        });
    }

    public void deleteModifier(long id) {
        tx.executeWithoutResult(status -> {
            Modifier modifier = modifiers.lockLiveById(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MODIFIER_NOT_FOUND));
            modifier.softDelete(time.now());
            modifiers.saveAndFlush(modifier);
        });
    }

    public void reorderModifiers(long groupId, List<Long> ids) {
        if (ids == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        tx.executeWithoutResult(status -> {
            groups.lockLiveById(groupId).orElseThrow(() -> new ApiException(ErrorCode.MODIFIER_GROUP_NOT_FOUND));
            List<Modifier> existing = modifiers.findLiveByGroup(groupId);
            MenuRules.requireExactSet(ids, existing.stream().map(Modifier::getId).toList());
            Map<Long, Modifier> byId = new HashMap<>();
            existing.forEach(m -> byId.put(m.getId(), m));
            for (int i = 0; i < ids.size(); i++) {
                byId.get(ids.get(i)).moveTo(i);
            }
            modifiers.saveAllAndFlush(existing);
        });
    }
}
