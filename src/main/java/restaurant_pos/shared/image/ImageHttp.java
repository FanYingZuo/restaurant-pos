package restaurant_pos.shared.image;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;

/** 圖片回應標頭（§4.10 第 6 條）：禁止猜測內容類型，並可快取（圖片鍵含隨機值，內容不會就地變動）。 */
public final class ImageHttp {

    private ImageHttp() {
    }

    public static HttpHeaders headers(String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
        return headers;
    }
}
