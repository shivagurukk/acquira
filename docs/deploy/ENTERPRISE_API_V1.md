# Enterprise API v1 — deploy notes (2026-09-05)

Everything below is **tenant-scoped**: webhook endpoints, their secrets and
delivery logs, API-key quotas, and idempotency records all live and are
enforced per tenant.

## What shipped

1. **Outbound webhooks** — per-tenant endpoints subscribed to event types,
   HMAC-SHA256-signed POST deliveries with retry/backoff (1m/5m/15m/1h/6h, 6
   attempts, auto-pause after 25 consecutive failures), delivery ledger with
   redeliver, admin UI (Settings → Data & Integrations → Webhooks, and
   `/admin/webhooks`).
   Events: `ingest.run.completed`, `ingest.run.failed`,
   `integration.pull.failed`, `apikey.expiring` (7/3/1 days out),
   `webhook.test`.
2. **API-key hardening** — in-place rotation (`POST /api/admin/api-keys/{id}/rotate`),
   daily quota (`quota_per_day`, enforced with `X-RateLimit-*-Day` headers),
   CIDR support in the IP allowlist, failed-auth requests now logged to
   `api_request_log`, and a BCrypt verification cache that removes the
   ~100ms-per-request hash from the hot path (revoke/rotate evict it; the row
   is still re-read and is_active/expiry/IP re-checked every request).
3. **Idempotency keys** — `Idempotency-Key` header honored on non-GET
   `/api/v1/**` + `/api/external/**` (stored response replayed with
   `X-Idempotency-Replayed: true`; 24h retention). Forward wiring — the
   external surface is read-only today.
4. **springdoc** — generated OpenAPI at `/v3/api-docs/{external,internal}` +
   Swagger UI. **Dev ON, prod OFF** until `API_DOCS_ENABLED=true`.
5. **Actuator** — health/liveness/readiness only (`/actuator/health`),
   permitAll for k8s probes, details hidden.

## Prod/UAT apply steps

1. **Migration** (idempotent; also registered in dev schema-locations):
   ```
   psql -f acquira-core/src/main/resources/db/migration/V2026_09_05_05__enterprise_api_v1.sql
   ```
2. Rebuild + redeploy (new dependency: spring-boot-starter-actuator).
3. k8s probes can move to `/actuator/health/liveness` and `/readiness`.
4. Leave `WEBHOOKS_ALLOW_PRIVATE_URLS` unset in prod (defaults false =
   https-only, private/internal targets rejected — SSRF guard).
5. `API_DOCS_ENABLED=true` only when publishing the generated docs is a
   conscious choice.

## Integrator contract (webhooks)

- Delivery: HTTPS POST, JSON body
  `{id, type, createdAt, tenantId, data{...}}`.
- Headers: `X-Acquira-Event`, `X-Acquira-Event-Id` (stable across retries —
  de-dupe on it), `X-Acquira-Delivery-Id`,
  `X-Acquira-Signature: sha256=<hex HMAC-SHA256(secret, raw body)>`.
- Secret: generated server-side (`whsec_…`), shown once, stored encrypted
  (CryptoService), rotatable from the UI.
- Any 2xx acknowledges; anything else retries on the backoff ladder.

## Known limits / follow-ups

- Rate limiter, daily quota, verification cache and the webhook poller are
  in-memory / single-replica by design (same constraint as before); Redis is
  the prerequisite for multi-replica (see PLAN_POD_SPLIT).
- No grace overlap on key rotation (old secret dies instantly) — documented in
  the UI; zero-downtime rotation = second key + cutover.
- Digest-sent and reconciliation-mismatch events not yet published as
  webhooks (easy adds via WebhookDispatcher.dispatch).
- SFTP/REST Integration Hub connectors, SAML/generic OIDC, SCIM, cursor
  pagination: still open (tier 2/3 of the enterprise roadmap).
