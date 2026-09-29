package com.acquira.common.security;

import org.springframework.stereotype.Component;

/**
 * Legacy path-based screen check, kept so the ~88 existing
 * {@code @PreAuthorize("@menuAccess.canAccess('/some/path')")} annotations keep
 * working unchanged. Every call now resolves through {@link PermissionEvaluator},
 * which is the single authorisation rule.
 *
 * <p>What changed underneath:
 * <ul>
 *   <li>grants are read per tenant ({@code tenant_group_perm}), not from the
 *       tenant-blind {@code sys_group_menu}</li>
 *   <li>a screen whose module is disabled for the tenant is denied even if the
 *       group was granted it</li>
 *   <li>a DENY beats an ALLOW, so a second group can never widen access</li>
 *   <li>the four-table {@code COUNT(*)} per request became a cached set lookup</li>
 * </ul>
 *
 * <p><b>Do not use for new code.</b> A path is a routing detail that gets
 * renamed; {@code menu_key} is an identity. New endpoints declare what they
 * actually need:
 * <pre>
 *   &#64;PreAuthorize("@perm.can('sales.agents', 'EDIT')")
 * </pre>
 * This class is deleted once the last path-based annotation is converted.
 */
@Component("menuAccess")
public class MenuAccessEvaluator {

    private final PermissionEvaluator perm;

    public MenuAccessEvaluator(PermissionEvaluator perm) {
        this.perm = perm;
    }

    /**
     * @param menuPath the {@code sys_menu.path} of the screen this endpoint backs
     * @return true if the caller's group in the ACTIVE tenant may view that
     *         screen, or the caller is a super-admin
     */
    @Deprecated
    public boolean canAccess(String menuPath) {
        return perm.canAccessPath(menuPath);
    }

    /**
     * Category-level grant — true if the caller may view ANY screen in
     * {@code category}. Used by endpoints backing a strip shown across a whole
     * category rather than one screen.
     */
    @Deprecated
    public boolean canAccessCategory(String category) {
        return perm.canCategory(category);
    }
}
