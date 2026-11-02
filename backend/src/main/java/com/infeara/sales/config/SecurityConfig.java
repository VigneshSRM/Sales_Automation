package com.infeara.sales.config;

import com.infeara.sales.security.RateLimitFilter;
import com.infeara.sales.security.RestAccessDeniedHandler;
import com.infeara.sales.security.RestAuthenticationEntryPoint;
import com.infeara.sales.security.SessionAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Security configuration (SEC plan §1-3).
 *
 * <ul>
 *   <li>Authentication is performed by {@link SessionAuthenticationFilter} against the
 *       server-side session store; no form login, no HTTP basic, no JSESSIONID.</li>
 *   <li>CSRF protection is enabled for cookie-authenticated state changes using a
 *       double-submit cookie ({@code XSRF-TOKEN} readable by the SPA, echoed in
 *       {@code X-XSRF-TOKEN}). Provider webhooks are exempt because they are
 *       authenticated by HMAC signature instead.</li>
 *   <li>Hardened response headers (CSP, frame denial, no sniffing, referrer policy).</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final AppProperties properties;

    public SecurityConfig(SessionAuthenticationFilter sessionAuthenticationFilter, RateLimitFilter rateLimitFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler, AppProperties properties) {
        this.sessionAuthenticationFilter = sessionAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.properties = properties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Argon2id with the Spring Security recommended parameters.
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers("/api/v1/webhooks/**", "/api/v1/auth/login",
                                "/api/v1/auth/mfa/**", "/api/v1/intake/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> {
                        })
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.SAME_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "geolocation=(), microphone=(), camera=()"))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
                                        + "base-uri 'self'; form-action 'self'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/mfa/verify",
                                "/api/v1/auth/mfa/enrol/start", "/api/v1/auth/mfa/enrol/confirm").permitAll()
                        .requestMatchers("/api/v1/webhooks/**", "/api/v1/intake/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Static UI assets (bundled Next.js export served from classpath:/static).
                        .requestMatchers("/", "/index.html", "/_next/**", "/favicon.ico", "/manifest.webmanifest")
                        .permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(sessionAuthenticationFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);

        if (properties.getCors().getAllowedOrigins() != null
                && !properties.getCors().getAllowedOrigins().isBlank()) {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        }
        return http.build();
    }

    org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration =
                new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOrigins(java.util.List.of(
                properties.getCors().getAllowedOrigins().split(",")));
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("Content-Type", "X-XSRF-TOKEN", "If-Match",
                "Idempotency-Key", "X-Request-Id", "X-Intake-Secret"));
        configuration.setAllowCredentials(true);
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
