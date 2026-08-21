package com.acquira.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuditInterceptor} for all /api/** requests so each user
 * request is recorded into audit_log.
 *
 * NOTE: this intentionally implements WebMvcConfigurer (the Spring Boot-friendly
 * way) and does NOT use @EnableWebMvc, so Boot's MVC auto-configuration stays
 * active. Multiple WebMvcConfigurer beans compose cleanly, so this is safe to
 * add alongside any future ones.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuditInterceptor auditInterceptor;

    public WebMvcConfig(AuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditInterceptor).addPathPatterns("/api/**");
    }
}
