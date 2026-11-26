package restaurant_pos.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import restaurant_pos.shared.config.PosSecrets;

/**
 * PIN 工具（§4.5）：以伺服器密鑰做 HMAC-SHA256，輸出 64 字元十六進位。
 * 共用同一把密鑰，以「用途前綴」做領域分離：{@code pin:}、{@code device:}、{@code pairing:}。
 * 本類別不輸出、不記錄任何輸入值或密鑰。
 */
@Component
public class HmacHasher {

    private final byte[] key;

    public HmacHasher(PosSecrets secrets) {
        this.key = secrets.pinSecret();
    }

    public String pin(String pin) {
        return hash("pin:", pin);
    }

    /** 裝置憑證：{@code HMAC(密鑰, "device:" + 憑證)}。 */
    public String deviceCredential(String credential) {
        return hash("device:", credential);
    }

    /** 解鎖憑證的記憶體索引鍵。 */
    public String unlockToken(String token) {
        return hash("unlock:", token);
    }

    public String pairingCode(String code) {
        return hash("pairing:", code);
    }

    private String hash(String purposePrefix, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purposePrefix + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC 不可用", e);
        }
    }

    /** 常數時間比對（用於記憶體內的字串比對，例如顧客 token）。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "HmacHasher[***]";
    }
}
