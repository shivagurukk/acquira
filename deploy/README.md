# Acquira — Local Deployment on Windows (mirrors AWS EKS)

This folder deploys Acquira on your Windows PC in a way that mirrors the eventual
AWS EKS setup, so the manifests carry over with only the AWS-managed pieces swapped in.

## What maps to what

| AWS (EKS target)        | Local equivalent here              |
|-------------------------|------------------------------------|
| EKS control plane       | kind cluster                       |
| ALB + LB Controller     | ingress-nginx                      |
| RDS PostgreSQL          | `overlays/dev/postgres.yaml` (Postgres pod) |
| EFS RWX PVC             | `base/04-reports-pvc.yaml` (local RWO)      |
| ECR                     | images loaded into kind            |
| Secrets Manager + ESO   | `overlays/dev/secrets.env` (generated Secret) |
| Route53 + ACM           | `acquira.localtest.me` hostname    |

The Deployment / Service / PVC / probe shapes are **identical** to EKS. Only the
glue above changes when you move to AWS.

## Layout (Kustomize)

```
deploy/k8s/
  base/                 everything common: 4 Deployments + Services, ConfigMap,
                        reports PVC, ingress routing, NetworkPolicies
  overlays/dev/         kind: + Postgres pod, Secret from secrets.env, local images
  overlays/uat/         EKS: ECR images, RDS, EFS, ALB, ExternalSecret, 1 replica each
  overlays/prod/        EKS: same as uat, core x2, pdf x2, frontend x3
  logging/              central log aggregation (CloudWatch or Loki) — see its README
```

```powershell
kubectl kustomize deploy/k8s/overlays/prod     # preview, needs no cluster
kubectl apply -k   deploy/k8s/overlays/dev     # deploy an environment
```

`kubectl` has Kustomize built in; nothing else to install. Values marked
`CHANGE-ME` in the uat/prod overlays (RDS endpoint, certificate ARN, image
tags, EFS storage class, ALB subnet CIDR, secret store) must be filled in.

## Prerequisites (install on Windows)

1. **Docker Desktop** with the **WSL2** backend enabled (Settings → General).
2. **kind** and **kubectl** — easiest via:  `winget install Kubernetes.kind Kubernetes.kubectl`
3. **RAM:** 16 GB comfortable, 8 GB tight floor. The memory driver is Chromium
   running in-process inside acquira-core, not Kubernetes.
4. In Docker Desktop → Settings → Resources, give the WSL2 VM at least **8 GB**.

## Pod layout (one jar, three backend roles)

| Pod                | ACQUIRA_ROLE | Image target | Serves                                                                    |
|--------------------|--------------|--------------|---------------------------------------------------------------------------|
| `acquira-core`     | `core`       | `slim`       | login/auth, dashboards, email, AI, core schedulers, event-outbox relay     |
| `acquira-pdf`      | `pdf`        | `full`       | `/api/business/insights`, Chromium rendering                               |
| `acquira-batch`    | `batch`      | `slim`       | `/api/batch`, `/api/upload`, `/api/interchange`, `/api/admin/{backups,…}`  |
| `acquira-frontend` | —            | —            | the SPA (nginx)                                                           |

The same `acquira-core-*.jar` runs every backend pod; `ACQUIRA_ROLE` decides
which modules load (`PodRoleFilter`). With no role set it is the original
single-process app — that is what `docker compose` and local dev still run.
The ingress (`base/07-ingress.yaml`) does the routing; the frontend never changes.

### Replicas

| Pod      | Safe to run >1? | Why |
|----------|-----------------|-----|
| frontend | yes             | stateless |
| core     | yes (prod: 2)   | every scheduled job takes a ShedLock row; report caches sync through `report_cache_version`; events arrive via `event_outbox` (rows claimed with SKIP LOCKED). Still per-pod: rate-limit counters and bulk-email progress — covered by load-balancer stickiness (ALB target groups in uat/prod). |
| pdf      | yes (prod: 2)   | batch/S3 job status is persisted in `pdf_job_status`, so any replica answers status polls and cancels; a job whose pod died shows `INTERRUPTED`. A running batch is not resumed — re-run it. |
| batch    | **keep at 1**   | schedulers and cron pulls are lock-protected, per-tenant pulls take a cluster lock, but backfill / bulk-migration / dedup progress and the cron registry live in the pod's memory and an upload is processed by the pod that received it. Scale it up, not out. |

### Database migrations the split needs

| Migration | Tables | Without it |
|-----------|--------|-----------|
| `V2026_10_03_01` | `report_cache_version`, `event_outbox` | pods (role != all) stay **NotReady** |
| `V2026_10_03_02` | `shedlock` | pods (role != all) stay **NotReady**; in role `all` jobs run unlocked, as before |
| `V2026_10_03_03` | `pdf_job_status` | pdf job status falls back to in-memory (one WARN) |

All three are additive and idempotent; apply them with psql before deploying
(they are safe to apply while the old single-container version is running).
Readiness (`CrossPodSchemaHealthIndicator`) returns 503 until the first two
exist, so a forgotten migration shows up as pods that never become Ready
rather than as silently stale dashboards or duplicated emails.

### Checking which pod served a request

Every response carries `X-Acquira-Pod: core|pdf|batch` and `X-Correlation-Id`.
In the browser: DevTools -> Network -> any `/api` call -> Response Headers.
Each log line carries the same `pod` and `cid`; see `k8s/logging/README.md`
for searching all pods at once.

Known gaps in the split (same as the single-container image, listed so nobody
is surprised):
- **Deploy core first on a brand-new database** — core's startup runners
  (`DatabaseFixer`, `RbacCatalogSync`) add columns and seed RBAC rows the other
  pods read. Existing databases already have them.
- **Backups** (`/api/admin/backups`, batch pod) need `pg_dump`/`pg_restore` on
  PATH and a durable `BACKUP_DIR`; the images install no postgresql-client and
  `base/05c-batch.yaml` mounts no backup volume. Run backups from a CronJob with the
  `postgres` image, or add the client (matching the server major) to the image.
- **Interchange scan** (`acquira.interchange.folder`, batch pod) reads a local
  folder that nothing in k8s fills; mount a volume / S3 sync there if used.
- **Campaign emails** send WITHOUT the statement if the pdf pod is unreachable
  (pre-existing "send anyway" rule in `CampaignExecutionService`); the core log
  shows `pdf service ... unreachable` and `PdfRenderClient` fails fast after 3
  consecutive transport errors so a bulk run does not stall for hours.

## Code facts already verified (no action needed)

- **Actuator IS present** (health + info only). Probes use
  `/actuator/health/liveness` and `/readiness`.
- **Playwright 1.63.0 (driver-bundle) manages its own Chromium.** `Dockerfile.core`
  copies the version-matched browser from `mcr.microsoft.com/playwright/java:v1.63.0-jammy`,
  so there is no distro-Chromium mismatch. Your Docker Desktop must be able to pull
  from `mcr.microsoft.com` (it can by default).
- **PDF smoke test after first boot:** trigger a statement/insight PDF and confirm a
  file lands under `/opt/acquira/reports` in the core pod
  (`kubectl -n acquira exec deploy/acquira-core -- ls -R /opt/acquira/reports`).

---

## STEP 1 — Prove the images with Docker Compose (do this first)

From the **repo root** (PowerShell):

```powershell
# also place a copy of deploy/docker/.dockerignore at the repo root as .dockerignore
docker compose -f deploy/docker/docker-compose.yml up --build
```

- App:      http://localhost:8081/actuator/health  (or :8081 if no Actuator)
- Frontend: http://localhost:8080

Validate: app boots against Postgres, a PDF generates (Chromium works), an upload lands.
If this works, 80% of the cloud risk is gone. Then `Ctrl+C` and `docker compose ... down`.

---

## STEP 2 — Stand up the kind cluster (the AWS-like part)

```powershell
# 1. create the cluster with host port mappings
kind create cluster --config deploy/kind/kind-cluster.yaml

# 2. install ingress-nginx (local stand-in for the ALB)
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl wait --namespace ingress-nginx --for=condition=ready pod `
  --selector=app.kubernetes.io/component=controller --timeout=180s

# 3. build the images: backend slim (core, batch), backend full (pdf: +Chromium), frontend
docker build -t acquira-backend-slim:local --target slim -f deploy/docker/Dockerfile.core .
docker build -t acquira-backend-full:local               -f deploy/docker/Dockerfile.core .
docker build -t acquira-frontend:local                   -f deploy/docker/Dockerfile.frontend .

# 4. load them INTO the kind cluster (this is your "ECR" locally)
kind load docker-image acquira-backend-slim:local acquira-backend-full:local acquira-frontend:local --name acquira
```

---

## STEP 3 — Deploy the app

```powershell
# secrets for the dev overlay (git-ignored; fill in real values)
copy deploy\k8s\overlays\dev\secrets.env.example deploy\k8s\overlays\dev\secrets.env

# everything in one go: namespace, secret, config, Postgres, PVC, the four
# Deployments, ingress and network policies
kubectl apply -k deploy/k8s/overlays/dev

# FIRST TIME on the empty kind database only: the schema must exist before the
# pods can become Ready. Either restore a dump into the acquira-db pod, or let
# core create it once:
#   kubectl -n acquira set env deploy/acquira-core SPRING_SQL_INIT_MODE=always
#   (wait for core to start, then)  kubectl -n acquira set env deploy/acquira-core SPRING_SQL_INIT_MODE-
# then apply V2026_10_03_01, _02, _03 with psql (kubectl -n acquira port-forward svc/acquira-db 5432).

# watch it come up (core is slow to first-ready — that's expected)
kubectl -n acquira get pods -w
```

Open: **http://acquira.localtest.me**  (resolves to 127.0.0.1 automatically).

### Verify the split

```powershell
kubectl -n acquira get pods                      # core, pdf, batch, frontend x2 all Running
kubectl -n acquira logs deploy/acquira-batch | Select-String "Scheduler"     # batch owns the cron jobs
kubectl -n acquira logs deploy/acquira-core  | Select-String "EmailQueue"    # core owns email
```

- Upload a file in the UI → the job log appears in `deploy/acquira-batch`, and
  within ~5 s core and pdf log `Report caches cleared: evicted by another instance`.
- Generate a merchant statement PDF (Insight Hub) → served by `deploy/acquira-pdf`.
- Email Manager → send one statement → core renders it through
  `http://acquira-pdf:8081` (`PdfRenderClient`); pdf logs the Chromium render.
- `kubectl -n acquira delete pod -l app=acquira-pdf` while browsing: login and
  dashboards keep working; only PDF generation is unavailable until it is back.

`localtest.me` needs no hosts-file edit. If your network blocks it, add
`127.0.0.1 acquira.local` to `C:\Windows\System32\drivers\etc\hosts` and change
the host in `base/07-ingress.yaml`.

---

## Troubleshooting

```powershell
kubectl -n acquira logs deploy/acquira-core            # app logs
kubectl -n acquira describe pod -l app=acquira-core    # events / probe failures
kubectl -n acquira get events --sort-by=.lastTimestamp
```

- **Pod stuck `0/1` for minutes:** normal at first (startupProbe is generous). If it
  never readies, check Actuator (see verify step) and the DB URL in the ConfigMap.
- **`ImagePullBackOff`:** you forgot `kind load docker-image` after rebuilding.
- **OOMKilled on pdf:** raise the memory limit in `base/05b-pdf.yaml` and give the
  WSL2 VM more RAM. Chromium is hungry.
- **Rebuilt an image:** re-run `docker build` **and** `kind load` **and**
  `kubectl -n acquira rollout restart deploy/acquira-core deploy/acquira-pdf deploy/acquira-batch`.
- **A new batch/pdf endpoint returns 404:** the ingress routes by prefix — add
  the controller's `/api/...` prefix to `base/07-ingress.yaml` (the build fails
  on this: `IngressRoutingContractTest`).
- **Pods Running but never Ready (0/1):** `kubectl -n acquira exec deploy/acquira-core -- curl -s localhost:8081/actuator/health/readiness`
  — a `crossPodSchema` DOWN means a migration from the table above is missing.
- **A scheduled job did not run:** `select * from shedlock` shows who holds each
  lock and until when; a pod that died frees its locks within 10 minutes.
- **Dashboards stale after an upload / no failure-alert emails:** check the
  core log for `Report cache sync ... failed` or `Event outbox relay ... failed`
  — migration `V2026_10_03_01` is missing on that database.

## Tear down

```powershell
kind delete cluster --name acquira
```

---

## AWS EKS (uat / prod)

Everything environment-specific lives in `k8s/overlays/uat` and
`k8s/overlays/prod`: ECR images, RDS URL, EFS-backed ReadWriteMany reports
volume, ALB ingress with HTTPS and sticky target groups, the Secret pulled from
Secrets Manager (External Secrets), NetworkPolicy sources, replica counts.

1. Fill in every `CHANGE-ME` in the overlay.
2. Push the images: `backend:slim-<version>`, `backend:full-<version>`, `frontend:<version>`.
3. Apply the three `V2026_10_03_*` migrations to the database (psql).
4. `kubectl kustomize deploy/k8s/overlays/prod` and review, then `kubectl apply -k ...`.
5. Install a log collector — `k8s/logging/README.md`.

Cluster prerequisites the overlays assume: AWS Load Balancer Controller, EFS
CSI driver + a StorageClass, External Secrets Operator + a ClusterSecretStore,
and NetworkPolicy enforcement enabled on the VPC CNI (or Calico/Cilium).

### Security settings the manifests rely on

| What | Where | What you must supply |
|---|---|---|
| Pods run non-root (backend uid 10001, nginx uid 101), no privilege escalation, no capabilities, no service-account token | `base/05*.yaml`, `06-frontend.yaml`, both Dockerfiles | EFS access points owned by uid/gid 10001 |
| Namespace rejects non-compliant pods (`restricted`) | uat/prod overlays | — (dev only warns: its Postgres pod runs as root) |
| Outbound traffic denied except DNS, 5432, 443, SMTP (core), 1521/1433 (batch); metadata endpoint blocked | `base/08-networkpolicy.yaml` | add the port of any integration that uses another one; set the node IMDSv2 hop limit to 1 |
| TLS 1.2+ on the ALB, WAF attached (prod) | overlay ingress annotations | the WAFv2 web ACL ARN, with a rate-based rule |
| TLS to RDS (`sslmode=require`) | overlay ConfigMap patch | — |
| Client IP taken from the entry the ALB appended to `X-Forwarded-For` | `ClientIp` (`app.security.trusted-proxies`, default 1) | set `APP_SECURITY_TRUSTEDPROXIES=2` if CloudFront or another proxy sits in front of the ALB |
| Pod-to-pod token separate from the JWT secret | `InternalAuth` | optional `APP_INTERNAL_TOKEN` key in the Secrets Manager secret |
| Browser security headers + Content-Security-Policy on the SPA | `docker/security-headers.conf` | add any new external origin the UI loads from |

Still to do on the AWS side: KMS envelope encryption for Kubernetes Secrets,
ECR image scanning, and versioned (not reused) image tags for UAT.

If you keep the current docker-compose production instead, leave
`ACQUIRA_ROLE` unset there (single process, `full` image) — nothing else
changes, and the three migrations are still safe to apply.
