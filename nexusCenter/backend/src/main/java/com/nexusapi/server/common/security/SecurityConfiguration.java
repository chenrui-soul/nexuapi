package com.nexusapi.server.common.security;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.modules.apikey.security.ApiKeyAuthenticationFilter;
import com.nexusapi.server.modules.auth.security.SessionAuthenticationFilter;
import com.nexusapi.server.modules.systemtoken.security.SystemAccessTokenAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final JsonApiKeyAuthenticationEntryPoint apiKeyAuthenticationEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final SystemAccessTokenAuthenticationFilter systemAccessTokenAuthenticationFilter;
    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final JsonSystemAccessTokenEntryPoint systemAccessTokenEntryPoint;

    public SecurityConfiguration(
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonApiKeyAuthenticationEntryPoint apiKeyAuthenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            SystemAccessTokenAuthenticationFilter systemAccessTokenAuthenticationFilter,
            JsonSystemAccessTokenEntryPoint systemAccessTokenEntryPoint,
            SessionAuthenticationFilter sessionAuthenticationFilter
    ) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.apiKeyAuthenticationEntryPoint = apiKeyAuthenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.systemAccessTokenAuthenticationFilter = systemAccessTokenAuthenticationFilter;
        this.systemAccessTokenEntryPoint = systemAccessTokenEntryPoint;
        this.sessionAuthenticationFilter = sessionAuthenticationFilter;
    }

    @Bean
    @Order(1)
    SecurityFilterChain publicFileUploadSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/v1/files/upload")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain gatewaySecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/v1/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers.withObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
                    @Override
                    public <O extends HeaderWriterFilter> O postProcess(O filter) {
                        // Gateway 使用 StreamingResponseBody；安全响应头必须在异步线程提交响应前写入。
                        filter.setShouldWriteHeadersEagerly(true);
                        return filter;
                    }
                }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(apiKeyAuthenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(apiKeyAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // ASYNC 是已通过 Bearer 鉴权的同一请求内部续派，客户端不能伪造该 DispatcherType。
                        // 放行续派可避免 SSE 写出后再次进入安全链时因无 SecurityContext 被误判为未认证。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    /**
     * API 令牌 Filter 只能由 `/v1/**` 安全链调用。
     *
     * <p>禁用 Servlet 容器的全局自动注册，避免它误拦截控制台 `/api/**`
     * 的 Cookie Session 请求，同时也避免同一 Filter 在容器和 Spring Security 中重复执行。</p>
     */
    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilterRegistration(
            ApiKeyAuthenticationFilter filter
    ) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** 系统访问令牌 Filter 只允许由独立只读安全链调用，禁止 Servlet 全局注册。 */
    @Bean
    FilterRegistrationBean<SystemAccessTokenAuthenticationFilter> systemAccessTokenFilterRegistration(
            SystemAccessTokenAuthenticationFilter filter
    ) {
        FilterRegistrationBean<SystemAccessTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(3)
    SecurityFilterChain systemAccessSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/v1/system-access/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(systemAccessTokenEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(systemAccessTokenAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SYSTEM_ACCESS_TOKEN"))
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain consoleSecurity(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = new CookieCsrfTokenRepository();
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieName("NEXUS_XSRF_TOKEN");
        csrfRepository.setHeaderName("X-CSRF-TOKEN");

        return http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // AuthSessionService already invalidates the pre-login session and creates a new ID.
                        .sessionFixation(fixation -> fixation.none()))
                .securityContext(context -> context.requireExplicitSave(true))
                .addFilterBefore(sessionAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .cors(Customizer.withDefaults())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .ignoringRequestMatchers(
                                "/api/v1/auth/captcha",
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/password/forgot",
                                "/api/v1/auth/password/reset",
                                "/api/v1/payments/callbacks/**"
                                , "/api/v1/device-activation/verify"
                        ))
                .authorizeHttpRequests(auth -> auth
                        // Servlet 容器发生异常后会以 ERROR 分派进入 `/error`；该内部分派必须保留原始 HTTP 状态，
                        // 不能被控制台会话入口误报为 AUTH_SESSION_EXPIRED。
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                                .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api/v1/system/info",
                                "/api/v1/system/registration",
                                "/api/v1/auth/captcha",
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/password/forgot",
                                "/api/v1/auth/password/reset",
                                "/api/v1/payments/callbacks/**"
                                , "/api/v1/device-activation/verify"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/models", "/api/v1/service-groups", "/api/v1/model-market", "/api/v1/model-market/**").permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(NexusProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.security().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "X-CSRF-TOKEN", "X-Request-Id",
                "X-Nexus-Group"
        ));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
