package restaurant_pos.staff.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import restaurant_pos.shared.security.DeviceType;

/**
 * 裝置（device）。憑證只存 HMAC 雜湊；撤銷為軟刪除。
 * toString 不含任何雜湊或配對資料（03-BOUNDARIES §3.3）。
 */
@Entity
@Table(name = "device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 10)
    private DeviceType deviceType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "credential_hash", length = 64)
    private String credentialHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "pairing_code_hash", length = 64)
    private String pairingCodeHash;

    @Column(name = "pairing_expires_at")
    private Instant pairingExpiresAt;

    protected Device() {
    }

    public Device(DeviceType deviceType, String name, Instant createdAt) {
        this.deviceType = deviceType;
        this.name = name;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getPairingExpiresAt() {
        return pairingExpiresAt;
    }

    /** 是否已完成配對（已有憑證）。 */
    public boolean isPaired() {
        return credentialHash != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    String getCredentialHash() {
        return credentialHash;
    }

    String getPairingCodeHash() {
        return pairingCodeHash;
    }

    /** 設定新的配對碼雜湊與到期時間（舊配對碼立即失效）。 */
    public void issuePairingCode(String codeHash, Instant expiresAt) {
        this.pairingCodeHash = codeHash;
        this.pairingExpiresAt = expiresAt;
    }

    /** 配對成功：寫入憑證雜湊，配對碼立即失效。 */
    public void completePairing(String credentialHash) {
        this.credentialHash = credentialHash;
        this.pairingCodeHash = null;
        this.pairingExpiresAt = null;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
        this.pairingCodeHash = null;
        this.pairingExpiresAt = null;
    }

    @Override
    public String toString() {
        return "Device[id=" + id + ", type=" + deviceType + ", revoked=" + isRevoked() + "]";
    }
}
