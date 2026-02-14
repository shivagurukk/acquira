package com.acquira.pdf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Standalone entry point for acquira-pdf when run as a separate service.
 * When used as a library inside acquira-core, this class is NOT invoked —
 * CoreApplication handles all scanning. The inner Config class only activates
 * when running standalone (i.e., when CoreApplication is absent).
 */
@SpringBootApplication
public class PdfApplication {
    public static void main(String[] args) {
        SpringApplication.run(PdfApplication.class, args);
    }

    /**
     * Only activates scanning when running standalone (CoreApplication not present).
     */
    @Configuration
    @ConditionalOnMissingBean(name = "coreApplication")
    @ComponentScan(basePackages = {"com.acquira.common", "com.acquira.pdf"})
    @EntityScan(basePackages = "com.acquira.common.model")
    @EnableJpaRepositories(basePackages = "com.acquira.common.repository")
    static class StandaloneConfig {
    }
}
