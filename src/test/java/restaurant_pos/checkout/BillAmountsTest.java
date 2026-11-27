package restaurant_pos.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 卡 7.1：純函式 金額、服務費、快速金額（01-PLAN S7）。 */
class BillAmountsTest {

    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");

    // ---- 服務費 ----

    @Test
    void 服務費_995_收100() {
        assertThat(BillAmounts.serviceCharge(995, TEN_PERCENT)).isEqualTo(100);
    }

    @Test
    void 服務費_986_收99() {
        assertThat(BillAmounts.serviceCharge(986, TEN_PERCENT)).isEqualTo(99);
    }

    @Test
    void 服務費_小計0_為0() {
        assertThat(BillAmounts.serviceCharge(0, TEN_PERCENT)).isZero();
    }

    @Test
    void 服務費_費率0_為0() {
        assertThat(BillAmounts.serviceCharge(1000, BigDecimal.ZERO)).isZero();
    }

    @Test
    void 服務費_105_剛好一半_進位為11() {
        assertThat(BillAmounts.serviceCharge(105, TEN_PERCENT)).isEqualTo(11);
    }

    // ---- 金額彙總 ----

    @Test
    void 彙總_小計服務費總額() {
        BillAmounts.Totals t = BillAmounts.totals(List.of(240L, 80L), TEN_PERCENT);
        assertThat(t.subtotal()).isEqualTo(320);
        assertThat(t.serviceCharge()).isEqualTo(32);
        assertThat(t.discount()).isZero();
        assertThat(t.total()).isEqualTo(352);
    }

    @Test
    void 彙總_服務費停用_費率0_總額等於小計() {
        BillAmounts.Totals t = BillAmounts.totals(List.of(240L, 80L), BigDecimal.ZERO);
        assertThat(t.serviceCharge()).isZero();
        assertThat(t.total()).isEqualTo(t.subtotal());
    }

    @Test
    void 彙總_折扣固定為0_總額為小計加費用() {
        BillAmounts.Totals t = BillAmounts.totals(List.of(986L), TEN_PERCENT);
        assertThat(t.discount()).isZero();
        assertThat(t.total()).isEqualTo(t.subtotal() + t.serviceCharge() - t.discount());
    }

    // ---- 入帳、找零、未付 ----

    @Test
    void 無付款_未付等於總額() {
        assertThat(BillAmounts.unpaid(352, 0)).isEqualTo(352);
    }

    @Test
    void 部分付款_實收小於未付_全數入帳無找零() {
        BillAmounts.Applied a = BillAmounts.applyCash(352, 100);
        assertThat(a.credited()).isEqualTo(100);
        assertThat(a.change()).isZero();
        assertThat(BillAmounts.unpaid(352, a.credited())).isEqualTo(252);
    }

    @Test
    void 超額付款_入帳到未付_其餘找零() {
        BillAmounts.Applied a = BillAmounts.applyCash(352, 500);
        assertThat(a.credited()).isEqualTo(352);
        assertThat(a.change()).isEqualTo(148);
        assertThat(BillAmounts.unpaid(352, a.credited())).isZero();
    }

    @Test
    void 多筆付款_已付與找零各自累計() {
        BillAmounts.Applied first = BillAmounts.applyCash(352, 100);
        long unpaid = BillAmounts.unpaid(352, first.credited());
        BillAmounts.Applied second = BillAmounts.applyCash(unpaid, 300);
        assertThat(first.credited() + second.credited()).isEqualTo(352);
        assertThat(first.change() + second.change()).isEqualTo(48);
    }

    // ---- 快速金額 ----

    @Test
    void 快速金額_1096() {
        assertThat(BillAmounts.quickAmounts(1096)).containsExactly(1096L, 1100L, 1500L, 2000L);
    }

    @Test
    void 快速金額_剛好整百_去重排序() {
        assertThat(BillAmounts.quickAmounts(1100)).containsExactly(1100L, 1500L, 2000L);
    }

    @Test
    void 快速金額_已付清_回空列表() {
        assertThat(BillAmounts.quickAmounts(0)).isEmpty();
    }

    @Test
    void 快速金額_95() {
        assertThat(BillAmounts.quickAmounts(95)).containsExactly(95L, 100L, 500L, 1000L);
    }
}
