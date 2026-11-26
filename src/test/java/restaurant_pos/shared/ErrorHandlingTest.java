package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.support.AbstractIntegrationTest;
import restaurant_pos.support.TestSupportConfiguration;

/** 卡 0.5：錯誤碼列舉、全域錯誤處理、HTTP 狀態對照（01-PLAN §4.8）。 */
class ErrorHandlingTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    /** 規格 §4.8「HTTP 狀態分類對照」表，獨立抄錄於測試，不從主程式複製。 */
    private static final Map<Integer, List<String>> SPEC = Map.of(
            400, List.of("VALIDATION_ERROR", "INVALID_QUANTITY", "INVALID_PRICE", "INVALID_IMAGE",
                    "INVALID_SORT_REQUEST", "INVALID_DATE_RANGE", "DAY_CLOSE_FUTURE_DATE", "PAYMENT_AMOUNT_INVALID",
                    "INVALID_MODIFIER_SELECTION", "MODIFIER_GROUP_SELECTION_OUT_OF_RANGE",
                    "MODIFIER_DUPLICATE_NOT_ALLOWED", "SHIFT_CORRECTION_INVALID", "SHIFT_CORRECTION_TOO_OLD"),
            401, List.of("DEVICE_TOKEN_INVALID", "SESSION_TOKEN_INVALID"),
            403, List.of("FORBIDDEN", "INVALID_PIN", "NOT_MANAGER", "DEVICE_TYPE_FORBIDDEN", "DEVICE_SELF_REVOKE",
                    "PAIRING_CODE_INVALID", "SHIFT_CORRECTION_SELF", "KDS_STATION_FORBIDDEN"),
            404, List.of("NOT_FOUND", "EMPLOYEE_NOT_FOUND", "MENU_CATEGORY_NOT_FOUND", "MENU_ITEM_NOT_FOUND",
                    "MODIFIER_GROUP_NOT_FOUND", "MODIFIER_NOT_FOUND", "TABLE_NOT_FOUND", "SESSION_NOT_FOUND",
                    "ORDER_NOT_FOUND", "ORDER_ITEM_NOT_FOUND", "DRAFT_ITEM_NOT_FOUND", "CHECKOUT_NOT_FOUND",
                    "KDS_ITEM_NOT_FOUND", "PRINT_JOB_NOT_FOUND"),
            409, List.of("CONCURRENT_MODIFICATION", "DUPLICATE_REQUEST", "ALREADY_CLOCKED_IN", "PIN_ALREADY_USED",
                    "LAST_MANAGER", "MENU_CATEGORY_IN_USE", "MENU_ITEM_NOT_ARCHIVED", "MENU_ITEM_NOT_SELLABLE",
                    "TABLE_NO_DUPLICATE", "TABLE_OCCUPIED", "TABLE_UNAVAILABLE", "SESSION_NOT_OPEN",
                    "SESSION_HAS_SUBMITTED_ORDERS", "SESSION_PENDING_RECHECKOUT", "QR_EXPIRED",
                    "CUSTOMER_ORDER_LIMIT", "ORDER_ITEM_CANCELLED", "ORDER_ITEM_ALREADY_READY",
                    "INVALID_ORDER_ITEM_TRANSITION", "ORDER_NOT_EDITABLE", "EMPTY_DRAFT", "CHECKOUT_IN_PROGRESS",
                    "UNSENT_ITEMS_EXIST", "NOTHING_TO_CHECKOUT", "CHECKOUT_NOT_IN_PROGRESS",
                    "CHECKOUT_NOT_VOIDABLE", "CHECKOUT_VOID_NOT_SAME_DAY", "CHECKOUT_HAS_PAYMENTS",
                    "PAYMENT_METHOD_DISABLED", "PAYMENT_FAILED", "PAYMENT_IDEMPOTENCY_CONFLICT",
                    "PAYMENT_NOT_REFUNDABLE", "VOID_FAILED"),
            429, List.of("PIN_LOCKED", "PAIRING_LOCKED", "CLOCK_TOO_FAST", "CUSTOMER_ORDER_TOO_FAST"),
            500, List.of("INTERNAL_ERROR"),
            405, List.of("METHOD_NOT_ALLOWED"),
            415, List.of("UNSUPPORTED_MEDIA_TYPE"));

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void 錯誤碼列舉剛好是規格表的77個() {
        Set<String> expected = new java.util.HashSet<>();
        SPEC.values().forEach(expected::addAll);
        Set<String> actual = new java.util.HashSet<>();
        EnumSet.allOf(ErrorCode.class).forEach(c -> actual.add(c.name()));
        assertThat(expected).hasSize(77);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 每個錯誤碼的HTTP狀態都符合規格對照表() {
        Map<String, Integer> expected = new HashMap<>();
        SPEC.forEach((status, codes) -> codes.forEach(c -> expected.put(c, status)));
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status().value()).as(code.name()).isEqualTo(expected.get(code.name()));
        }
    }

    @Test
    void 每個錯誤碼經由API回傳對應狀態且回應只含三個欄位() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ErrorCode code : ErrorCode.values()) {
            MvcResult r = mvc.perform(get("/api/test/errors/api").param("code", code.name())).andReturn();
            JsonNode body = json(r);
            if (r.getResponse().getStatus() != code.status().value()) {
                failures.add(code + " 狀態 " + r.getResponse().getStatus());
            }
            List<String> fields = new ArrayList<>();
            body.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(List.of("code", "message", "timestamp"))) {
                failures.add(code + " 欄位 " + fields);
            }
            if (!body.get("code").asText().equals(code.name())) {
                failures.add(code + " code 欄位不符");
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void 訊息為固定的繁體中文且不含動態資料() throws Exception {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.message()).as(code.name()).isNotBlank().matches(".*[\\u4e00-\\u9fff].*");
        }
        String first = json(mvc.perform(get("/api/test/errors/api").param("code", "INVALID_PIN")).andReturn())
                .get("message").asText();
        String second = json(mvc.perform(get("/api/test/errors/api").param("code", "INVALID_PIN")).andReturn())
                .get("message").asText();
        assertThat(first).isEqualTo(second).isEqualTo(ErrorCode.INVALID_PIN.message());
    }

    @Test
    void 時間戳來自注入的時鐘() throws Exception {
        mvc.perform(get("/api/test/errors/api").param("code", "NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").value(TestSupportConfiguration.DEFAULT_NOW.toString()));
    }

    @Test
    void 未預期例外回INTERNAL_ERROR且不洩漏內部細節() throws Exception {
        MvcResult r = mvc.perform(get("/api/test/errors/runtime")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain("hunter2").doesNotContain("SQL").doesNotContain("Exception")
                .doesNotContain("stackTrace").doesNotContain("trace");
    }

    @Test
    void 唯一索引衝突死結與鎖等待逾時都轉CONCURRENT_MODIFICATION() throws Exception {
        for (String path : List.of("duplicate", "lock-timeout", "deadlock")) {
            mvc.perform(get("/api/test/errors/" + path)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        }
    }

    @Test
    void 其他資料完整性錯誤回INTERNAL_ERROR() throws Exception {
        mvc.perform(get("/api/test/errors/other-integrity")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void 找不到路徑回NOT_FOUND() throws Exception {
        mvc.perform(get("/api/test/errors/no-such-path")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 不支援的方法回METHOD_NOT_ALLOWED() throws Exception {
        mvc.perform(post("/api/test/errors/runtime")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 不支援的內容類型回UNSUPPORTED_MEDIA_TYPE() throws Exception {
        mvc.perform(post("/api/test/errors/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void JSON格式錯誤與參數型別錯誤回VALIDATION_ERROR() throws Exception {
        mvc.perform(post("/api/test/errors/body").contentType(MediaType.APPLICATION_JSON).content("{壞掉"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/test/errors/param").param("n", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/test/errors/param"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
