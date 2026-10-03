package com.acquira.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the guards.
 *
 * <p>Two failure modes have each cost real access-control coverage in this
 * codebase, and neither is visible in review:
 *
 * <ol>
 *   <li>A controller ships with no {@code @PreAuthorize} at all and sits under
 *       {@code anyRequest().authenticated()}, so any logged-in user of any
 *       tenant can call it. Eleven controllers are in that state today; this
 *       test freezes that list so the twelfth fails the build.</li>
 *   <li>An annotation names a screen path that does not exist — a typo, or a
 *       route renamed without updating the annotation. The evaluator fails
 *       closed, so the symptom is not an open door but a screen nobody can
 *       use, reported days later as "permissions are broken".</li>
 * </ol>
 *
 * <p>Source scanning rather than reflection, deliberately: no Spring context,
 * no database, runs in milliseconds, and it sees the annotation exactly as a
 * reviewer would.
 */
class RbacGuardCoverageTest {

    /**
     * Controllers with no authorisation annotation as of 2026-09-26. This list
     * may shrink, never grow. Each entry is either genuinely public or a known
     * gap with a ticket behind it — an entry is not an endorsement.
     */
    private static final Set<String> UNGATED_BASELINE = Set.of(
        // Legitimately unauthenticated or self-scoped:
        "AuthController",          // login, refresh, forgot-password — pre-auth by definition
        "SsoController",           // SAML/OIDC callback — pre-auth by definition
        "MenuController",          // returns only the caller's own menus/permissions

        // Known gaps — every read is tenant-scoped through TenantContext, but
        // none of these express WHICH screen they back, so a user with no grant
        // to the screen can still call the API behind it.
        "AuditLogController",
        "BatchJobController",
        "BatchProgressController",
        "MerchantController",
        "S3SettingsController",
        "StoreController"
    );

    private static final Pattern MENU_ACCESS =
        Pattern.compile("@menuAccess\\.canAccess\\(\\s*'([^']+)'\\s*\\)");
    private static final Pattern PERM_CAN =
        Pattern.compile("@perm\\.can(?:View)?\\(\\s*'([^']+)'");

    // ── 1. the ratchet ──────────────────────────────────────────────────────

    @Test
    void noNewControllerShipsWithoutAnAuthorisationAnnotation() throws IOException {
        Set<String> ungated = new TreeSet<>();
        for (Path f : controllerSources()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (!src.contains("@RestController")) continue;
            if (src.contains("PreAuthorize")) continue;
            ungated.add(fileName(f));
        }

        Set<String> added = new TreeSet<>(ungated);
        added.removeAll(UNGATED_BASELINE);

        if (!added.isEmpty()) {
            fail("Controller(s) with no @PreAuthorize: " + added
               + ".\nEvery endpoint must declare the screen and action it backs, e.g."
               + "\n    @PreAuthorize(\"@perm.can('sales.agents', 'EDIT')\")"
               + "\nIf the endpoint is genuinely public, add it to UNGATED_BASELINE with"
               + " a comment saying why.");
        }
    }

    /** The baseline must shrink as gaps are closed, or it becomes a lie. */
    @Test
    void baselineHasNoStaleEntries() throws IOException {
        Set<String> present = new HashSet<>();
        for (Path f : controllerSources()) {
            present.add(fileName(f));
        }
        Set<String> stale = new TreeSet<>(UNGATED_BASELINE);
        stale.removeAll(present);
        assertTrue(stale.isEmpty(),
            "UNGATED_BASELINE names controllers that no longer exist: " + stale);
    }

    // ── 2. every guarded path is a real screen ──────────────────────────────

    @Test
    void everyGuardedPathExistsInTheScreenCatalog() throws IOException {
        Set<String> catalogPaths = new HashSet<>();
        Set<String> catalogKeys  = new HashSet<>();
        JsonNode screens = new ObjectMapper()
            .readTree(Paths.get("src/main/resources/rbac/menu-catalog.json").toFile())
            .get("screens");
        for (JsonNode s : screens) {
            if (s.hasNonNull("path")) catalogPaths.add(s.get("path").asText());
            if (s.hasNonNull("key"))  catalogKeys.add(s.get("key").asText());
        }
        assertTrue(catalogPaths.size() > 50,
            "menu-catalog.json looks truncated: " + catalogPaths.size() + " screens");

        List<String> unknown = new ArrayList<>();
        for (Path f : sources()) {
            // Line at a time, so javadoc and comments — which legitimately show
            // example annotations — are not mistaken for real guards.
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;

                Matcher m = MENU_ACCESS.matcher(line);
                while (m.find()) {
                    if (!catalogPaths.contains(m.group(1))) {
                        unknown.add(fileName(f) + " -> @menuAccess.canAccess('" + m.group(1) + "')");
                    }
                }
                Matcher k = PERM_CAN.matcher(line);
                while (k.find()) {
                    if (!catalogKeys.contains(k.group(1))) {
                        unknown.add(fileName(f) + " -> @perm.can('" + k.group(1) + "', ...)");
                    }
                }
            }
        }

        if (!unknown.isEmpty()) {
            fail("Authorisation annotation names a screen that is not in the catalog. "
               + "The evaluator fails closed, so these endpoints are unreachable for "
               + "everyone except a super-admin:\n  " + String.join("\n  ", unknown));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** acquira-core and its sibling modules, resolved from the module dir. */
    private static List<Path> sources() throws IOException {
        List<Path> roots = List.of(
            Paths.get("src/main/java"),
            Paths.get("../acquira-batch/src/main/java"),
            Paths.get("../acquira-common/src/main/java"));
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    private static List<Path> controllerSources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : sources()) {
            if (p.getFileName().toString().endsWith("Controller.java")) out.add(p);
        }
        return out;
    }

    private static String fileName(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.length() - ".java".length());
    }
}
