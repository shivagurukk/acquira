package com.acquira.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import com.acquira.common.config.PodRoleFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The one application entry point for every pod. Which modules actually load is
 * decided by {@code acquira.role} (all | core | pdf | batch) through
 * {@link PodRoleFilter}; the default "all" is the original single-JVM app.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.acquira.common", "com.acquira.core", "com.acquira.batch", "com.acquira.pdf", "com.acquira.ai"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = PodRoleFilter.class))
@EntityScan(basePackages = "com.acquira.common.model")
@EnableJpaRepositories(basePackages = "com.acquira.common.repository")
@EnableScheduling
@org.springframework.scheduling.annotation.EnableAsync
public class CoreApplication {
    public static void main(String[] args) {
        // Raise SpEL expression length limit BEFORE Spring context initializes.
        // Required to support base64 image data URIs injected as Thymeleaf variables.
        System.setProperty("spring.context.expression.maxLength", "500000");
        SpringApplication.run(CoreApplication.class, args);
    }
}
