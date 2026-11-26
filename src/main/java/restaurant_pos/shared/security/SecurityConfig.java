package restaurant_pos.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import restaurant_pos.shared.error.ErrorCode;

/**
 * Spring Security 基本設定（docs/plan/01-PLAN.md §4.5「HTTP 與環境」）：
 * 無狀態、關閉 CSRF；只放行 /api、錯誤頁、/ws，其餘一律回 404（不洩漏存在性）；
 * 回應標頭：CSP 預設不允許任何來源、禁止嵌入框架、不送參照來源、禁止猜測內容類型。
 */
@Configuration
public class SecurityConfig {

    /** 開發環境的 Swagger 需要載入自己的腳本與樣式，只對這幾個路徑放寬 CSP。 */
    @Bean
    @Order(1)
    @Profile("dev")
    public SecurityFilterChain swaggerChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(
                                "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .contentTypeOptions(Customizer.withDefaults()))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain mainChain(HttpSecurity http, SecurityErrorWriter errorWriter,
            DeviceAuthenticator deviceAuthenticator) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .contentTypeOptions(Customizer.withDefaults()))
                .addFilterBefore(new DeviceTokenFilter(deviceAuthenticator, errorWriter), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/**", "/error", "/ws", "/ws/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> errorWriter.write(res, ErrorCode.NOT_FOUND))
                        .accessDeniedHandler((req, res, ex) -> errorWriter.write(res, ErrorCode.NOT_FOUND)));
        return http.build();
    }
}
