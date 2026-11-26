package restaurant_pos.menu;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;

/** 菜單欄位規則（S3）：名稱長度、價格（整數、0～9,999,999）、排序清單必須剛好等於該層全部項目。 */
final class MenuRules {

    static final BigDecimal MAX_PRICE = new BigDecimal("9999999");

    private MenuRules() {
    }

    /** 去除頭尾空白後長度必須在 1～max 之間，否則 VALIDATION_ERROR。 */
    static String name(String raw, int max) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    /** 價格必須是 0～9,999,999 的整數；未提供為 VALIDATION_ERROR，有小數或超出範圍為 INVALID_PRICE。 */
    static BigDecimal price(BigDecimal raw) {
        if (raw == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (raw.signum() < 0 || raw.compareTo(MAX_PRICE) > 0) {
            throw new ApiException(ErrorCode.INVALID_PRICE);
        }
        try {
            return raw.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ApiException(ErrorCode.INVALID_PRICE);
        }
    }

    /** 顯示順序 0～9999。 */
    static int displayOrder(Integer raw) {
        if (raw == null || raw < 0 || raw > 9999) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return raw;
    }

    /** 提交的編號列表必須剛好等於該層所有未刪除項目（不多、不少、不重複），否則 INVALID_SORT_REQUEST。 */
    static void requireExactSet(List<Long> requested, List<Long> existing) {
        if (requested == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : requested) {
            if (id == null || !seen.add(id)) {
                throw new ApiException(ErrorCode.INVALID_SORT_REQUEST);
            }
        }
        if (!seen.equals(new HashSet<>(existing))) {
            throw new ApiException(ErrorCode.INVALID_SORT_REQUEST);
        }
    }

    /** 新項目排在最後；順序上限 9999。 */
    static int nextOrder(int currentMax) {
        return Math.min(currentMax + 1, 9999);
    }
}
