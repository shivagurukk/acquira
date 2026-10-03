# Central log aggregation

Goal: one place to search the logs of every pod, and follow a single request
across pods.

## What the application already does

With the `json-logs` Spring profile (set by the uat/prod overlays through
`SPRING_PROFILES_ACTIVE=prod,json-logs`) every pod writes **one JSON object per
line to stdout**:

```json
{"@timestamp":"2026-10-03T15:06:16.175+05:30","level":"INFO","logger_name":"c.a.pdf...","thread_name":"http-nio-8081-exec-1",
 "message":"...","app":"acquira","pod":"pdf","cid":"7f40c936","tenantId":"8","username":"admin"}
```

| Field      | Meaning                                                                        |
|------------|--------------------------------------------------------------------------------|
| `pod`      | the role that wrote the line: `core`, `pdf`, `batch` (`all` in single-process) |
| `cid`      | correlation id of the request. Created by the first pod that sees the request (or taken from an incoming `X-Correlation-Id` header), returned to the browser in the `X-Correlation-Id` response header, and **forwarded on pod-to-pod calls** (core -> pdf render), so the same `cid` appears in both pods' logs |
| `tenantId` | tenant the request ran for                                                     |
| `username` | authenticated user                                                             |
| `job`/`step` | Spring Batch job and step, on ingest threads                                 |

Nothing else is needed in the app. In Kubernetes the collector reads container
stdout, so **no log volume is mounted** — the rolling files under
`/opt/acquira/logs` are a convenience inside the pod, not the source of truth.

The files in this folder are **starting points for the collector** and have not
been run against your cluster. Pick one.

## Option A — AWS CloudWatch (EKS)

Fluent Bit as a DaemonSet ships every container's stdout to CloudWatch Logs.

```bash
helm repo add eks https://aws.github.io/eks-charts
helm upgrade --install aws-for-fluent-bit eks/aws-for-fluent-bit -n kube-system -f deploy/k8s/logging/fluent-bit-cloudwatch-values.yaml
```

Prerequisite: an IAM role for the Fluent Bit service account (IRSA) with
`logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`,
`logs:DescribeLogStreams`, `logs:PutRetentionPolicy`.

Search in **CloudWatch Logs Insights** (log group `/acquira/prod`):

```
# everything one request did, across all pods, in order
fields @timestamp, pod, level, logger_name, message
| filter cid = "7f40c936"
| sort @timestamp asc

# errors per pod in the last hour
filter level = "ERROR" | stats count() by pod, logger_name | sort count() desc

# what the batch pod did for one tenant
fields @timestamp, job, step, message | filter pod = "batch" and tenantId = "8" | sort @timestamp desc

# cross-pod signals
filter message like /evicted by another instance|Event outbox|pdf service/
```

## Option B — Grafana Loki (kind, or self-hosted)

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm upgrade --install loki grafana/loki-stack -n logging --create-namespace -f deploy/k8s/logging/loki-stack-values.yaml
```

Then in Grafana -> Explore (LogQL):

```
{namespace="acquira"} | json | cid="7f40c936"
{namespace="acquira", acquira_role="batch"} | json | level="ERROR"
sum by (acquira_role) (count_over_time({namespace="acquira"} | json | level="ERROR" [5m]))
```

`acquira_role` (core/pdf/batch) and `level` are indexed labels; `cid`,
`tenantId` and `username` are deliberately NOT labels (too many distinct values
for Loki) — filter on them after `| json`.

## Without a collector

`kubectl -n acquira logs -f deploy/acquira-batch` (or `-core`, `-pdf`) still
works; pipe through `jq` to read the JSON:

```bash
kubectl -n acquira logs deploy/acquira-core --since=10m | jq -r 'select(.cid=="7f40c936") | "\(.["@timestamp"]) \(.pod) \(.level) \(.message)"'
```
