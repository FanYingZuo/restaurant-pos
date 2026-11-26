package restaurant_pos.table;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * 桌號自然排序（純函式，S4）：連續數字依數值比較、其餘字元不分大小寫比較，所以 A2 排在 A10 前。
 * 完全相同（忽略大小寫與前導零）時再以原字串比較，讓結果穩定。
 */
public final class NaturalOrder implements Comparator<String> {

    public static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {
    }

    @Override
    public int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            boolean digitA = Character.isDigit(a.charAt(i));
            boolean digitB = Character.isDigit(b.charAt(j));
            if (digitA && digitB) {
                int endA = runEnd(a, i, true);
                int endB = runEnd(b, j, true);
                int cmp = new BigInteger(a.substring(i, endA)).compareTo(new BigInteger(b.substring(j, endB)));
                if (cmp != 0) {
                    return cmp;
                }
                i = endA;
                j = endB;
            } else if (digitA != digitB) {
                // 數字排在非數字前面
                return digitA ? -1 : 1;
            } else {
                int endA = runEnd(a, i, false);
                int endB = runEnd(b, j, false);
                int cmp = a.substring(i, endA).compareToIgnoreCase(b.substring(j, endB));
                if (cmp != 0) {
                    return cmp;
                }
                i = endA;
                j = endB;
            }
        }
        int remaining = Integer.compare(a.length() - i, b.length() - j);
        return remaining != 0 ? remaining : a.compareTo(b);
    }

    private static int runEnd(String s, int start, boolean digits) {
        int k = start;
        while (k < s.length() && Character.isDigit(s.charAt(k)) == digits) {
            k++;
        }
        return k;
    }
}
