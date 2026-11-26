package restaurant_pos.table;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 卡 4.1：桌號自然排序純函式（A2 在 A10 前）。 */
class NaturalOrderTest {

    private static List<String> sorted(String... values) {
        List<String> list = new ArrayList<>(List.of(values));
        list.sort(NaturalOrder.INSTANCE);
        return list;
    }

    @Test
    void A2排在A10前() {
        assertThat(sorted("A10", "A2", "A1")).containsExactly("A1", "A2", "A10");
    }

    @Test
    void 純數字依數值比較() {
        assertThat(sorted("10", "9", "100", "1")).containsExactly("1", "9", "10", "100");
    }

    @Test
    void 英文字母不分大小寫() {
        assertThat(sorted("b1", "A2", "a10", "B2")).containsExactly("A2", "a10", "b1", "B2");
    }

    @Test
    void 不同前綴先比前綴() {
        assertThat(sorted("B1", "A10", "A9", "C1")).containsExactly("A9", "A10", "B1", "C1");
    }

    @Test
    void 含分隔符號與多段數字() {
        assertThat(sorted("T-10", "T-2", "T-1")).containsExactly("T-1", "T-2", "T-10");
        assertThat(sorted("A1-10", "A1-2", "A2-1")).containsExactly("A1-2", "A1-10", "A2-1");
    }

    @Test
    void 中文桌號() {
        assertThat(sorted("包廂10", "包廂2", "吧台3")).containsExactly("包廂2", "包廂10", "吧台3");
    }

    @Test
    void 數字排在非數字前面() {
        assertThat(sorted("A", "1", "B2")).containsExactly("1", "A", "B2");
    }

    @Test
    void 前導零視為相同數值_結果仍然穩定() {
        List<String> result = sorted("A02", "A2", "A10");
        assertThat(result.get(2)).isEqualTo("A10");
        assertThat(NaturalOrder.INSTANCE.compare("A02", "A2")).isNotZero();
        assertThat(NaturalOrder.INSTANCE.compare("A2", "A02")).isEqualTo(-NaturalOrder.INSTANCE.compare("A02", "A2"));
    }

    @Test
    void 較短的在前() {
        assertThat(sorted("A1B", "A1", "A")).containsExactly("A", "A1", "A1B");
    }

    @Test
    void 非常大的數字不會溢位() {
        assertThat(sorted("A99999999999999999999", "A100000000000000000000"))
                .containsExactly("A99999999999999999999", "A100000000000000000000");
    }

    @Test
    void 自己與自己相等() {
        assertThat(NaturalOrder.INSTANCE.compare("A10", "A10")).isZero();
    }
}
