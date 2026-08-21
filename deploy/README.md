# Acquira — Local Deployment on Windows (mirrors AWS EKS)

This folder deploys Acquira on your Windows PC in a way that mirrors the eventual
AWS EKS setup, so the manifests carry over with only the AWS-managed pieces swapped in.

## What maps to what

| AWS (EKS target)        | Local equivalent here              |
|-------------------------|------------------------------------|
| EKS control plane       | kind cluster                       |
| ALB + LB Controller     | ingress-nginx                      |
| RDS PostgreSQL          | `03-postgres.yaml` (Postgres pod)  |
| EFS RWX PVC             | `04-reports-pvc.yaml` (local RWO)  |
| ECR                     | images loaded into kind            |
| Secrets Manager + ESO   | `01-secret.yaml` (plain Secret)    |
| Route53 + ACM           | `acquira.localtest.me` hostname    |

The Deployment / Service / PVC / probe shapes are **identical** to EKS. Only the
glue above changes when you move to AWS.

## Prerequisites (install on Windows)

1. **Docker Desktop** with the **WSL2** backend enabled (Settings → General).
2. **kind** and **kubectl** — easiest via:  `winget install Kubernetes.kind Kubernetes.kubectl`
3. **RAM:** 16 GB comfortable, 8 GB tight floor. The memory driver is Chromium
   running in-process inside acquira-core, not Kubernetes.
4. In Docker Desktop → Settings → Resources, give the WSL2 VM at least **8 GB**.

## Code facts already verified (no action needed)

- **Actuator is NOT present** in this build. The probes in `05-core.yaml` are
  therefore TCP checks on port 8081 (already set) — not `/actuator/health`.
- **Playwright 1.40.0 (driver-bundle) manages its own Chromium.** `Dockerfile.core`
  copies the version-matched browser from `mcr.microsoft.com/playwright/java:v1.40.0-jammy`,
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

# 3. build the two images
docker build -t acquira-core:local     -f deploy/docker/Dockerfile.core .
docker build -t acquira-frontend:local -f deploy/docker/Dockerfile.frontend .

# 4. load them INTO the kind cluster (this is your "ECR" locally)
kind load docker-image acquira-core:local     --name acquira
kind load docker-image acquira-frontend:local --name acquira
```

---

## STEP 3 — Deploy the app

```powershell
# real secret from the example (then fill in real values; it's gitignored)
copy deploy\k8s\01-secret.example.yaml deploy\k8s\01-secret.yaml

kubectl apply -f deploy/k8s/00-namespace.yaml
kubectl apply -f deploy/k8s/01-secret.yaml
kubectl apply -f deploy/k8s/02-configmap.yaml
kubectl apply -f deploy/k8s/03-postgres.yaml
kubectl apply -f deploy/k8s/04-reports-pvc.yaml
kubectl apply -f deploy/k8s/05-core.yaml
kubectl apply -f deploy/k8s/06-frontend.yaml
kubectl apply -f deploy/k8s/07-ingress.yaml

# watch it come up (core is slow to first-ready — that's expected)
kubectl -n acquira get pods -w
```

Open: **http://acquira.localtest.me**  (resolves to 127.0.0.1 automatically).

`localtest.me` needs no hosts-file edit. If your network blocks it, add
`127.0.0.1 acquira.local` to `C:\Windows\System32\drivers\etc\hosts` and change
the host in `07-ingress.yaml`.

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
- **OOMKilled on core:** raise the memory limit in `05-core.yaml` and give the WSL2
  VM more RAM. Chromium is hungry.
- **Rebuilt an image:** re-run `docker build` **and** `kind load` **and**
  `kubectl -n acquira rollout restart deploy/acquira-core`.

## Tear down

```powershell
kind delete cluster --name acquira
```

---

## When you move to AWS EKS (later)

Delete `03-postgres.yaml` (use RDS), change the reports PVC to an EFS RWX
StorageClass, swap ingress to ALB annotations, replace the plain Secret with
External Secrets → Secrets Manager, push images to ECR, and run the
`08-migration-job` (because prod uses `SPRING_SQL_INIT_MODE=never`). Everything
else — the Deployments, Services, probes, replicas:1 pin — is already correct.
