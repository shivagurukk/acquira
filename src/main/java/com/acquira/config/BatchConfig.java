package com.acquira.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;

@Configuration
// @EnableBatchProcessing // In Spring Boot 3+, this is often auto-configured,
// but we keep the config class for custom beans
public class BatchConfig {
    // Custom JobLauncher or JobRepository customization if needed
}
