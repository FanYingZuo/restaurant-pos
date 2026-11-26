package restaurant_pos.menu;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import restaurant_pos.menu.MenuDtos.MenuCategoryDto;
import restaurant_pos.menu.MenuDtos.MenuItemDto;
import restaurant_pos.menu.MenuDtos.MenuTree;
import restaurant_pos.menu.MenuDtos.ModifierDto;
import restaurant_pos.menu.MenuDtos.ModifierGroupDto;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.model.Station;

/**
 * 菜單管理 API（合約 menu 標籤，/api/pos/menu/**）：只需裝置憑證，不需 PIN 與解鎖（D-4）。
 * Controller 只做格式轉換與驗證、呼叫 Service、回 DTO（§4.1）。
 */
@RestController
@RequestMapping("/api/pos/menu")
public class MenuController {

    public record CategoryCreate(String name, Integer displayOrder, Boolean active) {
    }

    public record CategoryUpdate(String name, Integer displayOrder, Boolean active) {
    }

    public record ItemCreate(Long categoryId, String name, BigDecimal price, Station station, Boolean active) {
    }

    public record ItemUpdate(String name, BigDecimal price, Station station, Boolean active) {
    }

    public record GroupCreate(String name, Integer minSelect, Integer maxSelect, Boolean allowDuplicate) {
    }

    public record GroupUpdate(String name, Integer minSelect, Integer maxSelect, Boolean allowDuplicate) {
    }

    public record ModifierCreate(String name, BigDecimal priceDelta) {
    }

    public record ModifierUpdate(String name, BigDecimal priceDelta) {
    }

    public record ReorderRequest(List<Long> ids) {
    }

    private final MenuQueryService query;
    private final MenuCategoryService categories;
    private final MenuItemService items;
    private final ModifierService modifiers;

    public MenuController(MenuQueryService query, MenuCategoryService categories, MenuItemService items,
            ModifierService modifiers) {
        this.query = query;
        this.categories = categories;
        this.items = items;
        this.modifiers = modifiers;
    }

    @GetMapping
    public MenuTree tree() {
        return query.tree();
    }

    // ---- 分類 ----

    @PostMapping("/categories")
    public ResponseEntity<MenuCategoryDto> createCategory(@RequestBody CategoryCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(categories.create(request.name(), request.displayOrder(), request.active()));
    }

    @PatchMapping("/categories/{categoryId}")
    public MenuCategoryDto updateCategory(@PathVariable long categoryId, @RequestBody CategoryUpdate request) {
        return categories.update(categoryId, request.name(), request.displayOrder(), request.active());
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategory(@PathVariable long categoryId) {
        categories.delete(categoryId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/categories/order")
    public ResponseEntity<Void> reorderCategories(@RequestBody ReorderRequest request) {
        categories.reorder(request.ids());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/categories/{categoryId}/items/order")
    public ResponseEntity<Void> reorderItems(@PathVariable long categoryId, @RequestBody ReorderRequest request) {
        items.reorder(categoryId, request.ids());
        return ResponseEntity.noContent().build();
    }

    // ---- 品項 ----

    @PostMapping("/items")
    public ResponseEntity<MenuItemDto> createItem(@RequestBody ItemCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(items.create(request.categoryId(), request.name(),
                request.price(), request.station(), request.active()));
    }

    @PatchMapping("/items/{itemId}")
    public MenuItemDto updateItem(@PathVariable long itemId, @RequestBody ItemUpdate request) {
        return items.update(itemId, request.name(), request.price(), request.station(), request.active());
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable long itemId) {
        items.delete(itemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/items/{itemId}/sold-out")
    public MenuItemDto markSoldOut(@PathVariable long itemId) {
        return items.markSoldOut(itemId);
    }

    @DeleteMapping("/items/{itemId}/sold-out")
    public MenuItemDto clearSoldOut(@PathVariable long itemId) {
        return items.clearSoldOut(itemId);
    }

    // ---- 選項群組與選項 ----

    @PostMapping("/items/{itemId}/modifier-groups")
    public ResponseEntity<ModifierGroupDto> createGroup(@PathVariable long itemId, @RequestBody GroupCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(modifiers.createGroup(itemId, request.name(),
                request.minSelect(), request.maxSelect(), request.allowDuplicate()));
    }

    @PutMapping("/items/{itemId}/modifier-groups/order")
    public ResponseEntity<Void> reorderGroups(@PathVariable long itemId, @RequestBody ReorderRequest request) {
        modifiers.reorderGroups(itemId, request.ids());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/modifier-groups/{groupId}")
    public ModifierGroupDto updateGroup(@PathVariable long groupId, @RequestBody GroupUpdate request) {
        return modifiers.updateGroup(groupId, request.name(), request.minSelect(), request.maxSelect(),
                request.allowDuplicate());
    }

    @DeleteMapping("/modifier-groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(@PathVariable long groupId) {
        modifiers.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/modifier-groups/{groupId}/modifiers")
    public ResponseEntity<ModifierDto> createModifier(@PathVariable long groupId, @RequestBody ModifierCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(modifiers.createModifier(groupId, request.name(), request.priceDelta()));
    }

    @PutMapping("/modifier-groups/{groupId}/modifiers/order")
    public ResponseEntity<Void> reorderModifiers(@PathVariable long groupId, @RequestBody ReorderRequest request) {
        modifiers.reorderModifiers(groupId, request.ids());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/modifiers/{modifierId}")
    public ModifierDto updateModifier(@PathVariable long modifierId, @RequestBody ModifierUpdate request) {
        return modifiers.updateModifier(modifierId, request.name(), request.priceDelta());
    }

    @DeleteMapping("/modifiers/{modifierId}")
    public ResponseEntity<Void> deleteModifier(@PathVariable long modifierId) {
        modifiers.deleteModifier(modifierId);
        return ResponseEntity.noContent().build();
    }

    // ---- 圖片 ----

    @PutMapping(value = "/items/{itemId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MenuItemDto uploadImage(@PathVariable long itemId, @RequestParam("file") MultipartFile file) {
        try {
            return items.uploadImage(itemId, file.getBytes());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_IMAGE);
        }
    }

    @DeleteMapping("/items/{itemId}/image")
    public MenuItemDto deleteImage(@PathVariable long itemId) {
        return items.deleteImage(itemId);
    }
}
