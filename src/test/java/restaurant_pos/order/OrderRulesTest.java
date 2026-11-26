package restaurant_pos.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import restaurant_pos.order.ItemStatus;

/** 卡 5.1：純函式——訂單狀態重算、品項狀態轉換、單價與小計（01-PLAN S5）。 */
class OrderRulesTest {

    private static final ItemStatus P = ItemStatus.PENDING;
    private static final ItemStatus I = ItemStatus.IN_PROGRESS;
    private static final ItemStatus D = ItemStatus.DONE;
    private static final ItemStatus C = ItemStatus.CANCELLED;

    // ---- 狀態重算：五條依序 ----

    @Test
    void 條件一_沒有未取消品項_已取消() {
        assertThat(OrderRules.recalculate(List.of())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(OrderRules.recalculate(List.of(C, C))).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 條件二_全部完成_已全部上餐() {
        assertThat(OrderRules.recalculate(List.of(D))).isEqualTo(OrderStatus.SERVED);
        assertThat(OrderRules.recalculate(List.of(D, D, D))).isEqualTo(OrderStatus.SERVED);
    }

    @Test
    void 條件二_取消的品項不影響全部完成的判斷() {
        assertThat(OrderRules.recalculate(List.of(D, C, D))).isEqualTo(OrderStatus.SERVED);
    }

    @Test
    void 條件三_至少一項完成_部分上餐() {
        assertThat(OrderRules.recalculate(List.of(D, P))).isEqualTo(OrderStatus.PARTIALLY_SERVED);
        assertThat(OrderRules.recalculate(List.of(D, I))).isEqualTo(OrderStatus.PARTIALLY_SERVED);
        assertThat(OrderRules.recalculate(List.of(D, I, P, C))).isEqualTo(OrderStatus.PARTIALLY_SERVED);
    }

    @Test
    void 條件四_至少一項製作中_製作中() {
        assertThat(OrderRules.recalculate(List.of(I))).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(OrderRules.recalculate(List.of(I, P))).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(OrderRules.recalculate(List.of(P, I, C))).isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test
    void 條件五_其他_已送出() {
        assertThat(OrderRules.recalculate(List.of(P))).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(OrderRules.recalculate(List.of(P, P, C))).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void 依序判斷_部分上餐優先於製作中() {
        // 同時有完成與製作中：先符合第三條，不是第四條
        assertThat(OrderRules.recalculate(List.of(D, I))).isEqualTo(OrderStatus.PARTIALLY_SERVED);
    }

    // ---- 品項狀態轉換 ----

    @Test
    void 合法轉換() {
        assertThat(OrderRules.isLegalTransition(P, I)).isTrue();
        assertThat(OrderRules.isLegalTransition(P, D)).isTrue();
        assertThat(OrderRules.isLegalTransition(I, D)).isTrue();
        assertThat(OrderRules.isLegalTransition(P, C)).isTrue();
        assertThat(OrderRules.isLegalTransition(I, C)).isTrue();
    }

    @Test
    void 非法轉換() {
        assertThat(OrderRules.isLegalTransition(I, P)).isFalse();
        assertThat(OrderRules.isLegalTransition(P, P)).isFalse();
        assertThat(OrderRules.isLegalTransition(I, I)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ItemStatus.class)
    void 已完成是終點(ItemStatus target) {
        assertThat(OrderRules.isLegalTransition(D, target)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ItemStatus.class)
    void 已取消是終點(ItemStatus target) {
        assertThat(OrderRules.isLegalTransition(C, target)).isFalse();
    }

    // ---- 金額 ----

    private static OrderRules.ModifierPrice mod(String delta, int qty) {
        return new OrderRules.ModifierPrice(new BigDecimal(delta), qty);
    }

    @Test
    void 單價是基本價加所有選項加價乘數量() {
        BigDecimal unit = OrderRules.unitPrice(new BigDecimal("150"), List.of(mod("20", 1), mod("15", 2)));
        assertThat(unit).isEqualByComparingTo("200");
    }

    @Test
    void 沒有選項時單價等於基本價() {
        assertThat(OrderRules.unitPrice(new BigDecimal("150"), List.of())).isEqualByComparingTo("150");
    }

    @Test
    void 加價為0的選項不影響單價() {
        assertThat(OrderRules.unitPrice(new BigDecimal("150"), List.of(mod("0", 3)))).isEqualByComparingTo("150");
    }

    @Test
    void 小計是單價乘數量() {
        assertThat(OrderRules.lineTotal(new BigDecimal("170"), 3)).isEqualByComparingTo("510");
        assertThat(OrderRules.lineTotal(new BigDecimal("0"), 99)).isEqualByComparingTo("0");
    }

    @Test
    void S10範例_牛肉麵加麵乘2() {
        // 牛肉麵 150 ＋ 加麵 20 ＝ 單價 170；數量 2 ＝ 小計 340
        BigDecimal unit = OrderRules.unitPrice(new BigDecimal("150"), List.of(mod("20", 1)));
        assertThat(unit).isEqualByComparingTo("170");
        assertThat(OrderRules.lineTotal(unit, 2)).isEqualByComparingTo("340");
    }
}
