package restaurant_pos.table;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.shared.security.UnlockGate;
import restaurant_pos.table.TableDtos.SessionSummary;
import restaurant_pos.table.TableDtos.TableLayoutDto;
import restaurant_pos.table.TableDtos.TableViewDto;
import restaurant_pos.table.TableLayoutService.Layout;

/** 桌位 API（合約 listTables、createTable、updateTable、deleteTable、openTable）。版面編輯需 TABLE_LAYOUT 解鎖。 */
@RestController
@RequestMapping("/api/pos/tables")
public class TableController {

    public record LayoutRequest(String tableNo, Integer capacity, TableShape shape, Integer width, Integer height,
            Integer x, Integer y, String area) {
        Layout toLayout() {
            return new Layout(tableNo, capacity, shape, width, height, x, y, area);
        }
    }

    public record OpenRequest(Integer guestCount) {
    }

    private final TableLayoutService layout;
    private final TableSessionService sessions;
    private final UnlockGate unlockGate;

    public TableController(TableLayoutService layout, TableSessionService sessions, UnlockGate unlockGate) {
        this.layout = layout;
        this.sessions = sessions;
        this.unlockGate = unlockGate;
    }

    private void unlocked(String token) {
        unlockGate.require(token, UnlockFunction.TABLE_LAYOUT);
    }

    @GetMapping
    public List<TableViewDto> list() {
        return layout.list();
    }

    @PostMapping
    public ResponseEntity<TableLayoutDto> create(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @RequestBody LayoutRequest request) {
        unlocked(token);
        return ResponseEntity.status(HttpStatus.CREATED).body(layout.create(request.toLayout()));
    }

    @PatchMapping("/{tableId}")
    public TableLayoutDto update(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @PathVariable long tableId, @RequestBody LayoutRequest request) {
        unlocked(token);
        return layout.update(tableId, request.toLayout());
    }

    @DeleteMapping("/{tableId}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "X-Unlock-Token", required = false) String token,
            @PathVariable long tableId) {
        unlocked(token);
        layout.delete(tableId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{tableId}/open")
    public ResponseEntity<SessionSummary> open(@PathVariable long tableId, @RequestBody OpenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessions.open(tableId, request.guestCount()));
    }
}
