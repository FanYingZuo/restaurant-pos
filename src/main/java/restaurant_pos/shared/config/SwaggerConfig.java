package restaurant_pos.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Swagger 設定（只在開發環境存在，§3、§4.5）：在 Swagger 畫面的 Authorize 可輸入裝置憑證、解鎖憑證與顧客 token 標頭。
 * 完整的 API 合約見 docs/contract/openapi.yaml（已凍結 1.0.0）。
 */
@Configuration
@Profile("dev")
public class SwaggerConfig {

    @Bean
    public OpenAPI posOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("餐廳 QR 點餐＋POS 系統 API（開發環境）")
                        .description("先以配對碼呼叫 POST /api/pairing 取得裝置憑證，再按右上角 Authorize 輸入 X-Device-Token。")
                        .version("dev"))
                .components(new Components()
                        .addSecuritySchemes("DeviceToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Device-Token")
                                .description("裝置憑證（配對時只回傳一次）"))
                        .addSecuritySchemes("UnlockToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Unlock-Token")
                                .description("管理者解鎖憑證（單一功能有效）"))
                        .addSecuritySchemes("SessionToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Session-Token")
                                .description("顧客 Session token（來自 QR 網址片段）")))
                .addSecurityItem(new SecurityRequirement().addList("DeviceToken"));
    }
}
