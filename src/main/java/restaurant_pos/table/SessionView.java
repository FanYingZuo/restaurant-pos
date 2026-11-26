package restaurant_pos.table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 提供給其他模組的用餐紀錄唯讀檢視（§4.1：跨模組只透過 Service 或唯讀查詢，不改別模組的實體）。
 * 不含顧客 token。
 */
public record SessionView(long id, long tableId, int billNo, LocalDate businessDate, SessionStatus status,
        boolean occupiesTable, int guestCount, boolean serviceChargeEnabled, boolean checkoutInProgress,
        boolean pendingRecheckout, Instant qrExpiresAt, Instant openedAt) {

    /** 可編輯（動到訂單的寫入前檢查）：依序檢查使用中、非待重新結帳、非結帳中。 */
    public void requireEditable() {
        if (status != SessionStatus.OPEN) {
            throw new restaurant_pos.shared.error.ApiException(restaurant_pos.shared.error.ErrorCode.SESSION_NOT_OPEN);
        }
        if (pendingRecheckout) {
            throw new restaurant_pos.shared.error.ApiException(restaurant_pos.shared.error.ErrorCode.SESSION_PENDING_RECHECKOUT);
        }
        if (checkoutInProgress) {
            throw new restaurant_pos.shared.error.ApiException(restaurant_pos.shared.error.ErrorCode.CHECKOUT_IN_PROGRESS);
        }
    }

    static SessionView of(TableSession s) {
        return new SessionView(s.getId(), s.getTableId(), s.getBillNo(), s.getBusinessDate(), s.getStatus(),
                s.isOccupyingTable(), s.getGuestCount(), s.isServiceChargeEnabled(), s.isCheckoutInProgress(),
                s.isPendingRecheckout(), s.getQrExpiresAt(), s.getOpenedAt());
    }
}
