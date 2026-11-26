package restaurant_pos.menu;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import restaurant_pos.shared.security.DeviceType;
import restaurant_pos.support.AbstractIntegrationTest;

/** 菜單測試的共用輔助：以已配對 POS 呼叫 /api/pos/menu。 */
abstract class MenuTestBase extends AbstractIntegrationTest {

    protected TestDevice pos;

    @BeforeEach
    void registerPos() {
        pos = registerDevice(DeviceType.POS);
    }

    protected MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request = request.header("X-Device-Token", pos.token());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    protected MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, null);
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

    protected JsonNode tree() throws Exception {
        return body(call(get("/api/pos/menu")));
    }

    protected long category(String name) throws Exception {
        MvcResult r = call(post("/api/pos/menu/categories"), "{\"name\":\"" + name + "\"}");
        return body(r).get("id").asLong();
    }

    protected long item(long categoryId, String name, int price, String station, boolean active) throws Exception {
        MvcResult r = call(post("/api/pos/menu/items"), "{\"categoryId\":" + categoryId + ",\"name\":\"" + name
                + "\",\"price\":" + price + ",\"station\":\"" + station + "\",\"active\":" + active + "}");
        return body(r).get("id").asLong();
    }

    protected long group(long itemId, String name, int min, int max, boolean allowDuplicate) throws Exception {
        MvcResult r = call(post("/api/pos/menu/items/" + itemId + "/modifier-groups"), "{\"name\":\"" + name
                + "\",\"minSelect\":" + min + ",\"maxSelect\":" + max + ",\"allowDuplicate\":" + allowDuplicate + "}");
        return body(r).get("id").asLong();
    }

    protected long modifier(long groupId, String name, int priceDelta) throws Exception {
        MvcResult r = call(post("/api/pos/menu/modifier-groups/" + groupId + "/modifiers"),
                "{\"name\":\"" + name + "\",\"priceDelta\":" + priceDelta + "}");
        return body(r).get("id").asLong();
    }

    /** 找出菜單樹中某品項的 JSON。 */
    protected JsonNode findItem(JsonNode tree, long itemId) {
        for (JsonNode c : tree.get("categories")) {
            for (JsonNode i : c.get("items")) {
                if (i.get("id").asLong() == itemId) {
                    return i;
                }
            }
        }
        return null;
    }
}
