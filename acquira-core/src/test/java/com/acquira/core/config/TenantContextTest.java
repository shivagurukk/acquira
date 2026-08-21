package com.acquira.core.config;

import com.acquira.common.config.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TenantContext} — the ThreadLocal that carries the
 * active write-tenant, the visible-tenants read scope, and the effective role.
 *
 * Covers: set/get of each ThreadLocal, auto-seeding of visibleTenants from
 * setCurrentTenant, the P2-5 fail-loud behaviour when nothing is set, the
 * currentTenant fallback, thread isolation, and clear().
 */
class TenantContextTest {

    @BeforeEach
    @AfterEach
    void reset() {
        TenantContext.clear();
    }

    // ---- currentTenant ------------------------------------------------------

    @Test
    @DisplayName("setCurrentTenant then getCurrentTenant returns same value")
    void setAndGetCurrentTenant() {
        TenantContext.setCurrentTenant(42L);
        assertEquals(42L, TenantContext.getCurrentTenant());
    }

    @Test
    @DisplayName("getCurrentTenant is null when nothing set")
    void currentTenantNullByDefault() {
        assertNull(TenantContext.getCurrentTenant());
    }

    @Test
    @DisplayName("setCurrentTenant overwrites a previous value")
    void overwriteCurrentTenant() {
        TenantContext.setCurrentTenant(1L);
        TenantContext.setCurrentTenant(2L);
        assertEquals(2L, TenantContext.getCurrentTenant());
    }

    @Test
    @DisplayName("setCurrentTenant accepts a null tenant id")
    void setCurrentTenantNull() {
        TenantContext.setCurrentTenant(null);
        assertNull(TenantContext.getCurrentTenant());
    }

    // ---- visibleTenants auto-seeding ---------------------------------------

    @Test
    @DisplayName("setCurrentTenant auto-seeds visibleTenants with that single tenant")
    void setCurrentTenantSeedsVisible() {
        TenantContext.setCurrentTenant(7L);
        assertEquals(List.of(7L), TenantContext.getVisibleTenants());
    }

    @Test
    @DisplayName("auto-seed does not clobber an explicitly-set visible list")
    void setCurrentTenantKeepsExplicitVisible() {
        TenantContext.setVisibleTenants(List.of(10L, 20L, 30L));
        TenantContext.setCurrentTenant(10L);
        assertEquals(List.of(10L, 20L, 30L), TenantContext.getVisibleTenants());
    }

    // ---- visibleTenants explicit -------------------------------------------

    @Test
    @DisplayName("setVisibleTenants then getVisibleTenants returns the same list")
    void setAndGetVisibleTenants() {
        TenantContext.setVisibleTenants(List.of(1L, 2L, 3L));
        assertEquals(List.of(1L, 2L, 3L), TenantContext.getVisibleTenants());
    }

    @Test
    @DisplayName("getVisibleTenants returns the seeded single tenant after setCurrentTenant")
    void visibleFromCurrent() {
        TenantContext.setCurrentTenant(99L);
        assertEquals(List.of(99L), TenantContext.getVisibleTenants());
    }

    @Test
    @DisplayName("P2-5: getVisibleTenants throws IllegalStateException when nothing set")
    void visibleThrowsWhenEmpty() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                TenantContext::getVisibleTenants);
        assertTrue(ex.getMessage().contains("No tenant in context"));
    }

    @Test
    @DisplayName("setVisibleTenants accepts an empty list and returns it (no throw)")
    void visibleEmptyListReturned() {
        TenantContext.setVisibleTenants(List.of());
        assertEquals(List.of(), TenantContext.getVisibleTenants());
    }

    @Test
    @DisplayName("a multi-tenant visible scope is preserved exactly")
    void visibleMultiTenantPreserved() {
        TenantContext.setVisibleTenants(List.of(5L, 9L, 13L, 21L));
        assertEquals(4, TenantContext.getVisibleTenants().size());
        assertTrue(TenantContext.getVisibleTenants().containsAll(List.of(5L, 9L, 13L, 21L)));
    }

    // ---- role ---------------------------------------------------------------

    @Test
    @DisplayName("setCurrentRole then getCurrentRole returns same value")
    void setAndGetRole() {
        TenantContext.setCurrentRole("ROLE_SUPER_ADMIN");
        assertEquals("ROLE_SUPER_ADMIN", TenantContext.getCurrentRole());
    }

    @Test
    @DisplayName("getCurrentRole is null by default")
    void roleNullByDefault() {
        assertNull(TenantContext.getCurrentRole());
    }

    @Test
    @DisplayName("setCurrentRole overwrites a previous role")
    void overwriteRole() {
        TenantContext.setCurrentRole("ROLE_BANK_USER");
        TenantContext.setCurrentRole("ROLE_ADMIN");
        assertEquals("ROLE_ADMIN", TenantContext.getCurrentRole());
    }

    // ---- clear --------------------------------------------------------------

    @Test
    @DisplayName("clear removes tenant, visible scope and role")
    void clearRemovesEverything() {
        TenantContext.setCurrentTenant(5L);
        TenantContext.setCurrentRole("ROLE_ADMIN");
        TenantContext.clear();
        assertNull(TenantContext.getCurrentTenant());
        assertNull(TenantContext.getCurrentRole());
        assertThrows(IllegalStateException.class, TenantContext::getVisibleTenants);
    }

    @Test
    @DisplayName("clear is safe to call when nothing was set")
    void clearIsIdempotent() {
        assertDoesNotThrow(TenantContext::clear);
        assertDoesNotThrow(TenantContext::clear);
    }

    // ---- thread isolation ---------------------------------------------------

    @Test
    @DisplayName("tenant set on one thread is not visible on another")
    void threadIsolation() throws InterruptedException {
        TenantContext.setCurrentTenant(111L);

        final Long[] otherThreadValue = new Long[1];
        Thread other = new Thread(() -> otherThreadValue[0] = TenantContext.getCurrentTenant());
        other.start();
        other.join();

        assertNull(otherThreadValue[0], "child thread must not see parent's tenant");
        assertEquals(111L, TenantContext.getCurrentTenant(), "parent thread keeps its value");
    }

    @Test
    @DisplayName("each thread maintains its own independent tenant")
    void perThreadIndependentValues() throws InterruptedException {
        TenantContext.setCurrentTenant(1L);

        final Long[] childValue = new Long[1];
        Thread child = new Thread(() -> {
            TenantContext.setCurrentTenant(2L);
            childValue[0] = TenantContext.getCurrentTenant();
            TenantContext.clear();
        });
        child.start();
        child.join();

        assertEquals(2L, childValue[0]);
        assertEquals(1L, TenantContext.getCurrentTenant());
    }
}
