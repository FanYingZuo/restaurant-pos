package restaurant_pos.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.staff.device.FirstStartPairing;
import restaurant_pos.staff.device.PairingService;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 0.11：配對 API（豁免裝置憑證）、配對防暴力、首次啟動印配對碼（01-PLAN §4.5）。 */
class PairingTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FirstStartPairing firstStart;

    private PairingService.Issued newDevice(DeviceType type) {
        return pairingService.createDevice("新平板", type);
    }

    private static RequestPostProcessor from(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }

    private MvcResult pair(String code, String remoteAddr) throws Exception {
        return mvc.perform(post("/api/pairing").with(from(remoteAddr)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pairingCode\":\"" + code + "\"}")).andReturn();
    }

    private MvcResult pair(String code) throws Exception {
        return pair(code, "127.0.0.1");
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---- 配對成功與憑證只回一次 ----

    @Test
    void 配對成功取得憑證_且憑證可呼叫對應類型的API() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.KDS);
        MvcResult r = pair(issued.pairingCode());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("deviceId").asLong()).isEqualTo(issued.device().getId());
        assertThat(body.get("deviceType").asText()).isEqualTo("KDS");
        assertThat(body.get("name").asText()).isEqualTo("新平板");
        String token = body.get("deviceToken").asText();
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        mvc.perform(get("/api/kds/test-ping").header("X-Device-Token", token)).andExpect(status().isOk());
    }

    @Test
    void 配對碼是8位英數字() {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        assertThat(issued.pairingCode()).matches("^[A-Z0-9]{8}$");
    }

    @Test
    void 配對碼一次性_第二次使用同一個配對碼失敗() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        assertThat(pair(issued.pairingCode()).getResponse().getStatus()).isEqualTo(200);
        MvcResult second = pair(issued.pairingCode());
        assertThat(second.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(second).get("code").asText()).isEqualTo("PAIRING_CODE_INVALID");
        assertThat(jdbc.queryForObject("SELECT pairing_code_hash FROM device WHERE id = ?", String.class,
                issued.device().getId())).isNull();
    }

    @Test
    void 每次配對的憑證不同() throws Exception {
        String a = json(pair(newDevice(DeviceType.POS).pairingCode())).get("deviceToken").asText();
        String b = json(pair(newDevice(DeviceType.POS).pairingCode())).get("deviceToken").asText();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 資料庫只存雜湊_不存配對碼與憑證明碼() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        String codeHashBefore = jdbc.queryForObject("SELECT pairing_code_hash FROM device WHERE id = ?", String.class,
                issued.device().getId());
        assertThat(codeHashBefore).hasSize(64).doesNotContain(issued.pairingCode());
        String token = json(pair(issued.pairingCode())).get("deviceToken").asText();
        String row = jdbc.queryForMap("SELECT * FROM device WHERE id = ?", issued.device().getId()).values().toString();
        assertThat(row).doesNotContain(token).doesNotContain(issued.pairingCode());
        assertThat(jdbc.queryForObject("SELECT credential_hash FROM device WHERE id = ?", String.class,
                issued.device().getId())).isEqualTo(hasher.deviceCredential(token));
    }

    // ---- 10 分鐘有效 ----

    @Test
    void 配對碼10分鐘內有效_超過失效() throws Exception {
        PairingService.Issued fresh = newDevice(DeviceType.POS);
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(pair(fresh.pairingCode()).getResponse().getStatus()).isEqualTo(200);

        PairingService.Issued late = newDevice(DeviceType.POS);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        MvcResult r = pair(late.pairingCode());
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(r).get("code").asText()).isEqualTo("PAIRING_CODE_INVALID");
    }

    @Test
    void 有效期由設定決定() throws Exception {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('device.pairing.minutes', '2', NOW(6))");
        PairingService.Issued issued = newDevice(DeviceType.POS);
        clock.advance(Duration.ofMinutes(3));
        assertThat(pair(issued.pairingCode()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 重新產生配對碼_舊碼立即失效() throws Exception {
        PairingService.Issued first = newDevice(DeviceType.POS);
        PairingService.Issued second = pairingService.regenerateCode(first.device());
        assertThat(pair(first.pairingCode()).getResponse().getStatus()).isEqualTo(403);
        assertThat(pair(second.pairingCode()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 已撤銷裝置的配對碼不可用() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        jdbc.update("UPDATE device SET revoked_at = NOW(6) WHERE id = ?", issued.device().getId());
        assertThat(pair(issued.pairingCode()).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 不存在_過期_已使用的錯誤回應相同_不洩漏差異() throws Exception {
        PairingService.Issued used = newDevice(DeviceType.POS);
        pair(used.pairingCode());
        PairingService.Issued expired = newDevice(DeviceType.POS);
        clock.advance(Duration.ofMinutes(11));
        JsonNode a = json(pair("ZZZZZZZZ", "192.0.2.1"));
        JsonNode b = json(pair(expired.pairingCode(), "192.0.2.2"));
        JsonNode c = json(pair(used.pairingCode(), "192.0.2.3"));
        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    void 配對碼格式不合法回VALIDATION_ERROR_且不算失敗次數() throws Exception {
        for (String bad : new String[] {"", "ABC", "ABCDEFG", "ABCDEFGHI", "ABCD-FGH", "ABCD FGH"}) {
            MvcResult r = pair(bad, "192.0.2.9");
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(json(r).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        mvc.perform(post("/api/pairing").with(from("192.0.2.9")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // 格式錯誤不計入失敗：之後的正確配對仍可
        PairingService.Issued issued = newDevice(DeviceType.POS);
        assertThat(pair(issued.pairingCode(), "192.0.2.9").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- 防暴力：同 IP 10 分鐘內錯 10 次鎖 10 分鐘 ----

    @Test
    void 同IP錯十次後鎖定_連正確配對碼也被拒() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        for (int i = 0; i < 10; i++) {
            MvcResult r = pair("WRONG" + String.format("%03d", i));
            assertThat(r.getResponse().getStatus()).as("第 " + (i + 1) + " 次").isEqualTo(403);
        }
        MvcResult locked = pair(issued.pairingCode());
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(json(locked).get("code").asText()).isEqualTo("PAIRING_LOCKED");
    }

    @Test
    void 鎖定10分鐘後解除() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        for (int i = 0; i < 10; i++) {
            pair("WRONG" + String.format("%03d", i));
        }
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(pair(issued.pairingCode()).getResponse().getStatus()).isEqualTo(429);
        clock.advance(Duration.ofSeconds(2));
        // 配對碼本身 10 分鐘已到期，所以改用新的
        PairingService.Issued fresh = newDevice(DeviceType.POS);
        assertThat(pair(fresh.pairingCode()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 鎖定只影響該IP_其他IP不受影響() throws Exception {
        for (int i = 0; i < 10; i++) {
            pair("WRONG" + String.format("%03d", i), "192.0.2.50");
        }
        assertThat(pair("WRONGXXX", "192.0.2.50").getResponse().getStatus()).isEqualTo(429);
        PairingService.Issued issued = newDevice(DeviceType.POS);
        assertThat(pair(issued.pairingCode(), "192.0.2.51").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 超過10分鐘視窗的失敗不累計() throws Exception {
        for (int i = 0; i < 9; i++) {
            pair("WRONG" + String.format("%03d", i));
        }
        clock.advance(Duration.ofMinutes(11));
        for (int i = 0; i < 9; i++) {
            pair("OTHER" + String.format("%03d", i));
        }
        PairingService.Issued issued = newDevice(DeviceType.POS);
        assertThat(pair(issued.pairingCode()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 配對成功不會清除失敗計數() throws Exception {
        for (int i = 0; i < 9; i++) {
            pair("WRONG" + String.format("%03d", i));
        }
        PairingService.Issued issued = newDevice(DeviceType.POS);
        assertThat(pair(issued.pairingCode()).getResponse().getStatus()).isEqualTo(200);
        assertThat(pair("WRONGAAA").getResponse().getStatus()).isEqualTo(403); // 第 10 次失敗
        assertThat(pair("WRONGBBB").getResponse().getStatus()).isEqualTo(429); // 已鎖定
    }

    @Test
    void 非信任來源偽造XForwardedFor無法繞過鎖定() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/pairing").with(from("127.0.0.1")).header("X-Forwarded-For", "203.0.113." + i)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"pairingCode\":\"WRONG" + String.format("%03d", i) + "\"}"));
        }
        MvcResult r = mvc.perform(post("/api/pairing").with(from("127.0.0.1")).header("X-Forwarded-For", "203.0.113.200")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pairingCode\":\"WRONGZZZ\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void 信任代理轉送的來源IP分別計算() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/pairing").with(from("10.9.9.9")).header("X-Forwarded-For", "198.51.100.1")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"pairingCode\":\"WRONG" + String.format("%03d", i) + "\"}"));
        }
        MvcResult blocked = mvc.perform(post("/api/pairing").with(from("10.9.9.9")).header("X-Forwarded-For", "198.51.100.1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pairingCode\":\"WRONGZZZ\"}")).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        PairingService.Issued issued = newDevice(DeviceType.POS);
        MvcResult other = mvc.perform(post("/api/pairing").with(from("10.9.9.9")).header("X-Forwarded-For", "198.51.100.2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pairingCode\":\"" + issued.pairingCode() + "\"}")).andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(200);
    }

    // ---- 稽核、日誌、併發 ----

    @Test
    void 配對寫稽核() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        pair(issued.pairingCode());
        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log ORDER BY id", String.class);
        assertThat(actions).containsExactly("DEVICE_CREATED", "DEVICE_PAIRED");
        assertThat(jdbc.queryForObject("SELECT actor_device_id FROM audit_log WHERE action = 'DEVICE_PAIRED'", Long.class))
                .isEqualTo(issued.device().getId());
    }

    @Test
    void 配對碼與憑證不出現在任何日誌() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            PairingService.Issued issued = newDevice(DeviceType.POS);
            pair("WRONGAAA");
            String token = json(pair(issued.pairingCode())).get("deviceToken").asText();
            for (ILoggingEvent event : appender.list) {
                String text = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
                assertThat(text).doesNotContain(issued.pairingCode()).doesNotContain(token).doesNotContain("WRONGAAA");
            }
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void 同時使用同一個配對碼只有一個成功() throws Exception {
        PairingService.Issued issued = newDevice(DeviceType.POS);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String ip = "192.0.2." + (100 + i);
            Callable<Integer> task = () -> {
                start.await();
                return pair(issued.pairingCode(), ip).getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE credential_hash IS NOT NULL", Integer.class))
                .isEqualTo(1);
    }

    // ---- 首次啟動 ----

    private String runFirstStartCapturingOutput() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            firstStart.run(null);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String codeFrom(String output) {
        Matcher m = Pattern.compile("：([A-Z0-9]{8})").matcher(output);
        assertThat(m.find()).as("主控台輸出：" + output).isTrue();
        return m.group(1);
    }

    @Test
    void 沒有任何裝置時建立首台POS並只在主控台印一次配對碼() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        String output;
        try {
            output = runFirstStartCapturingOutput();
        } finally {
            root.detachAppender(appender);
        }
        String code = codeFrom(output);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT device_type FROM device", String.class)).isEqualTo("POS");
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(code));

        MvcResult r = pair(code);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).get("deviceType").asText()).isEqualTo("POS");
    }

    @Test
    void 已有可用裝置時不再建立也不再印配對碼() {
        registerDevice(DeviceType.POS);
        String output = runFirstStartCapturingOutput();
        assertThat(output).doesNotContain("配對碼");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Integer.class)).isEqualTo(1);
    }

    @Test
    void 首台POS還沒配對且配對碼過期時重新產生配對碼給同一台() throws Exception {
        String firstCode = codeFrom(runFirstStartCapturingOutput());
        clock.advance(Duration.ofMinutes(30));
        String secondCode = codeFrom(runFirstStartCapturingOutput());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Integer.class)).isEqualTo(1);
        assertThat(secondCode).isNotEqualTo(firstCode);
        assertThat(pair(firstCode, "192.0.2.77").getResponse().getStatus()).isEqualTo(403);
        assertThat(pair(secondCode, "192.0.2.78").getResponse().getStatus()).isEqualTo(200);
    }
}
