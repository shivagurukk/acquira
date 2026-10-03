package com.acquira.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Standalone entry point for acquira-ai when run as a separate service.
 * When used as a library inside acquira-core, this class is NOT invoked —
 * CoreApplication handles all scanning. The inner Config class only activates
 * when running standalone (i.e., when CoreApplication is absent), same shape as
 * PdfApplication: with the scan/JPA annotations on this class itself, core's
 * component scan picked them up and registered every repository twice
 * ("bean ... already defined in @EnableJpaRepositories declared on AiApplication").
 */
@SpringBootApplication
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }

    @Configuration
    @ConditionalOnMissingClass("com.acquira.core.CoreApplication")
    @ComponentScan(basePackages = {"com.acquira.common", "com.acquira.ai"})
    @EntityScan(basePackages = "com.acquira.common.model")
    @EnableJpaRepositories(basePackages = "com.acquira.common.repository")
    static class StandaloneConfig {
    }
}
