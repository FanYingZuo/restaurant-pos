package restaurant_pos.table;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 帳單號流水（§4.2、S4）：每營業日從 1 開始；悲觀鎖（命中主鍵）遞增；取消不回收。
 * 鎖順序：桌位 → 每日帳單號流水（§4.6），呼叫者必須已在交易中且先鎖了桌位。
 */
@Service
public class DailySequenceService {

    private final DailySequenceRepository repository;
    private final DailySequenceInitializer initializer;

    public DailySequenceService(DailySequenceRepository repository, DailySequenceInitializer initializer) {
        this.repository = repository;
        this.initializer = initializer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int nextBillNo(LocalDate businessDate) {
        try {
            initializer.ensure(businessDate);
        } catch (RuntimeException alreadyCreatedByAnotherRequest) {
            // 另一個請求剛好同時建立了同一列：略過，下面直接鎖定它
        }
        DailySequence sequence = repository.lockByDate(businessDate)
                .orElseThrow(() -> new IllegalStateException("帳單號流水列不存在"));
        int next = sequence.next();
        repository.saveAndFlush(sequence);
        return next;
    }
}
