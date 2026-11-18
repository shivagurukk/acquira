package com.acquira.common.config;

import jakarta.servlet.MultipartConfigElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Role-aware multipart limits.
 *
 * {@code spring.servlet.multipart.max-*} is 2 GB because the ingest jobs (batch
 * module) accept month-sized transaction files. That property is global, so in
 * the split deployment the internet-facing core pod accepted the same 2 GB on
 * every endpoint — logo uploads, CSV imports, Excel parsers — which is a cheap
 * disk/memory exhaustion vector for any authenticated caller. Only the batch
 * pod ingests big files, so only it (and the all-in-one role) keeps the
 * configured ceiling; the pure {@code core} role is capped at
 * {@code acquira.upload.core-max} (100 MB by default).
 *
 * Replaces Boot's auto-configured {@link MultipartConfigElement} (which is
 * {@code @ConditionalOnMissingBean}); every other {@code spring.servlet.multipart.*}
 * property (location, threshold, resolve-lazily) is still honoured because the
 * element is built from the same {@link MultipartProperties}.
 */
@Configuration
public class UploadLimitsConfig {

    private static final Logger log = LoggerFactory.getLogger(UploadLimitsConfig.class);
    private static final String ROLE_CORE = "core";

    @Bean
    public MultipartConfigElement multipartConfigElement(
            MultipartProperties props,
            @Value("${" + PodRoleFilter.ROLE_PROPERTY + ":" + PodRoleFilter.ROLE_ALL + "}") String role,
            @Value("${acquira.upload.core-max:100MB}") DataSize coreMax) {
        if (!ROLE_CORE.equalsIgnoreCase(role.trim())) {
            // batch / all / pdf: the configured spring.servlet.multipart.* values, unchanged
            return props.createMultipartConfig();
        }
        // Build from the same properties so location/threshold/resolveLazily are kept,
        // then cap only the two size limits. Never raise above what is configured.
        DataSize fileMax = min(props.getMaxFileSize(), coreMax);
        DataSize requestMax = min(props.getMaxRequestSize(), coreMax);
        log.info("Upload limits for role '{}': max-file-size={} max-request-size={} (configured {} / {})",
                role, fileMax, requestMax, props.getMaxFileSize(), props.getMaxRequestSize());
        MultipartConfigElement base = props.createMultipartConfig();
        return new MultipartConfigElement(base.getLocation(), fileMax.toBytes(), requestMax.toBytes(),
                base.getFileSizeThreshold());
    }

    /** A negative/unset Spring limit means "unlimited", so the cap always wins there. */
    private static DataSize min(DataSize configured, DataSize cap) {
        if (configured == null || configured.isNegative()) return cap;
        return configured.compareTo(cap) <= 0 ? configured : cap;
    }
}
