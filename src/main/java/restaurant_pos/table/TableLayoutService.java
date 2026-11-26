package restaurant_pos.table;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.table.TableDtos.SessionSummary;
import restaurant_pos.table.TableDtos.TableLayoutDto;
import restaurant_pos.table.TableDtos.TableViewDto;

/**
 * 桌位版面（TABLE_LAYOUT，S4）：桌號 1～20 字（不分大小寫、去空白後唯一）、容量 1～50、寬高 40～400、座標 0～5000、
 * 區域 0～32 字；方形與圓形高度必須等於寬度（送了不同寬高回 VALIDATION_ERROR，不自動修正）；
 * 使用中不可編輯或刪除；列表依桌號自然排序（A2 在 A10 前）。
 * 交易內第一個動作就是鎖桌位列（與開桌同一把鎖）。
 */
@Service
public class TableLayoutService {

    /** 版面欄位（建立與修改共用的驗證與合併對象）。 */
    public record Layout(String tableNo, Integer capacity, TableShape shape, Integer width, Integer height,
            Integer x, Integer y, String area) {
    }

    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final BusinessDayService time;
    private final TransactionTemplate tx;

    public TableLayoutService(DiningTableRepository tables, TableSessionRepository sessions, BusinessDayService time,
            TransactionTemplate tx) {
        this.tables = tables;
        this.sessions = sessions;
        this.time = time;
        this.tx = tx;
    }

    /** 桌位看板：依桌號自然排序；每桌帶目前占桌的使用中用餐紀錄摘要（無則為 null）。 */
    @Transactional(readOnly = true)
    public List<TableViewDto> list() {
        List<DiningTable> live = tables.findAllLive().stream()
                .sorted((a, b) -> NaturalOrder.INSTANCE.compare(a.getTableNo(), b.getTableNo())).toList();
        Map<Long, TableSession> occupying = live.isEmpty() ? Map.of()
                : sessions.findOccupyingIn(live.stream().map(DiningTable::getId).toList()).stream()
                        .collect(Collectors.toMap(TableSession::getTableId, Function.identity()));
        var now = time.now();
        return live.stream().map(t -> {
            TableSession s = occupying.get(t.getId());
            return new TableViewDto(t.getId(), t.getTableNo(), t.getCapacity(), t.getShape(), t.getWidth(),
                    t.getHeight(), t.getX(), t.getY(), t.getArea(), s != null,
                    s == null ? null : SessionSummary.of(s, t.getTableNo(), now));
        }).toList();
    }

    public TableLayoutDto create(Layout request) {
        Layout valid = validate(request, null);
        return tx.execute(status -> {
            requireUniqueNo(valid.tableNo(), null);
            DiningTable saved = tables.saveAndFlush(new DiningTable(valid.tableNo(), valid.capacity(), valid.shape(),
                    valid.width(), valid.height(), valid.x(), valid.y(), valid.area(), time.now()));
            return TableLayoutDto.of(saved);
        });
    }

    public TableLayoutDto update(long id, Layout patch) {
        if (patch.tableNo() == null && patch.capacity() == null && patch.shape() == null && patch.width() == null
                && patch.height() == null && patch.x() == null && patch.y() == null && patch.area() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return tx.execute(status -> {
            DiningTable table = tables.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.TABLE_NOT_FOUND));
            Layout merged = validate(new Layout(
                    patch.tableNo() != null ? patch.tableNo() : table.getTableNo(),
                    patch.capacity() != null ? patch.capacity() : table.getCapacity(),
                    patch.shape() != null ? patch.shape() : table.getShape(),
                    patch.width() != null ? patch.width() : table.getWidth(),
                    patch.height() != null ? patch.height() : table.getHeight(),
                    patch.x() != null ? patch.x() : table.getX(),
                    patch.y() != null ? patch.y() : table.getY(),
                    patch.area() != null ? patch.area() : table.getArea()), id);
            if (sessions.findOccupying(id).isPresent()) {
                throw new ApiException(ErrorCode.TABLE_OCCUPIED);
            }
            requireUniqueNo(merged.tableNo(), id);
            table.update(merged.tableNo(), merged.capacity(), merged.shape(), merged.width(), merged.height(),
                    merged.x(), merged.y(), merged.area());
            return TableLayoutDto.of(tables.saveAndFlush(table));
        });
    }

    public void delete(long id) {
        tx.executeWithoutResult(status -> {
            DiningTable table = tables.lockLiveById(id).orElseThrow(() -> new ApiException(ErrorCode.TABLE_NOT_FOUND));
            if (sessions.findOccupying(id).isPresent()) {
                throw new ApiException(ErrorCode.TABLE_OCCUPIED);
            }
            table.softDelete(time.now());
            tables.saveAndFlush(table);
        });
    }

    // ---- 驗證 ----

    private static Layout validate(Layout r, Long selfId) {
        String no = r.tableNo() == null ? "" : r.tableNo().trim();
        if (no.isEmpty() || no.length() > 20 || r.capacity() == null || r.shape() == null || r.width() == null
                || r.height() == null || r.x() == null || r.y() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        String area = r.area() == null ? "" : r.area().trim();
        if (area.length() > 32 || r.capacity() < 1 || r.capacity() > 50 || r.width() < 40 || r.width() > 400
                || r.height() < 40 || r.height() > 400 || r.x() < 0 || r.x() > 5000 || r.y() < 0 || r.y() > 5000) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (r.shape().requiresEqualSides() && !r.width().equals(r.height())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return new Layout(no, r.capacity(), r.shape(), r.width(), r.height(), r.x(), r.y(), area);
    }

    /** 桌號不分大小寫、去空白後唯一（只比對未刪除的桌位）。 */
    private void requireUniqueNo(String tableNo, Long selfId) {
        String key = tableNo.trim().toLowerCase(Locale.ROOT);
        boolean duplicate = tables.findAllLive().stream()
                .anyMatch(t -> !t.getId().equals(selfId) && t.getTableNo().trim().toLowerCase(Locale.ROOT).equals(key));
        if (duplicate) {
            throw new ApiException(ErrorCode.TABLE_NO_DUPLICATE);
        }
    }
}
