package restaurant_pos.table;

import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在「獨立交易」中確保某營業日的流水列存在。
 * 讓每個營業日第一次同時開不同桌時，不會因為兩邊同時新增同一列而其中一邊失敗。
 */
@Component
public class DailySequenceInitializer {

    private final DailySequenceRepository repository;

    public DailySequenceInitializer(DailySequenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensure(LocalDate businessDate) {
        if (!repository.existsById(businessDate)) {
            repository.saveAndFlush(new DailySequence(businessDate));
        }
    }
}
