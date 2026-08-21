package com.acquira.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Scope authorization helper for external API-key controllers.
 *
 * Lives in acquira-common so both acquira-core (data endpoints) and acquira-pdf
 * (report endpoints) can call it.
 *
 * The taxonomy mirrors the UI's permission list (ApiManagement.jsx PERMISSIONS)
 * plus read:reports for the PDF statement surface:
 *   read:transactions, read:merchants, read:analytics, read:finance, read:reports, write:upload
 *
 * Usage in a controller method:
 *   ApiScopes.require(request, ApiScopes.READ_TRANSACTIONS);
 * Throws {@link InsufficientScopeException} (mapped to 403) when the key lacks it.
 */
public final class ApiScopes {

    public static final String READ_TRANSACTIONS = "read:transactions";
    public static final String READ_MERCHANTS    = "read:merchants";
    public static final String READ_ANALYTICS    = "read:analytics";
    public static final String READ_FINANCE      = "read:finance";
    public static final String READ_REPORTS      = "read:reports";
    public static final String WRITE_UPLOAD      = "write:upload";

    private ApiScopes() {}

    public static ApiKeyPrincipal principal(HttpServletRequest request) {
        Object p = request.getAttribute(ApiKeyPrincipal.ATTR);
        if (p instanceof ApiKeyPrincipal principal) return principal;
        throw new InsufficientScopeException("Request is not API-key authenticated");
    }

    /** Assert the authenticated key holds the given scope, else throw (→ 403). */
    public static void require(HttpServletRequest request, String scope) {
        ApiKeyPrincipal p = principal(request);
        if (!p.hasScope(scope)) {
            throw new InsufficientScopeException("API key is missing required scope: " + scope);
        }
    }

    /** Thrown when the key is missing a required scope. Mapped to 403 by the controller advice. */
    public static class InsufficientScopeException extends RuntimeException {
        public InsufficientScopeException(String message) { super(message); }
    }
}
