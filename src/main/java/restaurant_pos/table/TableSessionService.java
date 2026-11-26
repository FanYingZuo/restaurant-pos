package restaurant_pos.table;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.config.PosProperties;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.security.VerifiedEmployee;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.setting.Setting;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.table.TableDtos.SessionSummary;

/**
 * 用餐紀錄（S4）：開桌、QR、修改人數與服務費、待重新結帳清單，以及供其他模組使用的生命週期方法。
 *
 * <p>開桌流程（§4.6、S4）：鎖桌位 → 檢查未使用 → 鎖並遞增當日流水 → 建立用餐紀錄（token、QR 到期＝開桌＋4 小時、
 * 服務費預設值）→ 桌位使用中 → 發佈「用餐開始」→ 回傳（**不含 token**）。同時開同一桌只有一個成功。
 * 交易內第一個動作就是鎖（REPEATABLE READ，§4.6）。
 */
@Service
public class TableSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final DailySequenceService sequences;
    private final BusinessDayService time;
    private final AppSettingService settings;
    private final AuditService audit;
    private final PinVerifier pins;
    private final QrImageService qr;
    private final PosProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public TableSessionService(DiningTableRepository tables, TableSessionRepository sessions,
            DailySequenceService sequences, BusinessDayService time, AppSettingService settings, AuditService audit,
            PinVerifier pins, QrImageService qr, PosProperties properties, ApplicationEventPublisher events,
            TransactionTemplate tx) {
        this.tables = tables;
        this.sessions = sessions;
        this.sequences = sequences;
        this.time = time;
        this.settings = settings;
        this.audit = audit;
        this.pins = pins;
        this.qr = qr;
        this.properties = properties;
        this.events = events;
        this.tx = tx;
    }

    // ---- 開桌 ----

    public SessionSummary open(long tableId, Integer guestCount) {
        int guests = requireGuestCount(guestCount);
        return tx.execute(status -> {
            DiningTable table = tables.lockLiveById(tableId)
                    .orElseThrow(() -> new ApiException(ErrorCode.TABLE_NOT_FOUND));
            if (sessions.findOccupying(tableId).isPresent()) {
                throw new ApiException(ErrorCode.TABLE_OCCUPIED);
            }
            Instant now = time.now();
            LocalDate today = time.businessDateOf(now);
            int billNo = sequences.nextBillNo(today);
            int qrHours = settings.getPositiveInt(Setting.QR_VALIDITY_HOURS);
            TableSession session = sessions.saveAndFlush(new TableSession(tableId, today, billNo, guests,
                    newToken(), now.plus(Duration.ofHours(qrHours)),
                    settings.getBoolean(Setting.SERVICE_CHARGE_DEFAULT_ENABLED), now));
            events.publishEvent(new SessionStartedEvent(session.getId()));
            return SessionSummary.of(session, table.getTableNo(), now);
        });
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static int requireGuestCount(Integer count) {
        if (count == null || count < 1 || count > 99) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return count;
    }

    // ---- 查詢 ----

    @Transactional(readOnly = true)
    public SessionSummary summary(long sessionId) {
        TableSession s = sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
        return toSummary(s, time.now());
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> pendingRecheckout() {
        Instant now = time.now();
        return sessions.findPendingRecheckout().stream().map(s -> toSummary(s, now)).toList();
    }

    private SessionSummary toSummary(TableSession s, Instant now) {
        String tableNo = tables.findById(s.getTableId()).map(DiningTable::getTableNo).orElse("");
        return SessionSummary.of(s, tableNo, now);
    }

    /** QR 圖片：網址＝公開基底網址（去結尾斜線）＋訂單路徑＋用餐紀錄編號＋「#t=」token。只有使用中可取得。 */
    @Transactional(readOnly = true)
    public byte[] qrImage(long sessionId) {
        TableSession s = sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
        if (!s.isOpen()) {
            throw new ApiException(ErrorCode.SESSION_NOT_OPEN);
        }
        return qr.png(qrUrl(s));
    }

    /** 顧客點餐網址（含 token）。機密：只給 QR 圖片與 QR 列印單使用，不寫入日誌。 */
    String qrUrl(TableSession s) {
        String base = properties.baseUrl() == null ? "" : properties.baseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/customer/order/" + s.getId() + "#t=" + s.getToken();
    }

    // ---- 修改人數與服務費 ----

    public SessionSummary updateGuestCount(long sessionId, Integer guestCount) {
        int guests = requireGuestCount(guestCount);
        return tx.execute(status -> {
            TableSession s = lockEditableEntity(sessionId);
            s.changeGuestCount(guests);
            sessions.saveAndFlush(s);
            return toSummary(s, time.now());
        });
    }

    /** 服務費開關：需 PIN 並寫稽核；先檢查狀態、之後才驗 PIN（避免用錯誤 PIN 探測資料狀態）。 */
    public SessionSummary setServiceCharge(long sessionId, Boolean enabled, String pin) {
        if (enabled == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return tx.execute(status -> {
            TableSession s = lockEditableEntity(sessionId);
            VerifiedEmployee who = pins.verify(pin);
            boolean before = s.isServiceChargeEnabled();
            s.changeServiceCharge(enabled);
            sessions.saveAndFlush(s);
            if (before != enabled) {
                audit.record(AuditEntry.of("SERVICE_CHARGE_CHANGED", "table_session", sessionId)
                        .withChange(before, enabled).byEmployee(who.id()));
            }
            return toSummary(s, time.now());
        });
    }

    /** 鎖定用餐紀錄並檢查「使用中且非結帳中、非待重新結帳」。 */
    private TableSession lockEditableEntity(long sessionId) {
        TableSession s = sessions.lockById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
        requireEditable(s);
        return s;
    }

    private static void requireEditable(TableSession s) {
        if (!s.isOpen()) {
            throw new ApiException(ErrorCode.SESSION_NOT_OPEN);
        }
        if (s.isPendingRecheckout()) {
            throw new ApiException(ErrorCode.SESSION_PENDING_RECHECKOUT);
        }
        if (s.isCheckoutInProgress()) {
            throw new ApiException(ErrorCode.CHECKOUT_IN_PROGRESS);
        }
    }

    // ---- 供其他模組使用（呼叫者必須已在交易中；以下方法都要求既有交易） ----

    /** 依編號查詢並鎖定用餐紀錄。不存在回 SESSION_NOT_FOUND。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView lockById(long sessionId) {
        return SessionView.of(sessions.lockById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND)));
    }

    /** 鎖定並要求可編輯：依序檢查使用中、非待重新結帳、非結帳中。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView lockEditable(long sessionId) {
        return SessionView.of(lockEditableEntity(sessionId));
    }

    /** 結束用餐（結帳完成）：釋放桌位。呼叫者先持有該用餐紀錄的鎖。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void close(long sessionId) {
        TableSession s = requireOpenForLifecycle(sessionId);
        s.close(time.now());
        sessions.saveAndFlush(s);
    }

    /** 取消用餐（取消開桌、結束不收費）：釋放桌位、帳單號不回收，並發佈「用餐取消」。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(long sessionId) {
        TableSession s = requireOpenForLifecycle(sessionId);
        s.cancel(time.now());
        sessions.saveAndFlush(s);
        events.publishEvent(new SessionCancelledEvent(sessionId));
    }

    /**
     * 重開（作廢結帳後）：原桌空著 → 占桌重開；原桌已被使用或已刪除 → 不占桌重開並標記「待重新結帳」。
     * 鎖順序：用餐紀錄（呼叫者已鎖）→ 桌位。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SessionView reopen(long sessionId) {
        TableSession s = sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
        if (s.getStatus() != SessionStatus.CLOSED) {
            throw new ApiException(ErrorCode.SESSION_NOT_OPEN);
        }
        boolean tableFree = tables.lockLiveById(s.getTableId()).isPresent()
                && sessions.findOccupying(s.getTableId()).isEmpty();
        if (tableFree) {
            s.reopenOccupying();
        } else {
            s.reopenWithoutTable();
        }
        return SessionView.of(sessions.saveAndFlush(s));
    }

    /** 結帳旗標：開始結帳時為真，放棄或完成時為假。結帳中訂單、人數、服務費都被鎖住。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setCheckoutInProgress(long sessionId, boolean inProgress) {
        TableSession s = sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
        s.setCheckoutInProgress(inProgress);
        sessions.saveAndFlush(s);
    }

    private TableSession requireOpenForLifecycle(long sessionId) {
        TableSession s = sessions.findById(sessionId).orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));
        if (!s.isOpen()) {
            throw new ApiException(ErrorCode.SESSION_NOT_OPEN);
        }
        return s;
    }
}
