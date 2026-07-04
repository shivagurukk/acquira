package com.acquira.common.config;

import com.acquira.common.security.CustomUserDetailsService;
import com.acquira.common.security.JwtRequestFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // Enables @PreAuthorize, @Secured, etc.
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtRequestFilter jwtRequestFilter;

    @Value("${app.cors.origins:http://localhost:5173}")
    private String corsOrigins;

    public SecurityConfig(CustomUserDetailsService userDetailsService, JwtRequestFilter jwtRequestFilter) {
        this.userDetailsService = userDetailsService;
        this.jwtRequestFilter = jwtRequestFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Acceptable for stateless JWT API
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // ===== Security Headers =====
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> {}) // X-Content-Type-Options: nosniff
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true))
                        .cacheControl(cache -> {}) // Cache-Control: no-cache, no-store
                )

                // ===== URL-Level Authorization =====
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/sso/**").permitAll()
                        .requestMatchers("/api/external/**").permitAll()  // External API — uses X-API-Key auth
                        .requestMatchers("/api/v1/**").permitAll()         // External Data API v1 — X-API-Key auth (ApiKeyAuthFilter)

                        // Admin endpoints — require ADMIN or SUPER_ADMIN
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")

                        // Batch monitoring — admin only
                        .requestMatchers("/api/batch/**").hasAnyRole("ADMIN", "SUPER_ADMIN")

                        // Everything else — must be authenticated
                        .anyRequest().authenticated()
                )

                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ===== Auth/Access Exception Handling =====
                // By default (Spring Security 6, no formLogin/httpBasic) an
                // unauthenticated request to a protected endpoint falls through
                // to Http403ForbiddenEntryPoint and returns 403. That made an
                // expired/idle JWT look like a permission error, and the frontend
                // axios interceptor (which only reacts to 401) silently swallowed
                // it — the page "moved" but every call failed with 403.
                //
                // We now distinguish the two cases explicitly:
                //   * Not authenticated (missing/expired/invalid token) -> 401
                //     so the frontend refreshes the token or redirects to /login.
                //   * Authenticated but lacking the role               -> 403
                //     a genuine permission denial (RBAC), left as-is.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(
                                    "{\"error\":\"Session expired. Please log in again.\",\"code\":\"AUTH_REQUIRED\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(
                                    "{\"error\":\"You do not have permission to access this resource.\",\"code\":\"FORBIDDEN\"}");
                        })
                )

                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // ===== Centralized CORS Configuration =====
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Parse allowed origins from property (comma-separated)
        List<String> origins = Arrays.asList(corsOrigins.split(","));
        config.setAllowedOrigins(origins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Tenant-Id", "X-API-Key"));
        config.setExposedHeaders(List.of("Content-Disposition", "X-Correlation-Id", "Set-Cookie",
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
