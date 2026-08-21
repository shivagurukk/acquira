# Acquira — Unified Settings Hub: Design & Plan

**Date:** 2026-07-11
**Status:** PLAN ONLY — no code changes. Grounded in the current `ACQUIRA_FEATURE_GUIDE.md` atlas, the existing settings controllers/pages, and the `tenant_setting` / `dashboard_config` / policy stores already in the schema.
**Goal:** One coherent **Settings** area that surfaces every configurable knob we already have, is organized so an admin can find anything in seconds, and is extensible so new settings drop into an obvious home instead of becoming another scattered page.

---

## 1. The problem today

Settings are real but **scattered across ~10 separate sidebar routes** under "Administration", each its own page, each its own mental model:

| Existing screen | Route | Backing store |
|---|---|---|
| Security Settings (lockout/password/session policy, access requests, locked users, revoke sessions) | `/admin/security-settings` | `tenant_setting` (`security.*`), `AdminController /security/*` |
| SMTP Settings | `/admin/smtp-settings` | `email_smtp_config` |
| S3 Report Storage | `/admin/s3-settings` | `tenant_setting` (`s3.*`) |
| SSO Settings (Microsoft Entra) | `/admin/sso-settings` | `tenant_setting` (`sso_*`) |
| Database Maintenance (ANALYZE/VACUUM window) | `/admin/maintenance` | `MaintenanceController` config |
| Alerts & Notifications (threshold rules) | `/admin/alerts` | `alert_rule` |
| API Management (external keys) | `/admin/api-management` | `api_key` |
| Group Management / RBAC (menus per group) | `/admin/groups` | `sys_user_group × sys_menu × sys_group_menu` |
| Tenant (Bank) Management (branding, currency, region) | `/tenants` | `tenant`, `region`, `dashboard_config` |
| User Management | `/users` | `users`, `user_tenant_access` |

**Consequences:**
- No single "Settings" entry point — admins hunt through the sidebar.
- Related knobs live apart (e.g. SMTP is under Admin, but leakage thresholds live in `tenant_setting` with no UI; `dashboard_config` KPI-tile visibility is only reachable via the Admin tenant endpoints).
- New settings have no obvious home, so they keep becoming new top-level pages.
- Some existing `tenant_setting` keys have **no UI at all** (leakage thresholds, session-timeout minutes, locale) — they can only be changed by raw SQL.

---

## 2. Design principle: **one hub, tabbed, permission-aware, tenant-scoped**

A single route `/settings` renders a **left settings-nav + right content panel** (the pattern GitHub/Stripe/Linear use). Each item in the settings-nav is a *section*; the existing pages become *panels* inside it rather than standalone routes. Three hard rules:

1. **Everything stays tenant-scoped.** Every panel reads/writes for the active tenant via `X-Tenant-Id`, exactly as the underlying controllers already do. Switching banks re-scopes the whole hub.
2. **Permission-aware rendering.** A section only appears if the user's role can use it. Bank Admin sees tenant-level config; Super Admin additionally sees cross-tenant / platform config. Non-admins see only "My Account".
3. **Additive, not a rewrite.** The hub is a *shell + navigation layer* over the controllers and pages that already work. Old routes can redirect into the hub so nothing breaks and bookmarks survive.

---

## 3. Proposed information architecture

Six top-level groups. Items marked **[EXISTS]** already have a page/endpoint; **[HAS STORE, NO UI]** exists in `tenant_setting` but has never had a screen; **[NEW]** is a sensible addition we don't have yet.

### A. My Account  *(every authenticated user)*
- **Profile** — display name, email (read-only if SSO), phone. **[partly EXISTS via user record]**
- **Password** — change password (self-service). **[EXISTS — `/users/change-password`]**
- **Security** — active sessions list + "log out all devices"; last login / failed-login info. **[EXISTS — `logout-all`, refresh-token store]**
- **Preferences** — theme (light/dark), default landing page, table density, number/date format. **[NEW — mostly client-side + a few `tenant_setting`/user prefs]**

### B. Organization  *(Bank Admin + Super Admin, current tenant)*
- **Bank Profile & Branding** — bank name, short code, logo, primary color, region. **[EXISTS — `BankController`]**
- **Currency & Locale** — base currency, currency symbol, decimal notation, date format, timezone. **[EXISTS partly via `tenant`; locale key is HAS STORE, NO UI]**
- **Dashboard Configuration** — KPI-tile visibility & order per dashboard. **[EXISTS via `AdminController /dashboard-config` — but no dedicated UI today]**
- **Fiscal / Reporting defaults** — fiscal year start, default reporting period, default volume basis (settlement). **[NEW — small, high-value]**

### C. Security & Access  *(Bank Admin + Super Admin)*
- **Password Policy** — min length, complexity, history depth, min age, expiry days, block-breached toggle. **[EXISTS — `SecurityPolicyService` / `security.*`]**
- **Login Protection** — max failed attempts, lockout minutes, IP rate limit, session timeout minutes. **[EXISTS for lockout; session-timeout is HAS STORE, NO UI]**
- **Token / Session Policy** — access-token TTL, refresh-token TTL, max concurrent sessions. **[EXISTS — `SecurityPolicyService`]**
- **Single Sign-On (SSO)** — Microsoft Entra config, enable toggle, redirect URI. **[EXISTS — `SsoController` / `sso_*`]**
- **Access Requests** — approve/reject self-service & SSO requests; pending badge. **[EXISTS — `AccessRequestController`]**
- **Locked Accounts / Sessions** — view locked users, unlock, revoke-all-sessions. **[EXISTS — `AdminController /security/*`]**

### D. Communications  *(Bank Admin + Super Admin)*
- **SMTP / Email Server** — per-tenant SMTP, test send, activate. **[EXISTS — `EmailSmtpController`]**
- **Email Templates** — statement/welcome/alert templates, variables, preview. **[EXISTS — `EmailCampaignController` templates]**
- **Notifications & Alerts** — threshold alert rules + triggered history. **[EXISTS — `AlertController`]**
- **Statement / PDF defaults** — default statement day, branding on PDFs, S3 archival on/off. **[EXISTS partly — S3 + reports dir]**

### E. Data & Integrations  *(Bank Admin + Super Admin)*
- **Report Storage (S3)** — bucket, region, prefix, credentials (encrypted). **[EXISTS — `S3SettingsController`]**
- **External DB Integrations** — Oracle/SQL Server connections, report configs, schedules. **[EXISTS — `IntegrationController` / Integration Hub]**
- **API Keys** — issue/revoke external `X-API-Key` keys. **[EXISTS — `ApiKeyController`]**
- **Revenue-Leakage Thresholds** — the `leakage.*` detection knobs. **[HAS STORE, NO UI — currently SQL-only]**
- **Data Load Mode** — REPLACE vs APPEND for transaction uploads. **[EXISTS as a property `acquira.load.mode`; NEW as a per-tenant UI toggle if desired]**

### F. Platform  *(Super Admin only — cross-tenant / system)*
- **Tenants (Banks)** — create/configure banks. **[EXISTS — `/tenants`]**
- **Roles & Menus (RBAC)** — groups and which menus each sees. **[EXISTS — `RbacController`]**
- **Database Maintenance** — nightly ANALYZE/VACUUM window, run-now. **[EXISTS — `MaintenanceController`]**
- **Backup & Restore** — trigger/track DB backups. **[EXISTS — `BackupController`]**
- **Data Migration** — legacy import, day-correction tool. **[EXISTS — `MigrationController`]**
- **AI Assistant** — provider (Ollama/Anthropic/OpenAI/Gemini), model, row/timeout guardrails. **[HAS STORE via properties; NEW as a UI — currently properties-only]**
- **Audit Log** — searchable trail (read-only, but belongs in the settings mental model). **[EXISTS — `AuditLogController`]**

---

## 4. What we *could* have that we don't (expert additions worth considering)

These are gaps a mature multi-tenant platform usually closes. Prioritized, not mandatory:

**High value / low effort:**
- **Session-timeout, locale, and leakage-threshold UIs** — the stores already exist; only a form is missing. Closes the "SQL-only setting" gap.
- **Dashboard KPI-tile editor** — endpoint exists (`dashboard_config`), no UI. Lets each bank tailor its landing tiles.
- **AI provider settings UI** — today switching provider means editing `application.properties` and restarting. A gated UI (Super Admin) that writes to `tenant_setting` and hot-reloads would remove a deploy step.

**Medium value:**
- **Notification channels beyond email** — webhook / Slack targets for alerts (schema addition).
- **Data-retention settings UI** — snapshot-retention days already a property (`acquira.retention.snapshot-days`); expose per-tenant.
- **Branding upload** — logo + primary color used in UI and PDF headers (needs a small asset store or S3 reuse).
- **Per-tenant feature flags** — turn modules on/off per bank (e.g. hide Sales suite for a bank that doesn't use it) — leverages the existing `sys_menu` grant model.

**Nice to have / governance:**
- **Settings audit & "who changed what"** — every settings write already can flow through `AuditService`; a per-section "last changed by / when" line makes config drift visible.
- **Import/Export tenant settings** — export a bank's config as JSON to clone onto a new tenant (speeds onboarding).
- **"Effective settings" view** — show the resolved value + its source (default vs tenant override vs env), mirroring the PLAIN/ENCRYPTED/AWS secret-resolution model already in the app.
- **Change preview / dry-run** for destructive platform actions (maintenance, migration) — some already confirm; standardize it.

---

## 5. Backend approach (no new architecture, just consolidation)

- **Reuse every existing controller.** The hub is a frontend composition; panels call the same endpoints they call today (`AdminController`, `S3SettingsController`, `EmailSmtpController`, `SsoController`, `MaintenanceController`, `AlertController`, `ApiKeyController`, `RbacController`, `BankController`, `AccessRequestController`, `IntegrationController`, `AuditLogController`).
- **One thin read-facade (optional, recommended):** a `GET /api/settings/catalog` that returns, for the active tenant + role, the list of visible sections and each setting's current value + metadata (label, type, default, source, editable-by-role). This lets the frontend render the nav and forms generically and is the single place to register a **new** setting. Writes still go to the specific existing endpoints (keeps validation where it lives).
- **Settings registry pattern:** define each setting once (key, group, type, default, min/max, role, validation, "hot-reloadable vs restart-required"). `tenant_setting` remains the store for string/JSON/boolean/number values; dedicated tables (`email_smtp_config`, `api_key`, `alert_rule`, `integration_*`) keep their own richer schemas and are surfaced as "managed sections" rather than key/value rows.
- **Migration discipline:** any genuinely new key is just a `tenant_setting` row (no DDL). New *tables* (only if we add webhook channels, feature flags, etc.) follow the idempotent-ALTER / schema-locations rule already in the project. Sidebar changes go through `sys_menu` + `sys_group_menu` as usual.

---

## 6. Frontend approach

- **New route `/settings`** (and `/settings/:section/:subsection` for deep links) rendering a two-pane `SettingsLayout`: searchable left nav (grouped A–F above) + right panel.
- **Panels are the existing pages, refactored into embeddable components** — not rewritten. Each current `*Settings.jsx` becomes a panel mounted inside the hub; the standalone routes **redirect** into the hub (`/admin/smtp-settings` → `/settings/communications/smtp`) so bookmarks and the DB-driven menu still work during transition.
- **Global affordances:** a settings search box ("find any setting"), a per-panel "unsaved changes" guard, consistent Save/Reset/Test buttons, and a "last changed by / when" line where audit data exists.
- **Design system:** reuse the existing CSS-variable tokens and the change-password/login aesthetic already in place — restrained financial-instrument look, no new visual language.
- **Permission gating:** the settings-nav is filtered by role client-side AND enforced server-side by the underlying endpoints (defense in depth — same model as today's `RoleGuard`).

---

## 7. Migration path (phased, low-risk)

1. **Phase 0 — Shell:** build `/settings` with the left-nav + empty panels wired to *existing* endpoints for the 3–4 most-used sections (Security, SMTP, Bank Profile, API Keys). Add one `sys_menu` "Settings" row. Nothing removed yet.
2. **Phase 1 — Absorb existing pages:** mount the remaining current pages as panels; convert old routes to redirects. Sidebar collapses ~10 admin rows into one "Settings" entry (+ keep deep-linkable sub-items if desired).
3. **Phase 2 — Close the no-UI gaps:** add forms for the stores that exist but have no screen (session-timeout, locale, leakage thresholds, dashboard KPI tiles).
4. **Phase 3 — New settings & registry:** introduce the `GET /api/settings/catalog` facade + the "effective settings" view, AI-provider UI, feature flags — each dropped into its group with no new top-level page.

Each phase ships independently and is reversible.

---

## 8. Open decisions for you

1. **Collapse or coexist?** Fully replace the ~10 admin routes with `/settings` (cleanest), or keep them as redirects indefinitely (safest)?
2. **Section granularity** — is the A–F grouping right, or do you want Communications folded into Organization, etc.?
3. **Scope of v1** — ship Phase 0+1 (consolidation only) first, or go straight through Phase 2 (close the SQL-only gaps) since those are high-value/low-effort?
4. **The catalog facade** — worth building the `GET /api/settings/catalog` registry now (pays off as settings grow), or defer and hard-wire panels for v1?
5. **Which "could-have" additions** from §4 are actually wanted (AI-provider UI, feature flags, retention, branding upload, settings export)?

Tell me your calls on 1–5 and I'll turn the chosen scope into an implementation plan (routes, `sys_menu` SQL, component list, endpoint map) — still no code until you say go.
