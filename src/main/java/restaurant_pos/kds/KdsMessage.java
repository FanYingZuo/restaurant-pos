package restaurant_pos.kds;

import restaurant_pos.order.KitchenQueryService.KitchenItem;

/**
 * WebSocket 推播訊息（合約 KdsMessage，以 {@code type} 區分）：
 * 品項新增或更新（完整資料）、品項移除（編號）、整板重新載入。
 */
public final class KdsMessage {

    private KdsMessage() {
    }

    public record ItemUpsert(String type, KitchenItem item) {
        public static ItemUpsert of(KitchenItem item) {
            return new ItemUpsert("ITEM_UPSERT", item);
        }
    }

    public record ItemRemove(String type, long orderItemId) {
        public static ItemRemove of(long orderItemId) {
            return new ItemRemove("ITEM_REMOVE", orderItemId);
        }
    }

    public record BoardReload(String type) {
        public static BoardReload create() {
            return new BoardReload("BOARD_RELOAD");
        }
    }
}
