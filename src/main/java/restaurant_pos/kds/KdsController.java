package restaurant_pos.kds;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import restaurant_pos.order.KitchenItemService;
import restaurant_pos.order.KitchenQueryService;
import restaurant_pos.order.KitchenQueryService.KitchenItem;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.model.Station;

/**
 * 廚房 API（合約 kds 標籤，/api/kds/**，只限 KDS 裝置）：站別清單、開始製作、完成。
 * 狀態變更直接呼叫訂單模組的品項狀態服務（S5 卡 5.8），本類別不重複實作任何規則。
 * 路徑中的站別不存在 → 404 NOT_FOUND。
 */
@RestController
@RequestMapping("/api/kds/stations")
public class KdsController {

    public record Board(Station station, Instant generatedAt, List<KitchenItem> items) {
    }

    public record TransitionRequest(Integer expectedVersion) {
    }

    private final KitchenQueryService query;
    private final KitchenItemService kitchen;
    private final Clock clock;

    public KdsController(KitchenQueryService query, KitchenItemService kitchen, Clock clock) {
        this.query = query;
        this.kitchen = kitchen;
        this.clock = clock;
    }

    private static Station station(String raw) {
        try {
            return Station.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    @GetMapping("/{station}/items")
    public Board list(@PathVariable String station) {
        Station s = station(station);
        return new Board(s, clock.instant(), query.list(s));
    }

    @PostMapping("/{station}/items/{orderItemId}/start")
    public KitchenItemService.Result start(@PathVariable String station, @PathVariable long orderItemId,
            @RequestBody TransitionRequest request) {
        return kitchen.start(station(station), orderItemId, request.expectedVersion());
    }

    @PostMapping("/{station}/items/{orderItemId}/complete")
    public KitchenItemService.Result complete(@PathVariable String station, @PathVariable long orderItemId,
            @RequestBody TransitionRequest request) {
        return kitchen.complete(station(station), orderItemId, request.expectedVersion());
    }
}
