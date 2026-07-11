# Acquira — Project Instructions

## What this project is
Acquira is a multi-tenant Merchant Analytics Platform for the card-acquiring business. It ingests merchant master + transaction data, aggregates into pre-computed summary tables, and serves analytics dashboards, executive reports, branded PDF statements, an AI assistant, and email campaigns. One deployment serves many banks/acquirers, isolated by tenant_id.

Location: C:\Users\sivag\Desktop\cms\Acquira

## Stack
- Backend: Java 21, Spring Boot 3.2, multi-module Maven (acquira-common, acquira-batch, acquira-pdf, acquira-ai, acquira-core). Only acquira-core has a runnable main(); it component-scans the other four and runs as one process on port 8081.
- Frontend: React 19 + Vite 5 + MUI 7 + Tailwind 4 + Recharts + React Router 7 + Axios + Framer Motion. Built by Vite, served static by Nginx, /api/* proxied to 8081.
- DB: PostgreSQL — dev on 127.0.0.1:5433, prod on 5432.
- PDF: Playwright + headless Chromium (HTML→PDF). Use the Chromium from mcr.microsoft.com/playwright/java:v1.40.0-jammy; do NOT use system Chromium.
- AI: Ollama (llama3.2 at localhost:11434).

## Repo caveat
There are two parallel copies of the code. The root /src is the LEGACY monolith (com.acquira.*, single AcquiraSystemApplication) and the migration/ folder holds leftover split files — both are dead weight. The live code is the five acquira-* modules. Always work in the modules, never /src.

## How I work
- I communicate tersely and expect direct execution with minimal back-and-forth. "proceed" means advance the work.
- Prefer additive, isolated changes wired through existing systems over wholesale page rewrites.
- Prefer frontend-only changes when possible (no backend rebuild). Deploy: npm run build → copy dist/ → restorecon → hard refresh.
- Read the controller/repository file BEFORE writing code, to confirm exact column names, endpoint shapes, and data flows. This avoids column-name mismatches.

## Filesystem MCP rules (operates on the Windows repo)
- All edits are full-file overwrites via write_file. No partial edits, no str_replace to the Windows machine.
- New directories must be created by me manually before files can be written into them.
- After edit_file, re-copy the file via Filesystem:copy_file_user_to_claude before validating — otherwise the validated copy is stale.
- read_multiple_files takes Windows-style backslash paths as a JSON array. Use head/tail params for partial reads of large files. Use search_files with excludePatterns: ['**/target/**'] to skip compiled artifacts.

## Validation
- JSX: npx --yes esbuild@0.21.5 in Claude's Linux container (unresolved imports are fine, no bundling). Success = "out.js … kb" printed. "npm error config prefix" in output is harmless.
- Java: grep-based / Python brace-scanner checks.

## Design system
CSS-variable driven (--bg-card, --border, --text, --brand, --radius-*, --shadow-*, status tokens), auto-adapts via html.dark + ThemeContext. Preferred aesthetic: restrained financial-instrument (Linear / Sigma / Stripe register). Avoid heavy gradients, glow blobs, and gradient text fills.

## Data-sourcing rules (critical for correctness)
- Bank-level unfiltered KPIs/trends: sum_daily_bank / sum_monthly_bank.
- Dimension-filtered KPIs/trends: sum_daily_insight (has dimensional columns; interchange/scheme/VAT are 0 there).
- Per-merchant per-day grid, leaderboards, portfolios: sum_daily_merchant, using total_base_volume (settlement, single-currency) — NOT total_volume (cardholder currency).
- Cross-tab / associative / Explorer: sum_daily_insight (cardholder-currency volume).
- Raw drag-and-drop Data Explorer: stg_merchant_master_raw / stg_trnx_raw — last upload only, never historical.
- Data bounds: fact_transaction authoritative, fall back to sum_daily_insight.
- Daily Merchant Dashboard uses sum_daily_merchant (synchronous, indexed) — NOT the legacy drop-prone merchant_daily_metrics.

## Schema migration discipline
Production runs spring.sql.init.mode=never, so ALTER migrations are the ONLY landing mechanism. CREATE TABLE IF NOT EXISTS silently skips updated column defs on existing tables. Any new column/constraint must land as idempotent ALTER TABLE ADD COLUMN IF NOT EXISTS / DO $$ ... IF NOT EXISTS ... ADD CONSTRAINT $$ migrations.

## Architectural constraints
- replicas: 1 is deliberate — the scheduler/batch architecture (EmailQueueProcessor, external DB pulls, alert schedulers, Spring Batch) requires single-replica to avoid duplicate work and job collisions. Not a Kubernetes limitation.
- Spring Boot Actuator is absent — health probes use TCP socket checks, not HTTP health endpoints.
- Sidebar is DB-driven from sys_menu × sys_group_menu. Adding a screen = add route in App.jsx + add a sys_menu row + grant via sys_group_menu (manual SQL insert on prod).
- Tenant isolation: every read/write scopes on tenant_id; aggregation subqueries push the tenant filter INSIDE the subquery and onto every dim join (dX.tenant_id = base.tenant_id).
- Agents identified by sales_user_id + sales_email; sales_agent_profile is the reconciliation point.

## Tools/resources
- Dev DB: PostgreSQL 127.0.0.1:5433.
- Prod: RHEL box at /opt/acquira/ (not filesystem-accessible; give operations as shell commands).
- Local k8s: kind (Kubernetes-in-Docker), Docker Desktop WSL2 backend, kubectl v1.32.2. AWS→local mapping: RDS→Postgres pod, EFS→local RWO PVC, ALB→ingress-nginx, ECR→kind image load, Secrets Manager→k8s Secrets.
- ACQUIRA_FEATURE_GUIDE.md (in project files) is the page/endpoint/table atlas. ACQUIRA_DEVELOPER_GUIDE.md covers infra/batch/schema/deploy.
