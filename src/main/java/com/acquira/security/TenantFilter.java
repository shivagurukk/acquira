package com.acquira.security;

/**
 * SECURITY FIX: TenantFilter has been DISABLED.
 *
 * Previously this filter blindly set the tenant context from the X-Tenant-Id header
 * WITHOUT validating user access — creating a critical IDOR vulnerability where any
 * authenticated user could access any tenant's data.
 *
 * Tenant validation is now handled EXCLUSIVELY in JwtRequestFilter, which:
 * 1. Validates the user's JWT
 * 2. Checks user.isActive()
 * 3. Validates the X-Tenant-Id header against the user's allowed tenants
 * 4. Falls back to default tenant if header is missing/invalid
 *
 * This class is kept as a placeholder to avoid Spring autowiring issues.
 * It can be safely deleted once all references are removed.
 */
// @Component — REMOVED to prevent this filter from registering
public class TenantFilter {
    // Intentionally empty — this filter is disabled
}
