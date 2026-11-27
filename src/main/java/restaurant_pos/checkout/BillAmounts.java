package restaurant_pos.checkout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.TreeSet;

/**
 * 帳單金額純函式（無 Spring、無資料庫）。
 *
 * <p>規格出處：docs/plan/01-PLAN.md S7「金額」「快速金額」「現金入帳」。
 */
public final class BillAmounts {

    private BillAmounts() {}

    /** 小計、服務費、折扣、總額。 */
    public record Totals(long subtotal, long serviceCharge, long discount, long total) {}

    /** 一次現金付款的入帳與找零。 */
    public record Applied(long credited, long change) {}

    /** 服務費 ＝ 小計 × 費率，HALF_UP 到整數。 */
    public static long serviceCharge(long subtotal, BigDecimal rate) {
        return BigDecimal.valueOf(subtotal).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 小計 ＝ 明細加總；折扣固定為 0；總額 ＝ 小計 ＋ 費用 − 折扣。服務費停用時呼叫端傳費率 0。 */
    public static Totals totals(List<Long> lineTotals, BigDecimal serviceRate) {
        long subtotal = lineTotals.stream().mapToLong(Long::longValue).sum();
        long charge = serviceCharge(subtotal, serviceRate);
        long discount = 0;
        return new Totals(subtotal, charge, discount, subtotal + charge - discount);
    }

    /** 未付 ＝ 總額 − 已付（不小於 0）。 */
    public static long unpaid(long total, long paid) {
        return Math.max(0, total - paid);
    }

    /** 現金入帳：最多入到未付，超出為找零；不足就付多少入多少。 */
    public static Applied applyCash(long unpaid, long received) {
        long credited = Math.min(unpaid, received);
        return new Applied(credited, received - credited);
    }

    /** 快速金額：{未付、向上取整到 100、500、1000} 去重排序；已付清回空列表。 */
    public static List<Long> quickAmounts(long unpaid) {
        if (unpaid <= 0) {
            return List.of();
        }
        TreeSet<Long> set = new TreeSet<>();
        set.add(unpaid);
        for (long unit : new long[] {100, 500, 1000}) {
            set.add(((unpaid + unit - 1) / unit) * unit);
        }
        return List.copyOf(set);
    }
}
