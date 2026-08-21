package com.acquira.core.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.TenantSetting;
import com.acquira.common.repository.TenantSettingRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for the admin-configured security policy.
 *
 * Settings are persisted in {@code tenant_setting} under {@code security.<snake_key>}
 * by Admin &gt; Security Settings (e.g. {@code security.min_length},
 * {@code security.access_token_minutes}). This service reads them back with the
 * same snake_case keys the UI writes, applies sensible defaults when a key is
 * unset, and exposes typed accessors so callers never parse raw strings.
 *
 * Tenant resolution: callers may pass an explicit tenantId, or null to use the
 * tenant currently bound to the request via {@link TenantContext}. When neither
 * is available the built-in defaults apply (fail-safe, never fail-open beyond
 * the documented defaults).
 */
@Service
public class SecurityPolicyService {

    private final TenantSettingRepository settingRepository;

    public SecurityPolicyService(TenantSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    private Long resolve(Long tenantId) {
        if (tenantId != null) return tenantId;
        try { return TenantContext.getCurrentTenant(); } catch (Exception e) { return null; }
    }

    /** Load all {@code security.*} entries for the (resolved) tenant, keyed without the prefix. */
    private Map<String, String> load(Long tenantId) {
        Map<String, String> map = new HashMap<>();
        Long t = resolve(tenantId);
        if (t == null) return map;
        try {
            for (TenantSetting s : settingRepository.findByTenant_TenantId(t)) {
                String k = s.getKey();
                if (k != null && k.startsWith("security.")) {
                    map.put(k.substring("security.".length()), s.getValue());
                }
            }
        } catch (Exception ignored) { /* fall back to defaults */ }
        return map;
    }

    private int getInt(Map<String, String> m, String key, int def) {
        String v = m.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    private boolean getBool(Map<String, String> m, String key, boolean def) {
        String v = m.get(key);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v.trim());
    }

    // ===== Password rules =====
    public PasswordPolicy passwordPolicy(Long tenantId) {
        Map<String, String> m = load(tenantId);
        PasswordPolicy p = new PasswordPolicy();
        p.minLength           = Math.max(6, getInt(m, "min_length", 8));
        p.requireUppercase    = getBool(m, "require_uppercase", true);
        p.requireLowercase    = getBool(m, "require_lowercase", true);
        p.requireDigit        = getBool(m, "require_digit", true);
        p.requireSpecialChar  = getBool(m, "require_special_char", true);
        p.blockBreached       = getBool(m, "block_breached_passwords", true);
        p.blockUserInfo       = getBool(m, "block_user_info_in_password", true);
        p.historyCount        = Math.max(0, getInt(m, "password_history_count", 5));
        p.minPasswordAgeHours = Math.max(0, getInt(m, "min_password_age_hours", 0));
        // Days after which a password must be changed. 0 = never expires.
        // Enforced at login (AuthController) by flipping mustChangePassword when
        // passwordChangedAt + N days is in the past. Local-password users only.
        p.passwordExpiryDays  = Math.max(0, getInt(m, "password_expiry_days", 90));
        return p;
    }

    // ===== Sessions & tokens =====
    /** Access-token lifetime in millis (default 30 min; non-positive config falls back to 30). */
    public long accessTokenMillis(Long tenantId) {
        int min = getInt(load(tenantId), "access_token_minutes", 30);
        if (min <= 0) min = 30;
        return min * 60_000L;
    }

    /** Refresh-token lifetime in millis (default 7 days; non-positive config falls back to 7). */
    public long refreshTokenMillis(Long tenantId) {
        int days = getInt(load(tenantId), "refresh_token_days", 7);
        if (days <= 0) days = 7;
        return days * 24L * 60 * 60 * 1000;
    }

    /** Max simultaneous sessions per user; 0 = unlimited. */
    public int maxConcurrentSessions(Long tenantId) {
        return Math.max(0, getInt(load(tenantId), "max_concurrent_sessions", 0));
    }

    // ===== Lockout & rate limiting =====
    /** Failed logins before the account locks (default 5). */
    public int maxFailedAttempts(Long tenantId) {
        int v = getInt(load(tenantId), "max_failed_attempts", 5);
        return v > 0 ? v : 5;
    }

    /** Minutes an account stays locked (default 15). */
    public int lockoutDurationMinutes(Long tenantId) {
        int v = getInt(load(tenantId), "lockout_duration_minutes", 15);
        return v > 0 ? v : 15;
    }

    /** Per-(IP, username) login attempts allowed per minute (default 10). */
    public int rateLimitPerMinute(Long tenantId) {
        int v = getInt(load(tenantId), "rate_limit_per_minute", 10);
        return v > 0 ? v : 10;
    }

    /** Plain holder for the resolved password policy. */
    public static class PasswordPolicy {
        public int minLength = 8;
        public boolean requireUppercase = true;
        public boolean requireLowercase = true;
        public boolean requireDigit = true;
        public boolean requireSpecialChar = true;
        public boolean blockBreached = true;
        public boolean blockUserInfo = true;
        public int historyCount = 5;
        public int minPasswordAgeHours = 0;
        public int passwordExpiryDays = 90;
    }
}
