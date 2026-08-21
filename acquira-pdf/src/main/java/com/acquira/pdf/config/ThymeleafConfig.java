package com.acquira.pdf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresource.ITemplateResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Thymeleaf configuration for PDF generation.
 *
 * Uses a custom template resolver that SANITIZES all template content
 * before Thymeleaf/SpEL parsing. This removes any base64 data URIs
 * from Thymeleaf expressions (th:src, th:if) and replaces them with
 * short placeholders.
 *
 * WHY: Spring Framework 6.1.1 (bundled with Spring Boot 3.2.0) has a
 * HARDCODED 10,000-character SpEL expression limit in InternalSpelExpressionParser.
 * Base64-encoded logo images (~50,000+ chars) exceed this limit when used in
 * Thymeleaf expressions like th:src="${afsLogoWhite}".
 * The spring.context.expression.maxLength property is NOT configurable in 6.1.1.
 * This sanitizer ensures no base64 data ever reaches SpEL evaluation.
 */
@Configuration
public class ThymeleafConfig {

    // Patterns to match ANY th:src expression referencing afsLogo variables (with possible ternary + base64 fallback)
    private static final Pattern P_TH_SRC_WHITE = Pattern.compile(
            "th:src\\s*=\\s*\"\\$\\{[^\"]*afsLogoWhite[^\"]*}\"", Pattern.DOTALL);
    private static final Pattern P_TH_SRC_BLACK = Pattern.compile(
            "th:src\\s*=\\s*\"\\$\\{[^\"]*afsLogoBlack[^\"]*}\"", Pattern.DOTALL);
    private static final Pattern P_TH_SRC_COLOR = Pattern.compile(
            "th:src\\s*=\\s*\"\\$\\{[^\"]*afsLogoColor[^\"]*}\"", Pattern.DOTALL);

    // Patterns to match th:if expressions referencing afsLogo variables
    private static final Pattern P_TH_IF_WHITE = Pattern.compile(
            "th:if\\s*=\\s*\"\\$\\{[^\"]*afsLogoWhite[^\"]*}\"", Pattern.DOTALL);
    private static final Pattern P_TH_IF_BLACK = Pattern.compile(
            "th:if\\s*=\\s*\"\\$\\{[^\"]*afsLogoBlack[^\"]*}\"", Pattern.DOTALL);
    private static final Pattern P_TH_IF_COLOR = Pattern.compile(
            "th:if\\s*=\\s*\"\\$\\{[^\"]*afsLogoColor[^\"]*}\"", Pattern.DOTALL);

    @Bean(name = "pdfTemplateEngine")
    public SpringTemplateEngine pdfTemplateEngine() {
        SanitizingTemplateResolver resolver = new SanitizingTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        resolver.setOrder(1);
        resolver.setCheckExistence(true);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /**
     * Custom ClassLoaderTemplateResolver that wraps every ITemplateResource
     * in a sanitizer, stripping base64 data from Thymeleaf/SpEL expressions.
     */
    static class SanitizingTemplateResolver extends ClassLoaderTemplateResolver {
        @Override
        protected ITemplateResource computeTemplateResource(
                org.thymeleaf.IEngineConfiguration configuration,
                String ownerTemplate, String template, String resourceName,
                String characterEncoding, Map<String, Object> templateResolutionAttributes) {

            ITemplateResource original = super.computeTemplateResource(
                    configuration, ownerTemplate, template, resourceName,
                    characterEncoding, templateResolutionAttributes);

            return new SanitizingResource(original);
        }
    }

    /**
     * Wraps an ITemplateResource and sanitizes its content on read.
     * Replaces any th:src/th:if expressions containing afsLogo variables
     * (which may have long base64 fallback strings) with short placeholders.
     */
    static class SanitizingResource implements ITemplateResource {
        private final ITemplateResource delegate;

        SanitizingResource(ITemplateResource delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getDescription() { return delegate.getDescription(); }

        @Override
        public String getBaseName() { return delegate.getBaseName(); }

        @Override
        public boolean exists() { return delegate.exists(); }

        @Override
        public Reader reader() throws IOException {
            Reader original = delegate.reader();
            if (original == null) return null;

            String content = readFully(original);
            String sanitized = sanitizeLogoExpressions(content);
            return new StringReader(sanitized);
        }

        @Override
        public ITemplateResource relative(String relativeLocation) {
            ITemplateResource rel = delegate.relative(relativeLocation);
            return new SanitizingResource(rel);
        }

        private static String readFully(Reader reader) throws IOException {
            StringBuilder sb = new StringBuilder(8192);
            try (BufferedReader br = new BufferedReader(reader)) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        }

        /**
         * Remove base64 data from any Thymeleaf expression referencing afsLogo*.
         * Handles patterns like:
         *   th:src="${afsLogoWhite}"
         *   th:src="${afsLogoWhite != null ? afsLogoWhite : 'data:image/jpeg;base64,...'}"
         *   th:if="${afsLogoWhite != null}"
         * Replaces with plain src="__AFS_LOGO_XXX__" or removes th:if entirely.
         */
        private static String sanitizeLogoExpressions(String html) {
            // Replace th:src expressions for each logo variant with plain src placeholders
            html = P_TH_SRC_WHITE.matcher(html).replaceAll("src=\"__AFS_LOGO_WHITE__\"");
            html = P_TH_SRC_BLACK.matcher(html).replaceAll("src=\"__AFS_LOGO_BLACK__\"");
            html = P_TH_SRC_COLOR.matcher(html).replaceAll("src=\"__AFS_LOGO_COLOR__\"");

            // Remove th:if expressions for logo null-checks (they're always present now)
            html = P_TH_IF_WHITE.matcher(html).replaceAll("");
            html = P_TH_IF_BLACK.matcher(html).replaceAll("");
            html = P_TH_IF_COLOR.matcher(html).replaceAll("");

            return html;
        }
    }
}
