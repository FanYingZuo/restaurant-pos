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

    static SessionView of(TableSession s) {
        return new SessionView(s.getId(), s.getTableId(), s.getBillNo(), s.getBusinessDate(), s.getStatus(),
                s.isOccupyingTable(), s.getGuestCount(), s.isServiceChargeEnabled(), s.isCheckoutInProgress(),
                s.isPendingRecheckout(), s.getQrExpiresAt(), s.getOpenedAt());
    }
}
