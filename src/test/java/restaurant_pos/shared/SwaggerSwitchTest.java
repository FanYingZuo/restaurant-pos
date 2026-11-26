package restaurant_pos.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import restaurant_pos.support.AbstractIntegrationTest;

/** 卡 0.4：Swagger 只在開發環境啟用，正式路徑回 404（§4.5、§4.9）。 */
class SwaggerSwitchTest {

    /** 預設測試環境為 dev。 */
    @Nested
    class Dev extends AbstractIntegrationTest {

        @Test
        void 開發環境API文件可開() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "spring.profiles.active=prod",
            "spring.datasource.url=jdbc:mysql://localhost:3306/placeholder",
            "restaurantpos.base-url=https://pos.example.test",
            "restaurantpos.pin-secret=0123456789abcdef0123456789abcdef"})
    class Prod extends AbstractIntegrationTest {

        @Test
        void 正式環境API文件回404() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        }

        @Test
        void 正式環境SwaggerUI回404() throws Exception {
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
            mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        }
    }
}
