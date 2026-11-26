package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.staff.device.PairingService;
import restaurant_pos.support.AbstractIntegrationTest;

/**
 * S0 驗收（卡 0.12）。完成條件（01-PLAN S0）：全部測試在 MySQL 通過；無裝置憑證時店內 API 回 401；
 * 開發環境 Swagger 可開、可輸入裝置憑證標頭。
 */
class S0AcceptanceTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void 無裝置憑證時店內API回401() throws Exception {
        for (String path : new String[] {"/api/pos/test-ping", "/api/kds/test-ping", "/api/staff/test-ping"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("DEVICE_TOKEN_INVALID"));
        }
    }

    @Test
    void 開發環境Swagger可開且可輸入裝置憑證標頭() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        MvcResult r = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode docs = objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode scheme = docs.at("/components/securitySchemes/DeviceToken");
        assertThat(scheme.get("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.get("in").asText()).isEqualTo("header");
        assertThat(scheme.get("name").asText()).isEqualTo("X-Device-Token");
        assertThat(docs.at("/components/securitySchemes/UnlockToken/name").asText()).isEqualTo("X-Unlock-Token");
        assertThat(docs.at("/components/securitySchemes/SessionToken/name").asText()).isEqualTo("X-Session-Token");
    }

    @Test
    void 配對第一台裝置後即可呼叫店內API() throws Exception {
        PairingService.Issued issued = pairingService.createDevice("驗收用 POS", DeviceType.POS);
        MvcResult paired = mvc.perform(post("/api/pairing").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pairingCode\":\"" + issued.pairingCode() + "\"}")).andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(paired.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("deviceToken").asText();
        mvc.perform(get("/api/pos/test-ping").header("X-Device-Token", token)).andExpect(status().isOk());
    }

    @Test
    void 資料庫是MySQL且遷移到最新() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertThat(failed).isZero();
    }
}
