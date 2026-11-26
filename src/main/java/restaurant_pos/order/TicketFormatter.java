package restaurant_pos.order;

import java.util.List;

/**
 * 列印內容（純函式，S5 卡 5.10；S11 才接上列印監聽器）：廚房單、修改單、取消單的文字格式。
 * 時間由呼叫者先轉成台北時間的「HH:mm」字串傳入，本類別不依賴時鐘或時區。
 *
 * <p>格式（規格 S11：表頭「桌號＋第幾批、時間」；品項行「數量＋名稱」，縮排列出選項與備註；
 * 有訂單備註時加在最後）：
 * <pre>
 * ================================
 * 桌號 A1　第 2 批　14:05
 * --------------------------------
 * 2 x 牛肉麵
 *     + 加麵 x1
 *     備註：少辣
 * 1 x 小菜
 * --------------------------------
 * 訂單備註：不要香菜
 * </pre>
 * 修改單在表頭下方加「【修改後】」；取消單加「【取消】」，且每項下方加「取消原因：…」。
 */
public final class TicketFormatter {

    private static final String DOUBLE_LINE = "================================";
    private static final String SINGLE_LINE = "--------------------------------";
    private static final String INDENT = "    ";

    private TicketFormatter() {
    }

    public record TicketModifier(String name, int quantity) {
    }

    public record TicketItem(int quantity, String name, List<TicketModifier> modifiers, String note,
            String cancelReason) {
        public TicketItem(int quantity, String name, List<TicketModifier> modifiers, String note) {
            this(quantity, name, modifiers, note, null);
        }
    }

    public record Header(String tableNo, int batchNo, String timeText) {
    }

    /** 廚房單：同一站別的品項。 */
    public static String kitchenTicket(Header header, List<TicketItem> items, String orderNote) {
        return build(header, null, items, orderNote);
    }

    /** 修改單：標註「修改後」，內容為修改後的品項。 */
    public static String modifyTicket(Header header, List<TicketItem> items, String orderNote) {
        return build(header, "【修改後】", items, orderNote);
    }

    /** 取消單：標註「取消」，每項加取消原因。 */
    public static String cancelTicket(Header header, List<TicketItem> items, String orderNote) {
        return build(header, "【取消】", items, orderNote);
    }

    private static String build(Header header, String tag, List<TicketItem> items, String orderNote) {
        StringBuilder sb = new StringBuilder();
        sb.append(DOUBLE_LINE).append('\n');
        sb.append("桌號 ").append(header.tableNo()).append("　第 ").append(header.batchNo()).append(" 批　")
                .append(header.timeText()).append('\n');
        if (tag != null) {
            sb.append(tag).append('\n');
        }
        sb.append(SINGLE_LINE).append('\n');
        for (TicketItem item : items) {
            sb.append(item.quantity()).append(" x ").append(item.name()).append('\n');
            for (TicketModifier m : item.modifiers()) {
                sb.append(INDENT).append("+ ").append(m.name()).append(" x").append(m.quantity()).append('\n');
            }
            if (item.note() != null && !item.note().isBlank()) {
                sb.append(INDENT).append("備註：").append(item.note()).append('\n');
            }
            if (item.cancelReason() != null && !item.cancelReason().isBlank()) {
                sb.append(INDENT).append("取消原因：").append(item.cancelReason()).append('\n');
            }
        }
        sb.append(SINGLE_LINE).append('\n');
        if (orderNote != null && !orderNote.isBlank()) {
            sb.append("訂單備註：").append(orderNote).append('\n');
        }
        return sb.toString();
    }
}
