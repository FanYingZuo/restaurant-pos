package restaurant_pos.staff.device;

import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** 只找得到「未撤銷」的裝置；不存在與已撤銷同樣查不到。 */
    @Query("SELECT d FROM Device d WHERE d.credentialHash = :hash AND d.revokedAt IS NULL")
    Optional<Device> findActiveByCredentialHash(@Param("hash") String hash);

    @Query("SELECT d FROM Device d WHERE d.pairingCodeHash = :hash AND d.revokedAt IS NULL")
    Optional<Device> findActiveByPairingCodeHash(@Param("hash") String hash);

    /** 配對時以唯一索引（配對碼雜湊）悲觀鎖定，確保同一配對碼同時只有一個請求成功。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Device d WHERE d.pairingCodeHash = :hash AND d.revokedAt IS NULL")
    Optional<Device> lockByPairingCodeHash(@Param("hash") String hash);

    /** 依編號查詢並鎖定（命中主鍵）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Device d WHERE d.id = :id")
    Optional<Device> lockById(@Param("id") Long id);

    long countByRevokedAtIsNull();

    long count();

    /** 以分鐘為單位更新最後使用時間：只在比目前記錄更新（以分鐘計）時才寫入。 */
    @Modifying
    @Query("UPDATE Device d SET d.lastUsedAt = :minute WHERE d.id = :id "
            + "AND (d.lastUsedAt IS NULL OR d.lastUsedAt < :minute)")
    int touch(@Param("id") Long id, @Param("minute") Instant minute);
}
