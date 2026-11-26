package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 0.7：Spring Security 基本設定與安全標頭（01-PLAN §4.5「HTTP 與環境」）。 */
class SecurityBasicsTest extends AbstractIntegrationTest {

    @Test
    void 四個安全標頭都在() throws Exception {
        MvcResult result = mvc.perform(get("/api/test/errors/param").param("n", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).startsWith("default-src 'none'");
    }

    @Test
    void 錯誤回應也帶安全標頭() throws Exception {
        mvc.perform(get("/api/test/errors/api").param("code", "NOT_FOUND"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void 只放行api路徑_其他路徑一律回NOT_FOUND() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/actuator/health", "/h2-console", "/admin", "/swaggerx"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void 其他路徑的POST也回NOT_FOUND() throws Exception {
        mvc.perform(post("/anything").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 關閉CSRF_POST不需要CSRF權杖() throws Exception {
        mvc.perform(post("/api/test/errors/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 無狀態_不建立Session也不送Cookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/test/errors/param").param("n", "1")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void 不啟用表單登入與基本驗證() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isNotFound());
        mvc.perform(get("/api/test/errors/param").param("n", "1").header("Authorization", "Basic dXNlcjpwdw=="))
                .andExpect(status().isOk());
    }

    @Test
    void 開發環境Swagger頁面放寬CSP讓頁面可載入() throws Exception {
        MvcResult result = mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'self'");
    }
}
