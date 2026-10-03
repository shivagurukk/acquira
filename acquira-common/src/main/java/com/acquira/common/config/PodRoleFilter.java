package com.acquira.common.config;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Component-scan EXCLUDE filter that decides which modules a JVM loads, so the
 * one application jar can run as separate Kubernetes pods.
 *
 * {@code acquira.role} (env ACQUIRA_ROLE) selects the role:
 * <pre>
 *   all    everything in one JVM — the default; local dev and single-pod installs
 *   core   com.acquira.core + com.acquira.ai   (API, auth, email, schedulers, AI)
 *   pdf    com.acquira.pdf                      (Chromium PDF rendering)
 *   batch  com.acquira.batch                    (ingest jobs, integration pulls)
 * </pre>
 * com.acquira.common is loaded in every role. A class outside the role's
 * modules can opt in with {@link LoadInRoles}.
 *
 * One jar + a role switch, rather than one jar per module, so every pod shares
 * the same application.properties, security chain and logging config — the
 * per-module property files are far leaner than core's and would drift.
 *
 * WHAT THE ROLES DO NOT SHARE: Spring events and in-memory caches. Cross-role
 * signals must go through the database (EventOutbox, ReportCacheSync).
 */
public class PodRoleFilter implements TypeFilter, EnvironmentAware {

    public static final String ROLE_PROPERTY = "acquira.role";
    public static final String ROLE_ALL = "all";

    private static final String BASE = "com.acquira.";
    private static final String COMMON_MODULE = "common";

    /** role -> module packages (the segment after com.acquira.) it owns. */
    private static final Map<String, Set<String>> ROLE_MODULES = Map.of(
            "core", Set.of("core", "ai"),
            "pdf", Set.of("pdf"),
            "batch", Set.of("batch"));

    private String role = ROLE_ALL;

    @Override
    public void setEnvironment(Environment environment) {
        String configured = environment.getProperty(ROLE_PROPERTY, ROLE_ALL).trim().toLowerCase();
        if (!ROLE_ALL.equals(configured) && !ROLE_MODULES.containsKey(configured)) {
            throw new IllegalStateException("Unknown " + ROLE_PROPERTY + " '" + configured
                    + "' — expected one of: all, core, pdf, batch");
        }
        this.role = configured;
    }

    /** @return true to EXCLUDE the class from this JVM. */
    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
        if (ROLE_ALL.equals(role)) return false;
        String className = reader.getClassMetadata().getClassName();
        if (!className.startsWith(BASE)) return false;
        int end = className.indexOf('.', BASE.length());
        String module = end < 0 ? "" : className.substring(BASE.length(), end);
        if (COMMON_MODULE.equals(module) || ROLE_MODULES.get(role).contains(module)) return false;
        return !optedIn(reader, factory);
    }

    /** {@link LoadInRoles} on the class or, for a nested class, on an enclosing one. */
    private boolean optedIn(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
        AnnotationMetadata meta = reader.getAnnotationMetadata();
        Map<String, Object> attrs = meta.getAnnotationAttributes(LoadInRoles.class.getName());
        if (attrs != null && Arrays.asList((String[]) attrs.get("value")).contains(role)) return true;
        String enclosing = reader.getClassMetadata().getEnclosingClassName();
        return enclosing != null && optedIn(factory.getMetadataReader(enclosing), factory);
    }
}
