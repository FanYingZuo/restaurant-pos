package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import restaurant_pos.menu.ItemSnapshot;
import restaurant_pos.menu.ItemSnapshot.GroupSnapshot;
import restaurant_pos.menu.ItemSnapshot.ModifierSnapshot;
import restaurant_pos.order.SelectionValidator.Selection;
import restaurant_pos.order.SelectionValidator.Validated;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.model.Station;

/** 卡 5.2：純函式——選項驗證五步依序、各一失敗情境、快照依群組順序（01-PLAN S5）。 */
class SelectionValidatorTest {

    private static ModifierSnapshot mod(long id, String name, String delta, int order) {
        return new ModifierSnapshot(id, name, new BigDecimal(delta), order);
    }

    /** 牛肉麵 150：加料（0～2、不可重複）＝加蛋 15、加麵 20；辣度（1～1、不可重複）＝小辣 0、大辣 5；配菜（0～3、可重複）＝豆干 10。 */
    private static ItemSnapshot beef() {
        GroupSnapshot topping = new GroupSnapshot(1, "加料", 0, 2, false, 0, List.of(mod(11, "加蛋", "15", 0), mod(12, "加麵", "20", 1)));
        GroupSnapshot spicy = new GroupSnapshot(2, "辣度", 1, 1, false, 1, List.of(mod(21, "小辣", "0", 0), mod(22, "大辣", "5", 1)));
        GroupSnapshot side = new GroupSnapshot(3, "配菜", 0, 3, true, 2, List.of(mod(31, "豆干", "10", 0)));
        return new ItemSnapshot(100, "牛肉麵", new BigDecimal("150"), Station.K1, List.of(topping, spicy, side));
    }

    private static Selection sel(long id, int qty) {
        return new Selection(id, qty);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(expected);
    }

    // ---- 成功與快照 ----

    @Test
    void 合法選擇產生快照與金額() {
        Validated v = SelectionValidator.validate(beef(), 2, List.of(sel(12, 1), sel(21, 1), sel(31, 2)));
        assertThat(v.quantity()).isEqualTo(2);
        assertThat(v.modifiers()).extracting(SelectionValidator.ModifierLine::name).containsExactly("加麵", "小辣", "豆干");
        // 單價 150 ＋ 20×1 ＋ 0×1 ＋ 10×2 ＝ 190；小計 380
        assertThat(v.unitPrice()).isEqualByComparingTo("190");
        assertThat(v.lineTotal()).isEqualByComparingTo("380");
    }

    @Test
    void 快照依群組順序_不是依使用者提交順序() {
        // 使用者先送辣度再送加料，快照仍是加料（群組 0）在前
        Validated v = SelectionValidator.validate(beef(), 1, List.of(sel(22, 1), sel(12, 1), sel(11, 1)));
        assertThat(v.modifiers()).extracting(SelectionValidator.ModifierLine::name).containsExactly("加蛋", "加麵", "大辣");
    }

    @Test
    void 快照帶入名稱與加價() {
        Validated v = SelectionValidator.validate(beef(), 1, List.of(sel(11, 1), sel(21, 1)));
        SelectionValidator.ModifierLine egg = v.modifiers().get(0);
        assertThat(egg.modifierId()).isEqualTo(11);
        assertThat(egg.priceDelta()).isEqualByComparingTo("15");
        assertThat(egg.quantity()).isEqualTo(1);
    }

    @Test
    void 沒有選項的品項() {
        ItemSnapshot plain = new ItemSnapshot(200, "小菜", new BigDecimal("60"), Station.K2, List.of());
        Validated v = SelectionValidator.validate(plain, 3, null);
        assertThat(v.modifiers()).isEmpty();
        assertThat(v.unitPrice()).isEqualByComparingTo("60");
        assertThat(v.lineTotal()).isEqualByComparingTo("180");
        assertThat(SelectionValidator.validate(plain, 1, List.of()).modifiers()).isEmpty();
    }

    // ---- 第 1 步：數量 ----

    @Test
    void 步驟一_品項數量必須1到99() {
        for (int bad : new int[] {0, -1, 100, 1000}) {
            assertCode(() -> SelectionValidator.validate(beef(), bad, List.of(sel(21, 1))), ErrorCode.INVALID_QUANTITY);
        }
        assertThat(SelectionValidator.validate(beef(), 1, List.of(sel(21, 1))).quantity()).isEqualTo(1);
        assertThat(SelectionValidator.validate(beef(), 99, List.of(sel(21, 1))).quantity()).isEqualTo(99);
    }

    @Test
    void 步驟一_選項數量也必須1到99() {
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(31, 0))), ErrorCode.INVALID_QUANTITY);
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(31, 100))), ErrorCode.INVALID_QUANTITY);
    }

    @Test
    void 缺少數量是格式錯誤() {
        assertCode(() -> SelectionValidator.validate(beef(), null, List.of(sel(21, 1))), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(new Selection(21L, null))), ErrorCode.VALIDATION_ERROR);
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(new Selection(null, 1))), ErrorCode.VALIDATION_ERROR);
    }

    // ---- 第 2 步：同一選項不重複 ----

    @Test
    void 步驟二_同一選項重複列出() {
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(11, 1), sel(11, 1))),
                ErrorCode.INVALID_MODIFIER_SELECTION);
    }

    // ---- 第 3 步：選項屬於品項的群組 ----

    @Test
    void 步驟三_選項不屬於這個品項() {
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(999, 1))),
                ErrorCode.INVALID_MODIFIER_SELECTION);
    }

    // ---- 第 4 步：每群不同選項數與重複規則 ----

    @Test
    void 步驟四_不同選項數少於最少() {
        // 辣度最少 1，沒選
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(11, 1))),
                ErrorCode.MODIFIER_GROUP_SELECTION_OUT_OF_RANGE);
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of()),
                ErrorCode.MODIFIER_GROUP_SELECTION_OUT_OF_RANGE);
    }

    @Test
    void 步驟四_不同選項數多於最多() {
        // 辣度最多 1，選兩個
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(22, 1))),
                ErrorCode.MODIFIER_GROUP_SELECTION_OUT_OF_RANGE);
    }

    @Test
    void 步驟四_不允許重複的群組數量大於1() {
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(11, 2))),
                ErrorCode.MODIFIER_DUPLICATE_NOT_ALLOWED);
    }

    @Test
    void 步驟四_允許重複的群組可以數量大於1() {
        assertThat(SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(31, 5))).modifiers()).hasSize(2);
    }

    @Test
    void 步驟四_上限算的是不同選項數_不是總數量() {
        // 配菜上限 3 個不同選項，但只有 1 個選項；數量 50 允許（允許重複）
        assertThat(SelectionValidator.validate(beef(), 1, List.of(sel(21, 1), sel(31, 50))).modifiers()).hasSize(2);
    }

    @Test
    void 步驟四_範圍檢查先於重複檢查() {
        // 辣度選了兩個（超出範圍）且其中一個數量 2（重複）：先回範圍錯誤
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(21, 2), sel(22, 1))),
                ErrorCode.MODIFIER_GROUP_SELECTION_OUT_OF_RANGE);
    }

    // ---- 五步依序 ----

    @Test
    void 數量錯誤優先於其他錯誤() {
        assertCode(() -> SelectionValidator.validate(beef(), 0, List.of(sel(999, 1), sel(999, 1))), ErrorCode.INVALID_QUANTITY);
    }

    @Test
    void 重複列出優先於不屬於群組() {
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(999, 1), sel(999, 1))),
                ErrorCode.INVALID_MODIFIER_SELECTION);
    }

    @Test
    void 不屬於群組優先於群組範圍() {
        assertCode(() -> SelectionValidator.validate(beef(), 1, List.of(sel(999, 1))), ErrorCode.INVALID_MODIFIER_SELECTION);
    }
}
