package com.acquira.common.security;

import java.util.Set;

/**
 * The authenticated identity of an external API-key request.
 *
 * Lives in acquira-common so every module (core data endpoints, pdf report endpoints)
 * can read it — acquira-pdf cannot depend on acquira-core (that would be circular).
 *
 * Stashed as a request attribute ({@link #ATTR}) by ApiKeyAuthFilter so downstream
 * controllers can read the resolved tenant + granted scopes without re-authenticating.
 * The key IS the tenant boundary: {@code tenantId}/{@code tenantCode} come from the key
 * row, never from a client-supplied parameter.
 */
public final class ApiKeyPrincipal {

    /** Request attribute name under which the principal is stored. */
    public static final String ATTR = "acquira.apiKeyPrincipal";

    private final Long keyId;
    private final Long tenantId;
    private final String tenantCode;
    private final Set<String> scopes;
    private final boolean staticKey;

    public ApiKeyPrincipal(Long keyId, Long tenantId, String tenantCode, Set<String> scopes, boolean staticKey) {
        this.keyId = keyId;
        this.tenantId = tenantId;
        this.tenantCode = tenantCode;
        this.scopes = scopes != null ? scopes : Set.of();
        this.staticKey = staticKey;
    }

    public Long getKeyId() { return keyId; }
    public Long getTenantId() { return tenantId; }
    public String getTenantCode() { return tenantCode; }
    public Set<String> getScopes() { return scopes; }
    public boolean isStaticKey() { return staticKey; }

    /** A static break-glass key is all-tenant and carries no stored scope set → treat as full read access. */
    public boolean hasScope(String scope) {
        return staticKey || scopes.contains(scope);
    }
}
