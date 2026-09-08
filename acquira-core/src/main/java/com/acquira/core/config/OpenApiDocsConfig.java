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
 * Machine-readable API documentation (springdoc). The dependency sat on the
 * classpath unused since day one while the external spec was hand-maintained
 * in OpenApiController — this wires the generated docs so the 400+ internal
 * endpoints finally have a contract that cannot drift from the code.
 *
 * Two groups:
 *   /v3/api-docs/external — the partner-facing surface (/api/v1, /api/external)
 *   /v3/api-docs/internal — everything else under /api (the app UI's contract)
 * Swagger UI lives at /swagger-ui/index.html.
 *
 * Gated by springdoc.api-docs.enabled — ON for dev, OFF by default in prod
 * (application-prod.properties) until the docs paths are consciously exposed;
 * flip API_DOCS_ENABLED=true there to publish them. The hand-written
 * /api/v1/openapi.json stays as the stable public spec for keyless bootstrap.
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
                        .description("Merchant acquiring analytics platform. External integrations "
                                + "authenticate with an X-API-Key header (tenant-bound, scoped); the "
                                + "application UI uses JWT bearer tokens with an X-Tenant-Id header."))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key"))
                        .addSecuritySchemes("bearerJwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    public GroupedOpenApi externalApi() {
        return GroupedOpenApi.builder()
                .group("external")
                .pathsToMatch("/api/v1/**", "/api/external/**")
                .build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
                .group("internal")
                .pathsToMatch("/api/**")
                .pathsToExclude("/api/v1/**", "/api/external/**")
                .build();
    }
}
