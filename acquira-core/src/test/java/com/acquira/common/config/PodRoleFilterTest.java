package com.acquira.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which modules each pod role loads. The filter is an EXCLUDE filter, so
 * "loaded" means match() returned false.
 */
class PodRoleFilterTest {

    private static final String CORE_ONLY = "com.acquira.core.service.EmailService";
    private static final String CORE_SHARED_WITH_PDF = "com.acquira.core.service.ReportS3UploadService";
    private static final String BATCH = "com.acquira.batch.config.BatchConfig";
    private static final String PDF = "com.acquira.pdf.service.PlaywrightPdfService";
    private static final String AI = "com.acquira.ai.controller.AiAssistantController";
    private static final String COMMON = "com.acquira.common.service.ReportCache";

    private final MetadataReaderFactory factory = new SimpleMetadataReaderFactory();

    private boolean loaded(String role, String className) throws Exception {
        PodRoleFilter filter = new PodRoleFilter();
        MockEnvironment env = new MockEnvironment();
        if (role != null) env.setProperty(PodRoleFilter.ROLE_PROPERTY, role);
        filter.setEnvironment(env);
        return !filter.match(factory.getMetadataReader(className), factory);
    }

    @Test
    @DisplayName("no role configured loads every module (single-JVM default)")
    void defaultLoadsEverything() throws Exception {
        for (String c : new String[] {CORE_ONLY, CORE_SHARED_WITH_PDF, BATCH, PDF, AI, COMMON}) {
            assertTrue(loaded(null, c), c);
            assertTrue(loaded("all", c), c);
        }
    }

    @Test
    @DisplayName("core role: core + ai + common, no batch, no pdf")
    void coreRole() throws Exception {
        assertTrue(loaded("core", CORE_ONLY));
        assertTrue(loaded("core", AI));
        assertTrue(loaded("core", COMMON));
        assertFalse(loaded("core", BATCH));
        assertFalse(loaded("core", PDF));
    }

    @Test
    @DisplayName("pdf role: pdf + common, plus core classes that opt in with @LoadInRoles")
    void pdfRole() throws Exception {
        assertTrue(loaded("pdf", PDF));
        assertTrue(loaded("pdf", COMMON));
        assertTrue(loaded("pdf", CORE_SHARED_WITH_PDF));
        assertFalse(loaded("pdf", CORE_ONLY));
        assertFalse(loaded("pdf", BATCH));
        assertFalse(loaded("pdf", AI));
    }

    @Test
    @DisplayName("batch role: batch + common only")
    void batchRole() throws Exception {
        assertTrue(loaded("batch", BATCH));
        assertTrue(loaded("batch", COMMON));
        assertFalse(loaded("batch", CORE_ONLY));
        assertFalse(loaded("batch", CORE_SHARED_WITH_PDF));
        assertFalse(loaded("batch", PDF));
        assertFalse(loaded("batch", AI));
    }

    @Test
    @DisplayName("role is case-insensitive; an unknown role fails fast instead of loading nothing")
    void roleValidation() throws Exception {
        assertTrue(loaded(" PDF ", PDF));
        assertThrows(IllegalStateException.class, () -> loaded("worker", COMMON));
    }
}
