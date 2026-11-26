package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import restaurant_pos.shared.config.PosProperties;
import restaurant_pos.shared.security.ClientIpResolver;

/** 卡 0.11：來源 IP 只採信「信任代理」送來的 X-Forwarded-For（01-PLAN §4.5）。 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(
            new PosProperties("https://x.test", List.of("10.0.0.1", "10.0.0.2"), null, null, "images"));

    private MockHttpServletRequest request(String remote, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (forwarded != null) {
            request.addHeader("X-Forwarded-For", forwarded);
        }
        return request;
    }

    @Test
    void 非信任來源不採信XForwardedFor() {
        assertThat(resolver.resolve(request("203.0.113.9", "198.51.100.7"))).isEqualTo("203.0.113.9");
    }

    @Test
    void 信任代理來的取轉送的客戶端位址() {
        assertThat(resolver.resolve(request("10.0.0.1", "198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void 從右往左略過信任代理_不採信左邊偽造的值() {
        // 客戶端自己塞了 1.2.3.4；真正的客戶端是 198.51.100.7，經過兩層信任代理
        assertThat(resolver.resolve(request("10.0.0.1", "1.2.3.4, 198.51.100.7, 10.0.0.2")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void 沒有標頭或全是信任代理時用直接來源() {
        assertThat(resolver.resolve(request("10.0.0.1", null))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "10.0.0.2"))).isEqualTo("10.0.0.1");
    }

    @Test
    void 格式不合法的轉送值不採信() {
        assertThat(resolver.resolve(request("10.0.0.1", "not an ip!"))).isEqualTo("10.0.0.1");
    }

    @Test
    void 沒有設定信任代理時永遠用直接來源() {
        ClientIpResolver none = new ClientIpResolver(new PosProperties("https://x.test", null, null, null, "images"));
        assertThat(none.resolve(request("10.0.0.1", "198.51.100.7"))).isEqualTo("10.0.0.1");
    }
}
