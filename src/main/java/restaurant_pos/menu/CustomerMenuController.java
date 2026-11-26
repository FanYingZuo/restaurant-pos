package restaurant_pos.menu;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.menu.MenuDtos.CustomerMenu;
import restaurant_pos.shared.image.ImageHttp;
import restaurant_pos.shared.image.ImageStorage;
import restaurant_pos.shared.image.StoredImage;

/**
 * 顧客菜單與菜單圖片（合約 getCustomerMenu、getMenuImage）：**不需任何驗證**（§4.5）。
 * 路徑在 /api/customer 底下，但資料屬於菜單模組，所以放在這裡。
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerMenuController {

    private final MenuQueryService query;
    private final ImageStorage images;

    public CustomerMenuController(MenuQueryService query, ImageStorage images) {
        this.query = query;
        this.images = images;
    }

    @GetMapping("/menu")
    public CustomerMenu menu() {
        return query.customerMenu();
    }

    /** 讀取前先驗證鍵格式（防路徑穿越）；回應帶「禁止猜測內容類型」與可快取標頭。 */
    @GetMapping("/images/{imageKey}")
    public ResponseEntity<byte[]> image(@PathVariable String imageKey) {
        StoredImage image = images.load(imageKey);
        return ResponseEntity.ok().headers(ImageHttp.headers(image.contentType())).body(image.content());
    }
}
