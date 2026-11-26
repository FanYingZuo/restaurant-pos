package restaurant_pos.table;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.shared.security.UnlockFunction;
import restaurant_pos.support.AbstractIntegrationTest;
import restaurant_pos.support.TableEventProbe;

/** 桌位測試的共用輔助：已配對 POS、版面解鎖、建桌與開桌。 */
abstract class TableTestBase extends AbstractIntegrationTest {

    @Autowired
    protected TableEventProbe events;

    protected TestDevice pos;
    protected String layoutToken;

    @BeforeEach
    void setUpTables() {
        events.clear();
        pos = registerDevice(DeviceType.POS);
        layoutToken = unlock(pos, UnlockFunction.TABLE_LAYOUT);
    }

    protected MvcResult call(MockHttpServletRequestBuilder request, String unlockToken, String body) throws Exception {
        request = request.header("X-Device-Token", pos.token());
        if (unlockToken != null) {
            request = request.header("X-Unlock-Token", unlockToken);
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    protected MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, null, body);
    }

    /** 版面編輯用（帶 TABLE_LAYOUT 解鎖）。 */
    protected MvcResult layout(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, layoutToken, body);
    }

    protected JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    protected String errorCode(MvcResult r) throws Exception {
        return body(r).get("code").asText();
    }

    /** 建立方形桌（64×64）並回傳編號。 */
    protected long table(String tableNo) throws Exception {
        MvcResult r = layout(post("/api/pos/tables"), "{\"tableNo\":\"" + tableNo
                + "\",\"capacity\":4,\"shape\":\"SQUARE\",\"width\":64,\"height\":64,\"x\":10,\"y\":20}");
        return body(r).get("id").asLong();
    }

    protected JsonNode open(long tableId, int guests) throws Exception {
        MvcResult r = call(post("/api/pos/tables/" + tableId + "/open"), "{\"guestCount\":" + guests + "}");
        return body(r);
    }

    protected JsonNode tableList() throws Exception {
        return body(call(get("/api/pos/tables"), null));
    }
}
