package restaurant_pos.shared.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 環境設定（docs/plan/01-PLAN.md §4.9「環境設定」）。值一律來自環境變數；本類別不得輸出機密欄位。
 */
@ConfigurationProperties(prefix = "restaurantpos")
public record PosProperties(
        String baseUrl,
        List<String> trustedProxies,
        String pinSecret,
        String firstManagerPin,
        String imageDir) {

    public PosProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }

    /** 機密欄位不得出現在 toString（03-BOUNDARIES §3.3）。 */
    @Override
    public String toString() {
        return "PosProperties[baseUrl=" + baseUrl
                + ", trustedProxies=" + trustedProxies
                + ", pinSecret=" + (isBlank(pinSecret) ? "無" : "有")
                + ", firstManagerPin=" + (isBlank(firstManagerPin) ? "無" : "有")
                + ", imageDir=" + imageDir + "]";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
