package restaurant_pos.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 訂單（orders）。每個用餐紀錄最多一張草稿；送出後轉為正式單（批次號＝該用餐紀錄最大批次＋1）。
 * 「草稿唯一」以 {@code draftSessionId}（只在草稿時有值）的唯一索引保證。
 */
@Entity
@Table(name = "orders")
public class DiningOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "batch_no")
    private Integer batchNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "draft_session_id")
    private Long draftSessionId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSource source;

    @Column(nullable = false, length = 255)
    private String note;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected DiningOrder() {
    }

    /** 建立草稿。 */
    public static DiningOrder newDraft(Long sessionId, Instant now) {
        DiningOrder o = new DiningOrder();
        o.sessionId = sessionId;
        o.status = OrderStatus.DRAFT;
        o.draftSessionId = sessionId;
        o.source = OrderSource.STAFF;
        o.note = "";
        o.version = 0;
        o.createdAt = now;
        return o;
    }

    /** 顧客直接建立的正式單（S8）。 */
    public static DiningOrder newSubmitted(Long sessionId, int batchNo, String requestId, String contentHash,
            OrderSource source, String note, Instant now) {
        DiningOrder o = new DiningOrder();
        o.sessionId = sessionId;
        o.batchNo = batchNo;
        o.status = OrderStatus.SUBMITTED;
        o.requestId = requestId;
        o.contentHash = contentHash;
        o.source = source;
        o.note = note;
        o.version = 0;
        o.createdAt = now;
        o.submittedAt = now;
        return o;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Integer getBatchNo() {
        return batchNo;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public OrderSource getSource() {
        return source;
    }

    public String getNote() {
        return note;
    }

    public int getVersion() {
        return version;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public boolean isDraft() {
        return status == OrderStatus.DRAFT;
    }

    /** 草稿送出：轉為正式單。對外第一次出現的正式單版本為 0（草稿不對外），所以這裡不遞增版本。 */
    public void submit(int batchNo, String requestId, String note, Instant now) {
        this.batchNo = batchNo;
        this.requestId = requestId;
        this.note = note;
        this.status = OrderStatus.SUBMITTED;
        this.draftSessionId = null;
        this.submittedAt = now;
    }

    public void changeNote(String newNote) {
        this.note = newNote;
        this.version++;
    }

    /** 品項變動後重算狀態並遞增版本。 */
    public void applyStatus(OrderStatus newStatus) {
        this.status = newStatus;
        this.version++;
    }
}
