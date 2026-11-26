package restaurant_pos.table;

import java.time.Instant;
import java.time.LocalDate;

/** 桌位與用餐紀錄的回應物件（欄位與 docs/contract/openapi.yaml 一致）。**不含顧客 token。** */
public final class TableDtos {

    private TableDtos() {
    }

    public record TableLayoutDto(long id, String tableNo, int capacity, TableShape shape, int width, int height,
            int x, int y, String area) {
        static TableLayoutDto of(DiningTable t) {
            return new TableLayoutDto(t.getId(), t.getTableNo(), t.getCapacity(), t.getShape(), t.getWidth(),
                    t.getHeight(), t.getX(), t.getY(), t.getArea());
        }
    }

    public record SessionSummary(long id, long tableId, String tableNo, String billNo, LocalDate businessDate,
            SessionStatus status, boolean occupiesTable, int guestCount, Instant openedAt, Instant qrExpiresAt,
            boolean qrExpired, boolean serviceChargeEnabled, boolean checkoutInProgress, boolean pendingRecheckout) {

        static SessionSummary of(TableSession s, String tableNo, Instant now) {
            return new SessionSummary(s.getId(), s.getTableId(), tableNo, billNoText(s.getBillNo()),
                    s.getBusinessDate(), s.getStatus(), s.isOccupyingTable(), s.getGuestCount(), s.getOpenedAt(),
                    s.getQrExpiresAt(), !now.isBefore(s.getQrExpiresAt()), s.isServiceChargeEnabled(),
                    s.isCheckoutInProgress(), s.isPendingRecheckout());
        }
    }

    public record TableViewDto(long id, String tableNo, int capacity, TableShape shape, int width, int height,
            int x, int y, String area, boolean inUse, SessionSummary session) {
    }

    /** 帳單號為三位數補零字串（§4.4）。 */
    public static String billNoText(int billNo) {
        return String.format("%03d", billNo);
    }
}
