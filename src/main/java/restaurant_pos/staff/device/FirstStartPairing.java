package restaurant_pos.staff.device;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.security.DeviceType;

/**
 * 首次啟動（§4.5 配對流程第 7 點）：沒有任何可用裝置時，自動建立一台 POS 並把配對碼**只印在主控台**。
 * 配對碼不寫入任何日誌（只用標準輸出，不經 logger）。
 *
 * <p>「可用」＝已配對且未撤銷。若上次的首台 POS 還沒配對（例如配對碼過期），重新啟動時重新產生配對碼給同一台，
 * 避免操作員錯過配對碼後無法進入系統（規格寫「沒有任何裝置」，這裡略為放寬，見 04-LOG）。
 */
@Component
public class FirstStartPairing implements ApplicationRunner {

    public static final String FIRST_DEVICE_NAME = "首台 POS";
    private static final Logger log = LoggerFactory.getLogger(FirstStartPairing.class);

    private final DeviceRepository devices;
    private final PairingService pairing;
    private final TransactionTemplate tx;

    public FirstStartPairing(DeviceRepository devices, PairingService pairing, TransactionTemplate tx) {
        this.devices = devices;
        this.pairing = pairing;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        PairingService.Issued issued = tx.execute(status -> {
            boolean hasUsable = devices.findAll().stream().anyMatch(d -> d.isPaired() && !d.isRevoked());
            if (hasUsable) {
                return null;
            }
            List<Device> unpaired = devices.findAll().stream()
                    .filter(d -> !d.isPaired() && !d.isRevoked() && FIRST_DEVICE_NAME.equals(d.getName())).toList();
            return unpaired.isEmpty()
                    ? pairing.createDevice(FIRST_DEVICE_NAME, DeviceType.POS)
                    : pairing.regenerateCode(unpaired.get(0));
        });
        if (issued == null) {
            return;
        }
        log.info("沒有可用的裝置，已建立首台 POS（配對碼只顯示在主控台一次）");
        System.out.println("==============================================================");
        System.out.println("首台 POS 配對碼（10 分鐘內有效，只顯示這一次）：" + issued.pairingCode());
        System.out.println("==============================================================");
    }
}
