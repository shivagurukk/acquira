package com.acquira.config;

/**
 * REMOVED: TenantAspect was setting DB tenant context via SET LOCAL on every
 * service/repository call, which was redundant with TenantWebMvcConfig's
 * parameterized set_config() interceptor.
 *
 * Having both caused:
 *   1. Extra SQL round-trip per service call
 *   2. Confusion about which mechanism is authoritative
 *   3. Potential conflicts between session-level and transaction-level settings
 *
 * Tenant context is now handled EXCLUSIVELY by:
 *   - TenantWebMvcConfig (for HTTP requests via interceptor)
 *   - TenantBatchStepListener (for batch jobs via step listener)
 *
 * This file is kept as documentation. Safe to delete.
 */
// @Aspect — REMOVED
// @Component — REMOVED
