package com.acquira.core.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pod split is a ROUTING contract: the ingress (deploy/k8s/base/07-ingress.yaml)
 * and the Vite dev proxy (frontend/vite.config.js) send each /api prefix to the
 * pod whose module serves it. This test pins that contract to the code so that
 * adding a controller to acquira-batch or acquira-pdf without adding its prefix
 * to the ingress — or putting a core controller under a batch/pdf prefix —
 * fails the build instead of 404ing in the cluster.
 */
class IngressRoutingContractTest {

    /** module package -> pod the ingress must send it to. */
    private static final Map<String, String> MODULE_POD = Map.of(
            "com.acquira.batch", "acquira-batch",
            "com.acquira.pdf", "acquira-pdf",
            "com.acquira.core", "acquira-core",
            "com.acquira.ai", "acquira-core");

    private static final Path INGRESS = Path.of("..", "deploy", "k8s", "base", "07-ingress.yaml");
    private static final Path VITE = Path.of("..", "frontend", "vite.config.js");

    /** Ingress rules in file order: path prefix -> service. */
    private static Map<String, String> ingressRules() throws Exception {
        Map<String, String> rules = new LinkedHashMap<>();
        Matcher m = Pattern.compile("- path: (\\S+)\\s*\\r?\\n\\s*pathType: Prefix\\s*\\r?\\n\\s*backend: \\{ service: \\{ name: (\\S+),")
                .matcher(Files.readString(INGRESS));
        while (m.find()) rules.put(m.group(1), m.group(2));
        assertFalse(rules.isEmpty(), "no rules parsed from " + INGRESS.toAbsolutePath());
        return rules;
    }

    /** Longest matching prefix wins, element-wise (like ingress-nginx and Vite). */
    private static String routeOf(String path, Map<String, String> rules) {
        String best = null;
        for (String prefix : rules.keySet()) {
            boolean match = path.equals(prefix) || path.startsWith(prefix + "/") || prefix.equals("/");
            if (match && (best == null || prefix.length() > best.length())) best = prefix;
        }
        return best == null ? null : rules.get(best);
    }

    /** class-level @RequestMapping paths of every @RestController in the module. */
    private static Map<String, List<String>> controllerPaths(String basePackage) throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        SimpleMetadataReaderFactory readers = new SimpleMetadataReaderFactory();
        Map<String, List<String>> out = new TreeMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
            MetadataReader reader = readers.getMetadataReader(bd.getBeanClassName());
            AnnotationMetadata meta = reader.getAnnotationMetadata();
            Map<String, Object> attrs = meta.getAnnotationAttributes(RequestMapping.class.getName());
            List<String> paths = new ArrayList<>();
            if (attrs != null) {
                for (String p : (String[]) attrs.get("value")) paths.add(p);
                for (String p : (String[]) attrs.get("path")) if (!paths.contains(p)) paths.add(p);
            }
            out.put(bd.getBeanClassName(), paths);
        }
        return out;
    }

    @Test
    @DisplayName("every batch/pdf/core/ai controller is routed to its own pod by the ingress")
    void everyControllerReachesItsPod() throws Exception {
        Map<String, String> rules = ingressRules();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : MODULE_POD.entrySet()) {
            Map<String, List<String>> controllers = controllerPaths(e.getKey());
            assertFalse(controllers.isEmpty(), "no controllers found under " + e.getKey());
            for (Map.Entry<String, List<String>> c : controllers.entrySet()) {
                String cls = c.getKey();
                if (cls.endsWith("MerchantInsightController")) continue; // core stub, never loads (ConditionalOnMissingClass)
                if (c.getValue().isEmpty()) {
                    // No class-level prefix: only acceptable if it cannot be served by another pod,
                    // i.e. the module is core (the /api catch-all). batch/pdf must declare a prefix.
                    if (!e.getValue().equals("acquira-core")) problems.add(cls + ": no class-level @RequestMapping — add one under an ingress prefix");
                    continue;
                }
                for (String path : c.getValue()) {
                    if (path.startsWith("/internal/")) continue; // pod-to-pod, deliberately unrouted
                    String target = routeOf(path, rules);
                    if (!e.getValue().equals(target)) {
                        problems.add(cls + " [" + path + "] routes to " + target + ", expected " + e.getValue());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "Ingress routing contract broken:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("the Vite dev proxy uses the same batch/pdf prefixes as the ingress")
    void viteProxyMatchesIngress() throws Exception {
        Map<String, String> rules = ingressRules();
        String vite = Files.readString(VITE);
        for (Map.Entry<String, String> r : rules.entrySet()) {
            if (r.getKey().equals("/") || r.getKey().equals("/api")) continue;
            assertTrue(vite.contains("'" + r.getKey() + "'"),
                    "frontend/vite.config.js is missing proxy prefix " + r.getKey() + " (" + r.getValue() + ")");
        }
    }
}
