package com.acquira.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = {"com.acquira.common", "com.acquira.core", "com.acquira.batch", "com.acquira.pdf"})
@EntityScan(basePackages = "com.acquira.common.model")
@EnableJpaRepositories(basePackages = "com.acquira.common.repository")
@EnableScheduling
public class CoreApplication {
    public static void main(String[] args) {
        // Raise SpEL expression length limit BEFORE Spring context initializes.
        // Required to support base64 image data URIs injected as Thymeleaf variables.
        System.setProperty("spring.context.expression.maxLength", "500000");
        SpringApplication.run(CoreApplication.class, args);
    }
}
