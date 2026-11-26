package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import restaurant_pos.order.TicketFormatter.Header;
import restaurant_pos.order.TicketFormatter.TicketItem;
import restaurant_pos.order.TicketFormatter.TicketModifier;

/** 卡 5.10：列印內容純函式——廚房單、修改單、取消單（01-PLAN S5、S11 列印監聽器）。 */
class TicketFormatterTest {

    private static final Header HEADER = new Header("A1", 2, "14:05");

    private static final List<TicketItem> ITEMS = List.of(
            new TicketItem(2, "牛肉麵", List.of(new TicketModifier("加麵", 1), new TicketModifier("加蛋", 2)), "少辣"),
            new TicketItem(1, "小菜", List.of(), ""));

    @Test
    void 廚房單格式() {
        String expected = """
                ================================
                桌號 A1　第 2 批　14:05
                --------------------------------
                2 x 牛肉麵
                    + 加麵 x1
                    + 加蛋 x2
                    備註：少辣
                1 x 小菜
                --------------------------------
                訂單備註：不要香菜
                """;
        assertThat(TicketFormatter.kitchenTicket(HEADER, ITEMS, "不要香菜")).isEqualTo(expected);
    }

    @Test
    void 沒有訂單備註時不印訂單備註那一行() {
        String text = TicketFormatter.kitchenTicket(HEADER, ITEMS, "");
        assertThat(text).doesNotContain("訂單備註");
        assertThat(TicketFormatter.kitchenTicket(HEADER, ITEMS, null)).doesNotContain("訂單備註");
        assertThat(TicketFormatter.kitchenTicket(HEADER, ITEMS, "   ")).doesNotContain("訂單備註");
    }

    @Test
    void 訂單備註加在最後() {
        String text = TicketFormatter.kitchenTicket(HEADER, ITEMS, "趕時間");
        assertThat(text.stripTrailing()).endsWith("訂單備註：趕時間");
    }

    @Test
    void 沒有選項與備註的品項只有一行() {
        String text = TicketFormatter.kitchenTicket(HEADER, List.of(new TicketItem(1, "小菜", List.of(), null)), null);
        assertThat(text).contains("1 x 小菜\n").doesNotContain("備註").doesNotContain("+");
    }

    @Test
    void 選項與備註縮排() {
        String text = TicketFormatter.kitchenTicket(HEADER, ITEMS, null);
        assertThat(text).contains("\n    + 加麵 x1\n").contains("\n    備註：少辣\n");
        // 品項行不縮排
        assertThat(text).contains("\n2 x 牛肉麵\n");
    }

    @Test
    void 修改單標註修改後() {
        String text = TicketFormatter.modifyTicket(HEADER, ITEMS, null);
        assertThat(text).contains("【修改後】").contains("桌號 A1　第 2 批　14:05").contains("2 x 牛肉麵");
        assertThat(text.indexOf("【修改後】")).isGreaterThan(text.indexOf("桌號"));
        assertThat(TicketFormatter.kitchenTicket(HEADER, ITEMS, null)).doesNotContain("修改後");
    }

    @Test
    void 取消單標註取消且每項加取消原因() {
        List<TicketItem> cancelled = List.of(
                new TicketItem(2, "牛肉麵", List.of(new TicketModifier("加麵", 1)), "少辣", "客人不要了"),
                new TicketItem(1, "小菜", List.of(), "", "點錯"));
        String text = TicketFormatter.cancelTicket(HEADER, cancelled, null);
        assertThat(text).contains("【取消】").contains("取消原因：客人不要了").contains("取消原因：點錯");
        assertThat(text.indexOf("取消原因：客人不要了")).isLessThan(text.indexOf("1 x 小菜"));
        assertThat(TicketFormatter.kitchenTicket(HEADER, ITEMS, null)).doesNotContain("取消");
    }

    @Test
    void 相同輸入產生相同內容_內容固定() {
        assertThat(TicketFormatter.kitchenTicket(HEADER, ITEMS, "x")).isEqualTo(TicketFormatter.kitchenTicket(HEADER, ITEMS, "x"));
    }

    @Test
    void 不同批次與桌號顯示在表頭() {
        String text = TicketFormatter.kitchenTicket(new Header("包廂 3", 12, "03:07"), ITEMS, null);
        assertThat(text).contains("桌號 包廂 3　第 12 批　03:07");
    }
}
