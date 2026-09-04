# Nexus Platform — Overview and Security / Risk Assessment

**Prepared for:** Executive Leadership · Information Security · Cybersecurity · Risk Management · Internal Audit · Compliance · Legal & Privacy · IT / Technology Management
**Subject system:** "Nexus" — the internal merchant-analytics and card-acquiring reporting platform (engineering codebase: **Acquira**)
**Assessment basis:** Review of the application source code, build configuration, and Kubernetes/AWS deployment manifests in the project repository
**Status:** Internal working document — for review and due diligence, not a final sign-off
**Date:** 30 August 2026

---

## How to read this document

This is an internal assessment of a system the organization builds and operates itself, so most technical statements here are drawn directly from the source code and can be verified in the repository. However, a security assessment covers more than code — it also covers operational practices (backups, penetration testing, staff vetting, certifications) that are **not** visible in a codebase. To keep the two clearly separated, every material statement is tagged:

| Tag | Meaning |
|-----|---------|
| **[Confirmed – code]** | Directly evidenced in the source code, build files, or deployment manifests. |
| **[Design inference]** | A reasonable conclusion from the architecture, not an explicit statement. Should be validated. |
| **[Requires confirmation]** | An operational or contractual fact not determinable from code. Owner must confirm. |

A short note on naming: throughout this document "Nexus" refers to the platform whose engineering codebase is named **Acquira** (Java package `com.acquira`). The business/product name and the code name should be reconciled and stated consistently in the final version. **[Requires confirmation]**

---

## 1. Executive summary

**What Nexus is.** Nexus is an in-house web platform that ingests raw card-payment transaction files from acquiring/processing operations, prices each transaction (interchange, scheme fees, merchant service fees, VAT), and turns the result into merchant-level analytics, dashboards, and formatted PDF reports. It is a **multi-tenant** system: several operating entities/countries (observed: UAE, Bahrain, Egypt, Oman) are served from one deployment, with each tenant's data logically separated. **[Confirmed – code]**

**Problem it solves.** Payment acquirers receive large, messy transaction files and must (a) calculate what each transaction actually earns after network and interchange costs, and (b) give internal teams, sales/relationship managers, and merchants a clear view of volumes, revenue, and margins. Nexus automates that pricing and reporting, replacing manual spreadsheet work with a repeatable pipeline and self-service dashboards.

**Who uses it.** Internal staff — analysts, finance, relationship managers, and administrators — authenticate and view dashboards or generate reports. Administrators manage tenants, users, menu/role grants, and rate cards. There is no evidence in the code of direct external merchant self-service login; merchant-facing output is produced as PDF reports. **[Design inference]**

**How it is built and run.** A Java 21 / Spring Boot 3.2 backend (modules: `acquira-core`, `acquira-batch`, `acquira-pdf`, `acquira-ai`, `acquira-common`) with a React 19 single-page frontend, backed by PostgreSQL. It is packaged as containers and deployed on Kubernetes, with manifests written to target AWS (RDS for the database, S3 and AWS Secrets Manager referenced, an ALB/ingress front door). **[Confirmed – code]**

**Information it processes.** Payment transaction records (card scheme, masked card metadata, amounts, currencies, merchant/terminal identifiers, dates/times), merchant master data, fee/rate configuration, and platform user accounts (usernames, hashed passwords, email addresses, roles). It is **card-acquiring transaction data**, which is commercially sensitive and, depending on file contents, may touch PCI-DSS scope. Whether full/primary account numbers (PANs) are present in ingested files is the single most important data-sensitivity question to resolve. **[Requires confirmation]**

**Security posture — headline.** The application shows deliberate, above-average attention to multi-tenant isolation and injection defense: tenant scoping is enforced both in application code and by PostgreSQL row-level security, security response headers are set, passwords are hashed with a modern encoder, authentication is stateless JWT with an email one-time-password MFA option, and the AI query feature is wrapped in an unusually thorough SQL guard. Balanced against this, several items typical of a self-hosted platform still need attention or confirmation: default/example credentials in committed manifests, a single-replica core service (no built-in high availability), a database schema-init switch that can be destructive if mis-set, broad statement timeouts during ingestion, and the operational controls (backups, DR, pen testing, logging retention) that cannot be judged from code.

**Compliance and privacy — headline.** As a system processing financial transaction data for regulated payment entities, the relevant regimes are likely **PCI-DSS**, local data-protection law (e.g., Bahrain PDPL, and GDPR where EU cardholders are involved), and the operating entities' financial-regulator obligations. Personal data is limited but present (staff accounts, potentially cardholder-linked identifiers). Formal DPA/subprocessor governance and a data-classification decision on ingested files are the key gaps.

### Executive risk summary

| Area | Position | Action |
|------|----------|--------|
| **Critical concern** | Data classification of ingested files (PAN / PCI scope) is undecided. This determines whether the whole platform is in PCI-DSS scope. | Decide and document what card data enters Nexus; scope PCI accordingly. **[Requires confirmation]** |
| **High-risk** | Committed example secrets (`change-me...`, `postgres/postgres`) and a `SQL_INIT_MODE=always` switch that can drop/recreate schema if promoted to production. | Confirm production uses AWS Secrets Manager + `SQL_INIT_MODE=never`; verify no default secret ever reaches prod. **[Requires confirmation]** |
| **High-risk** | Single-replica core service = single point of failure; no documented backup/DR/RPO/RTO. | Define availability target, backup schedule, and DR plan; confirm RDS backups. **[Requires confirmation]** |
| **Medium** | Optional AI assistant can send user questions (with schema, not data rows) to external LLM providers (Anthropic/OpenAI/Google) when configured. | Confirm which provider is enabled in production and whether external egress is permitted. **[Confirmed – code / Requires confirmation on config]** |
| **Important dependency** | AWS (compute, RDS, S3, Secrets Manager), PostgreSQL, and — for ingestion — external source databases (MSSQL/Oracle/Postgres). | Map and monitor these as critical dependencies. **[Confirmed – code]** |
| **Recommended next steps** | (1) Resolve PAN/PCI scope. (2) Obtain backup/DR/pen-test/logging evidence. (3) Lock production secrets and the schema-init switch. (4) Confirm AI provider posture. (5) Complete the gaps table in §11. | — |

---

## 2. What Nexus is (plain-language overview)

Nexus takes payment transaction data and answers, at scale, two questions acquirers care about: *"how much did we process?"* and *"how much did we actually make after the card networks took their cut?"*

The flow, in everyday terms:

1. **Files come in.** Transaction files (Excel/CSV) are uploaded through the web application or pulled automatically from source systems. Different countries and processors use different file layouts, and Nexus normalizes them into a common shape. **[Confirmed – code]**
2. **Transactions are priced.** Each transaction is matched to the applicable rate card and the fees are computed — interchange (paid to the card issuer), scheme fees (paid to Visa/Mastercard/local networks), the merchant service fee (what the merchant is charged), and VAT. **[Confirmed – code]**
3. **Data is summarized.** Priced transactions are rolled up into daily and monthly warehouse tables (by bank, merchant, scheme, channel, MCC/industry, terminal, destination) so dashboards load quickly over long time ranges. **[Confirmed – code]**
4. **People see it.** Users log in and view dashboards, leaderboards, and trend charts, or generate polished PDF reports for a specific merchant or period. An optional AI assistant lets users ask questions in plain English and get back a chart/table. **[Confirmed – code]**

Because it serves multiple operating entities from one system, a central design goal is that **one tenant can never see another tenant's data** — this is enforced in more than one layer (see §6).

**Deployment model.** Nexus is an internally developed, self-hosted application — not a purchased SaaS product. It runs as containers on Kubernetes, and the deployment manifests are written to run on AWS. In other words, the organization is both the vendor and the operator; "vendor risk" here is largely **internal build-and-run risk plus cloud-provider (AWS) risk**, not third-party SaaS risk. **[Confirmed – code / Design inference]**

---

## 3. Business use case and operational impact

**Why it exists.** It removes manual, error-prone fee reconciliation and reporting, gives finance a defensible revenue/margin picture per merchant and per network, gives relationship managers merchant-level performance views, and produces consistent merchant reports without hand-built spreadsheets.

**Who depends on it.** Finance and revenue-assurance, sales/relationship management, operations, and management reporting. Because it computes fee and margin figures used for internal decisions (and potentially merchant-facing statements), **accuracy of its pricing logic is itself a business risk**, independent of security.

**Impact if unavailable.** Short outages are tolerable — this is an analytics/reporting system, not a real-time payment-authorization system, so cardholder transactions are unaffected by a Nexus outage. **[Design inference]** The impact of a longer outage is delayed reporting, delayed month-end fee/margin figures, and loss of self-service analytics — an operational and reporting inconvenience rather than a payment-processing failure. This should be confirmed against how the outputs are actually used (e.g., if any merchant billing or settlement depends on Nexus figures, the impact rating rises). **[Requires confirmation]**

---

## 4. Solution architecture

**Confirmed from the codebase and manifests:**

- **Frontend:** React 19 single-page application (Vite build, MUI component library, Recharts). Served as static files behind the ingress. Talks to the backend over HTTPS REST under `/api`. **[Confirmed – code]**
- **Backend:** Java 21, Spring Boot 3.2, multi-module Maven build:
  - `acquira-core` — main web API, dashboards, scheduling, ingestion orchestration, email, external-DB pulls.
  - `acquira-batch` — Spring Batch file ingestion and fee/summary computation.
  - `acquira-pdf` — report generation (Playwright/Chromium renders HTML to PDF; also OpenPDF/JFreeChart).
  - `acquira-ai` — optional natural-language query assistant.
  - `acquira-common` — shared models, security, tenant context.
- **Database:** PostgreSQL 16 (local container for dev; AWS RDS in the target deployment). Holds staging, priced "fact," summary/warehouse, and dimension/reference tables. **[Confirmed – code]**
- **Storage:** A persistent volume for generated reports and uploads (`/opt/acquira/reports`, `/opt/acquira/uploads`); S3 is referenced for the AWS deployment. **[Confirmed – code]**
- **Ingress / edge:** ingress-nginx locally, written to swap to an **AWS ALB**; large upload bodies (2 GB) and long timeouts (600 s) are configured for batch/PDF calls. **[Confirmed – code]**
- **Secrets:** Kubernetes Secrets locally, written to be replaced by **AWS Secrets Manager via External Secrets** in production. A dedicated AES-256 application key encrypts stored integration secrets (SMTP/S3/source-DB credentials). **[Confirmed – code]**
- **Auth services:** Self-contained — Spring Security with JWT bearer tokens and a database-backed user store; email OTP for MFA. No external IdP integration is present in the code. **[Confirmed – code]**
- **External integrations:** Scheduled ingestion can pull from external **MSSQL, Oracle, and PostgreSQL** source databases; SMTP for email; optional external **LLM providers** (Anthropic, OpenAI, Google Gemini) or a local Ollama model for the AI assistant. **[Confirmed – code]**

**Notable architecture facts for reviewers:**

- The `acquira-core` service is deliberately pinned to **a single replica** (it runs the email queue, external-DB pulls, schedulers, and batch jobs, which would duplicate or collide if run twice). Horizontal scaling / high availability would require a web/worker split that is not yet built. This is a documented **availability single point of failure**. **[Confirmed – code]**
- The build includes **OWASP dependency-check** as a Maven plugin, indicating dependency vulnerability scanning is part of the build story. **[Confirmed – code]**
- Health probes are **TCP-only** (no Spring Actuator on the classpath), so orchestration knows the port is open but not whether the app is internally healthy. **[Confirmed – code]**

**What must be requested to complete the architecture picture:** a current architecture/network diagram, the actual AWS account/region layout, RDS configuration (Multi-AZ? encryption? backup retention?), how S3 buckets are configured, and whether any component is exposed to the public internet beyond the intended web front door. **[Requires confirmation]**

---

## 5. Data: what it holds, how it flows, how sensitive it is

**Logical data flow:** `Source files / source DBs → upload or scheduled pull → staging tables (per ingest run) → normalization & fee pricing → "fact" transactions → daily/monthly summary warehouse → dashboards, PDF reports, AI queries → persistent storage / email delivery`. **[Confirmed – code]**

**Data categories and sensitivity:**

| Category | Present in Nexus? | Sensitivity |
|----------|-------------------|-------------|
| Payment transaction records (amounts, currency, scheme, card type, terminal/merchant IDs, timestamps) | Yes **[Confirmed – code]** | High — commercially sensitive; possible PCI scope |
| Full card numbers (PAN) / sensitive authentication data | **Unknown — must confirm** | Critical if present; drives PCI-DSS scope |
| Merchant master data (names, MIDs, MCC, city, risk level, sales owner) | Yes **[Confirmed – code]** | Medium–High (confidential business data) |
| Fee / rate-card configuration | Yes **[Confirmed – code]** | High (competitively sensitive; integrity-critical) |
| Platform user accounts (username, email, hashed password, roles, MFA tokens) | Yes **[Confirmed – code]** | High (credentials / PII) |
| AI query history (question text + generated SQL, per user/tenant) | Yes **[Confirmed – code]** | Medium |
| System/application logs | Yes **[Confirmed – code]** | Medium (may contain identifiers) |

**Highest-impact-if-compromised:** (1) any cardholder PAN data, if present; (2) the rate-card / fee configuration (integrity — wrong rates silently corrupt revenue reporting); (3) user credentials and tenant-scoping metadata (a break here is a cross-tenant data exposure).

**Trust boundaries to note:** browser ↔ web front door; web front door ↔ backend; backend ↔ PostgreSQL; backend ↔ external source databases (pull); backend ↔ SMTP; backend ↔ external LLM provider (if enabled); backend ↔ S3/Secrets Manager. Each boundary is a place to confirm encryption in transit and least-privilege credentials.

---

## 6. Identity, access, and multi-tenant isolation

This is the strongest part of the platform and worth describing in some detail, because multi-tenant isolation is the dominant risk for a shared analytics system.

- **Authentication:** Stateless JWT bearer tokens (no server sessions, no cookies). Passwords are stored using Spring Security's delegating password encoder (bcrypt-family). **[Confirmed – code]**
- **MFA:** An email one-time-password second factor is implemented as a tenant-configurable option, on a dedicated token table, and is designed to **fail closed** if email delivery is unavailable. **[Confirmed – code]**
- **Authorization:** Role-based (`ADMIN` / `SUPER_ADMIN` and menu/feature grants), enforced both at URL level and via method-level annotations (`@EnableMethodSecurity`, `@PreAuthorize`). Admin and batch endpoints require elevated roles. **[Confirmed – code]**
- **Tenant isolation — defense in depth:** Every tenant-scoped query carries an explicit `tenant_id` predicate in application code, **and** the database applies PostgreSQL **row-level security** using a per-request `app.current_tenant` setting applied on the connection. Two independent layers must both fail for cross-tenant leakage. **[Confirmed – code]**
- **Security headers & transport:** `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, HSTS (1 year, includeSubDomains), and no-store cache control are set centrally. CSRF is intentionally disabled (justified for a stateless, header-based token API). CORS is centrally configured and origin-restricted (default is localhost — production origins must be set explicitly). **[Confirmed – code]**

**Points to confirm / watch:**
- No integration with an enterprise identity provider (Entra ID, Okta, SAML/OIDC) exists in the code. If corporate SSO is a requirement, it is **not present** today. **[Confirmed – code]**
- Account provisioning/deprovisioning, dormant-account handling, password policy specifics, session/token lifetime, and privileged-account review cadence are operational and should be confirmed. **[Requires confirmation]**
- CORS `allowCredentials=true` combined with a configurable origin list means the **production origin allow-list must be tightly set** — verify it is not permissive in prod. **[Requires confirmation]**

---

## 7. Encryption and secrets

- **In transit:** HTTPS at the edge with HSTS enforced; external provider calls (Anthropic, source DBs) use TLS endpoints. Internal service-to-database transport encryption should be confirmed for the RDS connection. **[Confirmed – code / Requires confirmation on internal legs]**
- **At rest:** Application-managed **AES-256** encryption protects stored integration secrets (SMTP, S3, source-DB credentials) via a resolver supporting plain/encrypted/AWS-backed values. Database-level and S3-level encryption at rest depend on RDS/S3 configuration and must be confirmed. **[Confirmed – code / Requires confirmation on infra]**
- **Key management:** The design expects secrets to live in **AWS Secrets Manager** in production, with a single AES application key that must be identical across pods and never rotated carelessly (rotating it without re-encrypting makes stored secrets undecryptable — a documented operational hazard). JWT signing uses a configured secret key. **[Confirmed – code]**

**Key questions for the security team:** Is RDS storage encryption enabled with a customer-managed KMS key? Is S3 encryption enabled? How is the AES application key generated, stored, and rotated? Is the JWT signing key unique per environment and at least 32 bytes of real entropy (the example value is a placeholder)? **[Requires confirmation]**

---

## 8. Logging, monitoring, and audit trail

- **Application logging** is present throughout (SLF4J). An **audit log** capability exists (prior work established one row per action, with username and action category), and the AI assistant persists every question, generated SQL, row count, duration, and errors per user/tenant. **[Confirmed – code]**
- **Not determinable from code:** centralized log aggregation / SIEM integration, log retention period, log immutability/integrity protection, alerting thresholds, and who can access or alter logs. Health monitoring is currently TCP-probe level only. **[Requires confirmation]**

**Assessment:** The building blocks for a usable audit trail exist (actor, action, timestamp, and — for AI — before/after query detail). Whether logs are shipped somewhere tamper-evident, retained long enough for forensic/compliance needs, and alerted on, is an **operational gap to confirm**. For audit and incident-response readiness, SIEM integration and a defined retention policy should be treated as required, not optional.

---

## 9. The AI assistant (dedicated note)

Because AI features attract specific scrutiny, they are called out separately.

**What it does:** Users ask a question in natural language; a language model translates it to a single read-only SQL `SELECT` against a fixed set of pre-aggregated warehouse tables; Nexus runs that query and renders the result. **[Confirmed – code]**

**Data egress:** The model receives the **question text plus the warehouse schema description** — not the underlying data rows. The generated SQL is executed locally against PostgreSQL; the result summary is produced by local heuristics, **not** sent back to the model. So row-level transaction data does not leave the platform through this feature; the user's phrasing of the question does (if an external provider is used). **[Confirmed – code]**

**Provider options:** A local model (Ollama, no egress) or external providers (Anthropic, OpenAI, Google Gemini). Which is active is a configuration/secret setting. If an external provider is enabled, question text leaves the network to that provider. **[Confirmed – code / Requires confirmation on prod config]**

**Guardrails (notably thorough):** generated SQL must start with `SELECT`; a keyword/'token blocklist and an allow-list of tables are enforced; every referenced table must be whitelisted (blocking "join to a users/api_key table" tricks); tenant predicate is injected and validated so a query cannot pin or probe another tenant; execution runs in a `READ ONLY` transaction with a statement timeout and a hard row cap; row-level security still applies underneath. Prompt-injection that tries to exfiltrate other tenants' data is contained by these layers even if the model misbehaves. **[Confirmed – code]**

**Residual AI questions:** Which provider is enabled in production and is external egress approved? Does the provider's contract prohibit training on submitted data? Is there any classification/DLP on the question text itself? **[Requires confirmation]**

---

## 10. Compliance, privacy, availability, and continuity

**Compliance framing.** Nexus processes financial transaction data for regulated payment entities, so the standards most likely to apply are **PCI-DSS** (scope contingent on whether PANs are present), **local data-protection law** (Bahrain PDPL; GDPR where EU cardholders/data subjects are involved), and the operating entities' own regulatory obligations (e.g., central-bank requirements for payment processors). Nothing in the code should be read as a certification. The organization should decide, per standard, whether it is *supporting*, *compliant with*, or *certified against* it — today none of these are evidenced in code. **[Requires confirmation]**

**Privacy.** Personal data is limited (staff accounts; potentially cardholder-linked identifiers depending on file contents). A **data-classification decision on ingested files** is the pivotal privacy question and also drives whether a Data Protection Impact Assessment is needed. Data-subject-rights handling, lawful basis, and retention are operational and should be documented. **[Requires confirmation]**

**Data residency.** The deployment targets AWS; the specific region(s), and therefore where transaction and personal data reside and are processed, must be confirmed against the operating entities' residency requirements. Cross-border considerations arise if any AWS region, support access, or AI provider sits outside the permitted jurisdiction. **[Requires confirmation]**

**Backup, recovery, DR, availability.** Not determinable from code. The single-replica core service is a known availability limitation. **RPO/RTO, backup frequency and testing, RDS Multi-AZ, and a documented BCP/DR plan are all open items** and are high-priority evidence to gather. **[Requires confirmation]**

**Incident response.** No incident-response runbook is visible in the repository. Detection/notification/escalation processes, and any regulatory breach-notification obligations for the operating entities, must be confirmed. No publicly documented security incident is applicable here (this is an internal, non-public system). **[Requires confirmation]**

---

## 11. Key risks and open items

### Risk register (initial)

| ID | Risk | Area | Likelihood | Impact | Rating | Existing control | Gap / mitigation |
|----|------|------|-----------|--------|--------|------------------|------------------|
| R1 | Card PAN data present but PCI scope unmanaged | Data / Compliance | Unknown | Critical | **To confirm** | Unknown | Decide data classification; scope PCI; confirm no raw PAN stored |
| R2 | Default/example secrets or destructive `SQL_INIT_MODE=always` reach production | Config / Integrity | Low–Med | Critical | **High** | Manifests say use Secrets Manager + `never` on AWS | Verify prod config; enforce via pipeline checks |
| R3 | Single-replica core → outage / data-processing stall | Availability | Medium | Medium–High | **Medium–High** | Documented, `Recreate` strategy | Define RTO; plan web/worker split; confirm RDS HA |
| R4 | No backups/DR evidence; unknown RPO/RTO | Continuity | Unknown | High | **To confirm** | Unknown | Obtain backup schedule, DR plan, restore test results |
| R5 | External LLM egress of question text (if enabled) | Privacy / AI | Config-dependent | Medium | **Medium** | Local-model option; no row data sent | Confirm prod provider; approve egress or force local model |
| R6 | Logging without confirmed SIEM/retention/integrity | Monitoring / Audit | Medium | Medium | **Medium** | App + audit + AI history logging present | Add SIEM shipping, retention policy, tamper-evidence |
| R7 | Rate-card integrity error → wrong revenue figures | Data integrity | Medium | High | **Medium–High** | Change tracking exists | Enforce four-eyes on rate changes; reconcile |
| R8 | No enterprise SSO / centralized deprovisioning | IAM | Medium | Medium | **Medium** | RBAC + MFA option | Consider SSO/OIDC; define JML process |
| R9 | Broad `statement_timeout=0` during ingestion | Availability | Low–Med | Medium | **Low–Medium** | Scoped overrides in AI path | Review global timeout; bound long jobs |

*Ratings are indicative. Items marked "To confirm" must not be assigned a final rating until evidence is available.*

### Gaps requiring confirmation

| Topic | Currently known | Missing | Question for the owner | Priority |
|-------|-----------------|---------|------------------------|----------|
| Card data scope | Transaction records ingested | Whether PANs/SAD are present | What card fields enter Nexus, and is PAN ever stored? | **Critical** |
| Production secrets | Secrets Manager intended | Actual prod config | Confirm no default secrets; confirm KMS-backed keys | **High** |
| Schema-init switch | `always` locally, `never` intended on AWS | Prod value | Confirm `SQL_INIT_MODE=never` in prod | **High** |
| Backup / DR | Nothing in code | RPO/RTO, backups, DR test | Provide backup schedule, DR plan, last restore test | **High** |
| Logging/SIEM | App + audit logging present | Aggregation, retention, integrity | Where do logs go, how long, and are they tamper-evident? | **High** |
| AI provider | Multi-provider support in code | Prod provider + egress approval | Which provider is live? Is external egress permitted? | **Medium** |
| Data residency | AWS-targeted | Region(s) | Which AWS region(s) hold/process the data? | **Medium** |
| SSO / JML | JWT + RBAC + MFA | IdP integration, deprovisioning | Is corporate SSO required? How are leavers offboarded? | **Medium** |
| Pen testing | OWASP dep-check in build | Independent test results | Has an independent pen test been done? Executive summary? | **Medium** |
| Certifications | None in code | SOC 2 / ISO / PCI status | What (if any) attestations apply to the hosting/operation? | **Medium** |

---

## 12. Recommendations

**Before approval (mandatory evidence):**
1. Resolve the **card-data classification** and PCI-DSS scope question (R1).
2. Confirm **production secrets** come from AWS Secrets Manager with KMS-backed keys, and that no default/example secret can reach production (R2).
3. Confirm `SQL_INIT_MODE=never` and RDS-based schema migration in production (R2).
4. Obtain **backup, DR, and availability** evidence — RPO/RTO, RDS Multi-AZ, last successful restore test (R3, R4).
5. Confirm the **production AI provider** posture and whether external egress is approved (R5).

**Before production use (technical controls):**
- Lock the CORS origin allow-list and JWT/AES keys to unique per-environment secrets.
- Enforce MFA for administrative accounts; document password and session policies.
- Ship logs to a SIEM with defined retention and integrity protection; add real health/alerting beyond TCP probes.
- Put a four-eyes control on rate-card changes.

**After implementation (ongoing):**
- Periodic user-access and privileged-access reviews; joiner/mover/leaver process.
- Dependency-vulnerability remediation SLAs (the OWASP scan already runs — define what happens to findings).
- Monitor the AWS and source-database dependencies as critical services.

**Annual review:**
- Independent penetration test; DR restore test; access recertification; review of any certifications/attestations; re-run of this assessment.

---

## 13. Overall assessment (for executives)

Based on the information currently available from the source code:

- **From a build-quality and application-security perspective,** Nexus is better engineered for security than a typical internal tool. Multi-tenant isolation is enforced in two independent layers, injection surfaces (including the AI feature) are tightly guarded, credentials are hashed, MFA exists, and modern security headers are in place. These are genuine strengths, not marketing claims — they are visible in the code.
- **From an operational, audit, and compliance perspective,** the platform cannot yet be signed off, because the controls that matter most to those functions — backups and DR, logging retention and SIEM, penetration testing, secrets hygiene in production, and above all the **card-data classification that decides PCI scope** — are not determinable from code and are currently unconfirmed.
- **The primary residual risks** are: unresolved PCI/data-classification scope; the possibility of default secrets or a destructive schema-init switch reaching production; the single-replica availability limitation; and the absence of confirmed backup/DR and logging/monitoring evidence.

This is therefore best characterized as **"Further assessment required — approvable with conditions."** The conditions are the "Before approval" items in §12. Nothing in the code review suggests the platform is unsafe by design; the outstanding work is to confirm that the operational and configuration controls match the quality of the application itself.

*This assessment reflects the state of the repository as reviewed on 30 August 2026 and should be revalidated once the confirmation items above are answered.*
