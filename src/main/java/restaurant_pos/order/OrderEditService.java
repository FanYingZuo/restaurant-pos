package restaurant_pos.order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.menu.ItemSnapshot;
import restaurant_pos.menu.MenuQueryService;
import restaurant_pos.order.OrderDtos.ItemInput;
import restaurant_pos.order.OrderDtos.OrderItemView;
import restaurant_pos.order.OrderDtos.OrderView;
import restaurant_pos.order.SelectionValidator.Validated;
import restaurant_pos.shared.audit.AuditEntry;
import restaurant_pos.shared.audit.AuditService;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.security.VerifiedEmployee;
import restaurant_pos.table.SessionView;
import restaurant_pos.table.TableDtos.SessionSummary;
import restaurant_pos.table.TableSessionService;

/**
 * 已送出訂單的修改與取消（S5 卡 5.5～5.7）。
 *
 * <p>共同規則：
 * <ul>
 *   <li>鎖順序：用餐紀錄 → 訂單 → 訂單品項；要先查才知道用餐紀錄編號的，先做不鎖查詢，鎖住後重新載入再檢查（§4.6）。</li>
 *   <li>需要 PIN 的操作（取消品項、整單、開桌）：<b>狀態檢查之後才驗 PIN</b>，避免用錯誤 PIN 探測資料狀態；
 *       PIN 與理由的格式先檢查（先格式後業務）。</li>
 *   <li>修改：草稿、結帳中、已取消、已完成、版本不符依序拒絕；製作中的品項修改後保持製作中；
 *       以目前菜單重建快照；寫前後摘要歷史、重算訂單狀態、發佈品項修改。</li>
 * </ul>
 */
@Service
public class OrderEditService {

    private final TableSessionService sessions;
    private final MenuQueryService menu;
    private final DiningOrderRepository orders;
    private final OrderItemRepository items;
    private final OrderItemSupport support;
    private final OrderHistoryRepository histories;
    private final PinVerifier pins;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public OrderEditService(TableSessionService sessions, MenuQueryService menu, DiningOrderRepository orders,
            OrderItemRepository items, OrderItemSupport support, OrderHistoryRepository histories, PinVerifier pins,
            AuditService audit, ApplicationEventPublisher events, TransactionTemplate tx) {
        this.sessions = sessions;
        this.menu = menu;
        this.orders = orders;
        this.items = items;
        this.support = support;
        this.histories = histories;
        this.pins = pins;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
    }

    // ---- 5.5 修改已送出品項 ----

    public OrderItemView modifyItem(long orderItemId, Integer expectedVersion, ItemInput input) {
        if (expectedVersion == null || input == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        SelectionValidator.checkQuantities(input.quantity(), input.modifiers());
        String note = DraftService.cleanNote(input.note());
        OrderItem peek = items.findById(orderItemId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        return tx.execute(status -> {
            SessionView view = sessions.lockById(peek.getSessionId());
            DiningOrder order = orders.lockById(peek.getOrderId())
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_ITEM_NOT_FOUND));
            OrderItem item = items.lockById(orderItemId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_ITEM_NOT_FOUND));

            // 拒絕順序：用餐紀錄使用中 → 待重新結帳 → 草稿 → 結帳中 → 已取消 → 已完成 → 版本不符
            requireOpenAndNotPending(view);
            if (order.isDraft()) {
                throw new ApiException(ErrorCode.ORDER_NOT_EDITABLE);
            }
            if (view.checkoutInProgress()) {
                throw new ApiException(ErrorCode.CHECKOUT_IN_PROGRESS);
            }
            requireModifiable(item);
            if (item.getVersion() != expectedVersion) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }

            ItemSnapshot snapshot = menu.editableItem(item.getMenuItemId());
            Validated v = SelectionValidator.validate(snapshot, input.quantity(), input.modifiers());
            String before = support.summary(item, support.modifiersOf(List.of(item.getId())).getOrDefault(item.getId(), List.of()));
            support.replace(item, snapshot, v, note, true);
            String after = support.summary(item, support.modifiersOf(List.of(item.getId())).getOrDefault(item.getId(), List.of()));

            recalculate(order);
            histories.save(new OrderHistory(order.getSessionId(), order.getId(), item.getId(), "ITEM_MODIFIED", before, after,
                    null, null, support.now()));
            events.publishEvent(new ItemModifiedEvent(item.getId(), order.getSessionId()));
            return support.view(item);
        });
    }

    /** 修改訂單備註：只寫歷史，不發事件；帶訂單版本。 */
    public OrderView updateOrderNote(long orderId, Integer expectedVersion, String note) {
        if (expectedVersion == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String cleanNote = DraftService.cleanNote(note);
        DiningOrder peek = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        return tx.execute(status -> {
            SessionView view = sessions.lockById(peek.getSessionId());
            DiningOrder order = orders.lockById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
            requireOpenAndNotPending(view);
            if (order.isDraft()) {
                throw new ApiException(ErrorCode.ORDER_NOT_EDITABLE);
            }
            if (view.checkoutInProgress()) {
                throw new ApiException(ErrorCode.CHECKOUT_IN_PROGRESS);
            }
            if (order.getVersion() != expectedVersion) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            String before = order.getNote();
            order.changeNote(cleanNote);
            orders.saveAndFlush(order);
            histories.save(new OrderHistory(order.getSessionId(), order.getId(), null, "ORDER_NOTE_CHANGED", before,
                    cleanNote, null, null, support.now()));
            return orderView(order);
        });
    }

    // ---- 5.6 取消品項、取消整單（PIN） ----

    public OrderItemView cancelItem(long orderItemId, String pin, String reason) {
        String cleanReason = checkPinAndReason(pin, reason);
        OrderItem peek = items.findById(orderItemId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        return tx.execute(status -> {
            SessionView view = sessions.lockById(peek.getSessionId());
            DiningOrder order = orders.lockById(peek.getOrderId())
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_ITEM_NOT_FOUND));
            OrderItem item = items.lockById(orderItemId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ORDER_ITEM_NOT_FOUND));

            // 狀態檢查（先於 PIN）
            requireOpen(view);
            if (view.checkoutInProgress()) {
                throw new ApiException(ErrorCode.CHECKOUT_IN_PROGRESS);
            }
            if (order.isDraft()) {
                throw new ApiException(ErrorCode.ORDER_NOT_EDITABLE);
            }
            requireModifiable(item);

            VerifiedEmployee who = pins.verify(pin);
            cancelOne(order, item, cleanReason, who.id());
            recalculate(order);
            return support.view(item);
        });
    }

    public OrderView cancelOrder(long orderId, String pin, String reason) {
        String cleanReason = checkPinAndReason(pin, reason);
        DiningOrder peek = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
        return tx.execute(status -> {
            SessionView view = sessions.lockById(peek.getSessionId());
            DiningOrder order = orders.lockById(orderId).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
            requireOpen(view);
            if (view.checkoutInProgress()) {
                throw new ApiException(ErrorCode.CHECKOUT_IN_PROGRESS);
            }
            if (order.isDraft()) {
                throw new ApiException(ErrorCode.ORDER_NOT_EDITABLE);
            }
            // 依編號遞增鎖定全部品項；有任何已完成品項就不可取消整單
            List<OrderItem> locked = new ArrayList<>();
            for (OrderItem i : items.findByOrder(orderId)) {
                locked.add(items.lockById(i.getId()).orElseThrow());
            }
            if (locked.stream().anyMatch(i -> i.getStatus() == ItemStatus.DONE)) {
                throw new ApiException(ErrorCode.ORDER_ITEM_ALREADY_READY);
            }

            VerifiedEmployee who = pins.verify(pin);
            for (OrderItem i : locked) {
                if (i.getStatus() != ItemStatus.CANCELLED) {
                    cancelOne(order, i, cleanReason, who.id()); // 逐項取消並各自寫歷史、發事件
                }
            }
            recalculate(order);
            return orderView(order);
        });
    }

    // ---- 5.7 取消開桌（PIN） ----

    public SessionSummary cancelSession(long sessionId, String pin, String reason) {
        String cleanReason = checkPinAndReason(pin, reason);
        return tx.execute(status -> {
            SessionView view = sessions.lockById(sessionId);
            requireOpenAndNotPending(view);
            if (view.checkoutInProgress()) {
                throw new ApiException(ErrorCode.CHECKOUT_IN_PROGRESS);
            }
            if (items.countLiveFormal(sessionId) > 0) {
                throw new ApiException(ErrorCode.SESSION_HAS_SUBMITTED_ORDERS);
            }

            VerifiedEmployee who = pins.verify(pin);
            // 草稿實體刪除；用餐紀錄已取消；桌位回空桌
            orders.lockDraft(sessionId).ifPresent(draft -> {
                support.deleteWithModifiers(items.findByOrder(draft.getId()).stream().map(OrderItem::getId).toList());
                orders.delete(draft);
                orders.flush();
            });
            sessions.cancel(sessionId);
            audit.record(AuditEntry.of("SESSION_CANCELLED", "table_session", sessionId).byEmployee(who.id())
                    .withReason(cleanReason));
            return sessions.summary(sessionId);
        });
    }

    // ---- 共用 ----

    private void cancelOne(DiningOrder order, OrderItem item, String reason, long employeeId) {
        String before = support.summary(item, support.modifiersOf(List.of(item.getId())).getOrDefault(item.getId(), List.of()));
        item.cancel(reason, employeeId, support.now());
        items.saveAndFlush(item);
        histories.save(new OrderHistory(order.getSessionId(), order.getId(), item.getId(), "ITEM_CANCELLED", before,
                null, employeeId, reason, support.now()));
        events.publishEvent(new ItemCancelledEvent(item.getId(), order.getSessionId()));
    }

    /** 以所有品項目前的狀態重算訂單狀態並遞增版本。 */
    private void recalculate(DiningOrder order) {
        List<ItemStatus> statuses = items.findByOrder(order.getId()).stream().map(OrderItem::getStatus).toList();
        order.applyStatus(OrderRules.recalculate(statuses));
        orders.saveAndFlush(order);
    }

    private OrderView orderView(DiningOrder order) {
        List<OrderItem> orderItems = items.findByOrder(order.getId());
        Map<Long, List<OrderItemModifier>> mods = support.modifiersOf(orderItems.stream().map(OrderItem::getId).toList());
        List<OrderItemView> views = orderItems.stream()
                .map(i -> OrderItemView.of(i, mods.getOrDefault(i.getId(), List.of()))).toList();
        return new OrderView(order.getId(), order.getBatchNo(), order.getStatus(), order.getSource(), order.getNote(),
                order.getVersion(), order.getSubmittedAt(), views);
    }

    private static void requireOpen(SessionView view) {
        if (view.status() != restaurant_pos.table.SessionStatus.OPEN) {
            throw new ApiException(ErrorCode.SESSION_NOT_OPEN);
        }
    }

    private static void requireOpenAndNotPending(SessionView view) {
        requireOpen(view);
        if (view.pendingRecheckout()) {
            throw new ApiException(ErrorCode.SESSION_PENDING_RECHECKOUT);
        }
    }

    /** 已取消與已完成的品項不可修改或取消。 */
    private static void requireModifiable(OrderItem item) {
        if (item.getStatus() == ItemStatus.CANCELLED) {
            throw new ApiException(ErrorCode.ORDER_ITEM_CANCELLED);
        }
        if (item.getStatus() == ItemStatus.DONE) {
            throw new ApiException(ErrorCode.ORDER_ITEM_ALREADY_READY);
        }
    }

    /** 先格式後業務：PIN 格式與理由（必填，1～255 字）。 */
    private static String checkPinAndReason(String pin, String reason) {
        if (!PinVerifier.isValidFormat(pin)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }
}
