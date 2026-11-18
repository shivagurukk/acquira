# Upgrading production to the Spring Boot 4 / pod-split release

The existing production database is kept. Nothing is exported or re-imported: the upgrade adds the
tables, columns, menus and rate rows the new version needs, and leaves institutions, merchants,
transactions and summaries as they are. The database is upgraded first, then the new application
is deployed against it.

| File | Use |
|---|---|
| `out/acquira_prod_upgrade.sql` | **Production / UAT.** Upgrades an existing database. One file. |
| `out/acquira_fresh_install.sql` | New, empty databases only. Refuses a database that has tables. |

Regenerate both after adding a migration (`build_fresh_db_kit.py`, then `build_prod_upgrade.py`;
the second one also re-runs its verification). Commit every migration before generating the file
that goes to production: the header of the upgrade script names any that were not committed.

## What you need

- `psql` 12 or later on a machine that can reach the production database.
- Credentials of a user that owns the schema (the application user).
- A maintenance window. The application must not run while the database is upgraded; plan about
  an hour including the rehearsal checks (the upgrade itself takes minutes; step 6 can take longer
  but runs with the application up).
- The new images built and pushed (backend `slim-<version>` and `full-<version>`, frontend
  `<version>`), and the `CHANGE-ME` values in `deploy/k8s/overlays/prod` filled in.

## Step 1 - Rehearse on a copy of production (do not skip)

1. Restore the latest production snapshot to a new, temporary database instance.
2. Run the dry run against it and keep the output:
   ```
   psql -h <copy-host> -U <user> -d <db> -v DRY_RUN=1 -f acquira_prod_upgrade.sql
   ```
   It lists the migrations that database has not recorded and changes nothing.
3. Run the upgrade against the copy:
   ```
   psql -h <copy-host> -U <user> -d <db> -f acquira_prod_upgrade.sql 2>&1 | tee upgrade_rehearsal.log
   ```
   It must end with `still_pending = 0`, all five objects `t`, and no `ERROR`.
4. Point UAT (or a local build of the new version) at the copy and check: login, the executive
   dashboards, one upload, one statement PDF, and the menus each role sees.
5. Note how long step 3 took. Then delete the temporary instance.

If anything fails here, stop and fix it before touching production.

## Step 2 - Back up production

Take a manual snapshot and wait until it is `available`:
```
aws rds create-db-snapshot --db-instance-identifier <prod-instance> --db-snapshot-identifier acquira-pre-boot4-<date>
aws rds wait db-snapshot-available --db-snapshot-identifier acquira-pre-boot4-<date>
```
(Without RDS: `pg_dump -Fc -h <host> -U <user> -d <db> -f acquira_pre_boot4.dump`.)

This snapshot is the rollback. The migrations cannot be undone one by one.

## Step 3 - Stop the application

Kubernetes:
```
kubectl -n acquira scale deploy acquira-batch acquira-core acquira-pdf --replicas=0
kubectl -n acquira get pods        # wait until no backend pod is left
```
Docker Compose: `docker compose -f deploy/docker/docker-compose.yml stop`.

The batch pod matters most: an ingest running during the upgrade holds locks on the fact and
summary tables. The script waits at most `LOCK_TIMEOUT` (default 60s) for a lock and then stops
with an error rather than hanging; running it again resumes where it stopped.

## Step 4 - Upgrade the production database

```
psql -h <prod-host> -U <user> -d <db> -v DRY_RUN=1 -f acquira_prod_upgrade.sql
```
Compare the pending list with the one from the rehearsal; they should match. Then:
```
psql -h <prod-host> -U <user> -d <db> -f acquira_prod_upgrade.sql 2>&1 | tee upgrade_prod.log
```

How it behaves:
- It refuses an empty database and a database older than the init chain, and changes nothing then.
- Each migration runs in its own transaction together with its row in `schema_migration_log`.
  On an error the run stops; everything before it stays applied. Fix the cause and run the same
  command again: applied migrations are skipped.
- `[skip ]` means already recorded. `[supersd]` means a migration that a later, already applied one
  replaces (the draft Egypt Mastercard tiers): it is recorded and not run, so it cannot undo the
  later rates.
- The end of the output shows `still_pending` (must be 0) and the cross-pod tables (must be `t`).

## Step 5 - Deploy the new version

Keep `SQL_INIT_MODE=never` (the ConfigMap already does). `always` re-runs `schema.sql`, which drops
tables.

Kubernetes:
```
kubectl kustomize deploy/k8s/overlays/prod      # review
kubectl apply -k   deploy/k8s/overlays/prod
kubectl -n acquira get pods -w                  # core first; all Ready within about 5 minutes
```
A backend pod that stays `0/1` means a missing table: check
`kubectl -n acquira exec deploy/acquira-core -- curl -s localhost:8081/actuator/health/readiness`
(a `crossPodSchema` DOWN means step 4 did not complete).

Smoke test: login (forced MFA where enabled), executive dashboards for each institution, one
upload, one statement PDF, one email, and each role's menus. Every response carries
`X-Acquira-Pod`, which shows which pod served it.

## Step 6 - Re-price for the rate-card changes

Migrations change rate cards; they do not re-price transactions already loaded. For each
institution whose rates changed in this release, as a super-admin switched into that institution,
run the summary rebuild with re-pricing for the months the new rates apply to (Data Migration
screen, or `POST /api/admin/migration/rebuild-summaries` with
`{"startMonth": "YYYY-MM", "endMonth": "YYYY-MM", "reprice": true, "confirm": true}`).
Run one institution at a time; the endpoint refuses a second run while one is in progress.

Rate changes in this release include Mastercard UAE from October 2026 (scheme fee by card type,
MCC recategorisation), the Jaywan scheme for the UAE, and the Egypt Visa / Mastercard / Meeza
alignment. Compare the dashboards before and after for one month.

## Rollback

Before step 5: scale the old version back up; the added tables and columns do not affect it.

After step 5, if the new version has to go:
1. Scale the backend to 0.
2. Restore the step 2 snapshot (to a new instance, then repoint, or in place).
3. Deploy the previous images.

Data written by the new version after the upgrade is lost by a restore, so decide quickly.

## Database older than the init chain

The upgrade does not re-run `schema.sql`, `data.sql` or the scripts listed in
`spring.sql.init.schema-locations`: some of them seed rate cards that later migrations replace, and
re-running them on a live database would undo those changes. If the script refuses with "missing
tables that the init chain creates", the database predates that chain. Restore a copy, bring it up
to the init-chain level and rehearse again before going near production.
