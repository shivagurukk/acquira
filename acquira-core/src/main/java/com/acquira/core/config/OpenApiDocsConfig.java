package com.acquira.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Machine-readable API documentation (springdoc) for the 400+ internal
 * endpoints, so the app UI's contract cannot drift from the code.
 *
 *   /v3/api-docs/internal — everything under /api
 * Swagger UI lives at /swagger-ui/index.html.
 *
 * Gated by springdoc.api-docs.enabled — ON for dev, OFF by default in prod
 * (application-prod.properties) until the docs paths are consciously exposed;
 * flip API_DOCS_ENABLED=true there to publish them.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiDocsConfig {

    @Bean
    public OpenAPI acquiraOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Acquira API")
                        .version("v1")
                        .description("Merchant acquiring analytics platform. The application UI "
                                + "uses JWT bearer tokens with an X-Tenant-Id header."))
                .components(new Components()
                        .addSecuritySchemes("bearerJwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
                .group("internal")
                .pathsToMatch("/api/**")
                .build();
    }
}
