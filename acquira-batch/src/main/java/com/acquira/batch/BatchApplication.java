package com.acquira.batch;

import org.springframework.context.annotation.Configuration;

/**
 * Batch module configuration.
 * When running standalone, use BatchStandaloneApplication.
 * When embedded in Core, this is just a configuration marker.
 */
@Configuration
public class BatchApplication {
    // No @SpringBootApplication - Core is the main app
}
