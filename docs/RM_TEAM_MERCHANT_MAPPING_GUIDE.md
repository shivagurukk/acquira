# RM / Team / Merchant Mapping Guide (Acquira)

Self-contained runbook for mapping **Sales Agents (RMs) → Teams → Merchants** and setting agent names/targets. Works on any environment — all you need is DB access (Postgres) and/or the app's API.

---

## 1. How the data model works

| Table | What it holds | Key |
|-------|---------------|-----|
| `dim_merchant` | Merchants. Carries `sales_user_id` (the **SID**) and `sales_email` per merchant row. **This is the source of truth for which agents exist and which merchants they own.** | `(tenant_id, internal_id)` |
| `sales_agent_profile` | One row per agent (per distinct SID). Holds `display_name`, `phone`, `country_code`, `hire_date`, `monthly_target` (legacy), `status`, `notes`. `sales_email` is auto-synced from `dim_merchant` — never edit it by hand. | `(tenant_id, sales_user_id)` |
| `sales_team_mapping` | Team leads: `team_lead_name`, `team_lead_email`, `is_default`. One lead = one team. | `(tenant_id, team_lead_email)` |
| `sales_user_assignment` | Which agent (SID) belongs to which team lead: `sales_user_id` → `team_lead_id`. | `(tenant_id, sales_user_id)` |
| `sales_agent_target` | Monthly targets per agent (entered yearly via UI/API, stored as 12 monthly rows, per metric). | `(tenant_id, sales_user_id, month_key, metric_type)` |

**Merchant → agent mapping is implicit**: a merchant belongs to whichever agent is in its `dim_merchant.sales_user_id`. You never map merchants directly to teams — merchant → SID → team lead.

**Everything is per-tenant.** Every script below uses `tenant_id = 1` — change it to your tenant first (check with `SELECT tenant_id, name FROM tenant;`).

---

## 2. Order of operations

1. **Sync agent profiles** from merchant data (creates one profile row per SID).
2. **Create team leads** (if a lead doesn't exist yet, create it — steps below).
3. **Assign agents (SIDs) to team leads.**
4. **Set agent display names** from your Excel.
5. (Optional) **Set yearly targets** per agent.
6. **Verify.**

---

## 3. Step 1 — Sync agent profiles

Creates a `sales_agent_profile` row for every distinct SID found in merchants. Safe to re-run; never overwrites names.

**Via API** (logged in as an admin with access to `/sales/agents`):
```
POST /api/sales-agents/sync
```

**Or via SQL:**
```sql
INSERT INTO sales_agent_profile (tenant_id, sales_user_id, sales_email, status, created_at, updated_at)
SELECT DISTINCT tenant_id, sales_user_id, sales_email, 'ACTIVE', NOW(), NOW()
FROM dim_merchant
WHERE sales_user_id IS NOT NULL
ON CONFLICT (tenant_id, sales_user_id) DO NOTHING;
```

**Check what SIDs exist first** (your Excel SIDs must match these exactly — case and spaces matter):
```sql
SELECT tenant_id, sales_user_id, sales_email, COUNT(*) AS merchant_count
FROM dim_merchant
WHERE sales_user_id IS NOT NULL
GROUP BY tenant_id, sales_user_id, sales_email
ORDER BY tenant_id, sales_user_id;
```

---

## 4. Step 2 — Create team leads (if not there)

**Via UI:** Sales → Team Management → add team lead (name + email).

**Via API:**
```
POST /api/sales-team/team-leads
{ "name": "Ahmed Hassan", "email": "ahmed@afs.com.bh", "isDefault": false }
```

**Via SQL** (idempotent — re-running updates the name):
```sql
INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default)
VALUES
    (1, 'Ahmed Hassan',  'ahmed@afs.com.bh',  false),
    (1, 'Fatima Ali',    'fatima@afs.com.bh', false)
    -- one row per team lead
ON CONFLICT (tenant_id, team_lead_email)
DO UPDATE SET team_lead_name = EXCLUDED.team_lead_name;
```

Notes:
- `team_lead_email` is the unique key per tenant — same email twice = update, not duplicate.
- Exactly one lead should have `is_default = true` — unmapped agents can be auto-assigned to it ("Auto-assign" button / `POST /api/sales-team/auto-assign`).

**Get the lead IDs you just created** (needed for step 3):
```sql
SELECT id, team_lead_name, team_lead_email, is_default
FROM sales_team_mapping
WHERE tenant_id = 1
ORDER BY id;
```

---

## 5. Step 3 — Assign agents (SIDs) to teams

**Via UI:** Sales → Team Management → pick a lead per agent.

**Via API** (one call per agent):
```
POST /api/sales-team/assign
{ "salesUserId": "S1001", "teamLeadId": 5 }
```

**Via SQL, bulk by lead email** (recommended — no need to look up numeric IDs):
```sql
INSERT INTO sales_user_assignment (tenant_id, sales_user_id, team_lead_id, assigned_at)
SELECT 1, v.sid, m.id, NOW()
FROM (VALUES
    ('S1001', 'ahmed@afs.com.bh'),
    ('S1002', 'ahmed@afs.com.bh'),
    ('S1003', 'fatima@afs.com.bh')
    -- one row per agent: (SID, team lead email)
) AS v(sid, lead_email)
JOIN sales_team_mapping m
  ON m.tenant_id = 1 AND m.team_lead_email = v.lead_email
ON CONFLICT (tenant_id, sales_user_id)
DO UPDATE SET team_lead_id = EXCLUDED.team_lead_id, assigned_at = NOW();
```

Re-running with a different lead email **moves** the agent to the new team (upsert).

**Excel formula** to generate the VALUES rows — SID in column A, lead email in column B, put in C2 and drag down:
```
="('" & TRIM(A2) & "', '" & TRIM(B2) & "'),"
```
Paste column C into the `VALUES (...)` block above; remove the trailing comma on the last row.

---

## 6. Step 4 — Set agent display names

**Via UI:** Sales → Agents (Agent Directory) → edit agent → Display Name.

**Via API:**
```
PUT /api/sales-agents/{salesUserId}
{ "displayName": "Ahmed Hassan" }
```

**Via SQL, bulk:**
```sql
UPDATE sales_agent_profile p
SET display_name = v.name, updated_at = NOW()
FROM (VALUES
    ('S1001', 'Ahmed Hassan'),
    ('S1002', 'Fatima Ali')
    -- one row per agent: (SID, display name)
) AS v(sid, name)
WHERE p.sales_user_id = v.sid
  AND p.tenant_id = 1;
```

**Excel formula** — SID in A, name in B, put in C2 and drag down:
```
="('" & TRIM(A2) & "', '" & SUBSTITUTE(B2,"'","''") & "'),"
```
(`SUBSTITUTE` escapes apostrophes like O'Brien; `TRIM` strips stray spaces.)

Or generate one complete UPDATE per row instead:
```
="UPDATE sales_agent_profile SET display_name = '" & SUBSTITUTE(B2,"'","''") & "', updated_at = NOW() WHERE sales_user_id = '" & TRIM(A2) & "' AND tenant_id = 1;"
```

Do **not** hand-edit `sales_email` — the next sync overwrites it from merchant data. Names are safe; sync never touches them.

---

## 7. Step 5 (optional) — Yearly targets per agent

Set via UI (targets admin screen) or API — targets are entered **yearly** and stored as 12 monthly rows:
```
POST /api/sales/targets/yearly
{ "year": 2026, "salesUserId": "S1001", "annualTarget": 960000,
  "phasing": "EQUAL", "metric": "NET_REVENUE" }
```
- Metrics: `NET_REVENUE` (default), `BASE_VOLUME`, `VOLUME`, `MSF`, `TXNS`.
- `phasing: "MANUAL"` + `"months": [12 values]` for hand-phased months.
- Bulk: `POST /api/sales/targets/bulk` with an array of the same payloads (all-or-nothing).
- Requires ADMIN / SUPER_ADMIN role, and the agent's profile row must already exist (step 1).
- Prefer the API over raw SQL here — the controller handles the monthly split and remainder correctly.

---

## 8. Step 6 — Verify

```sql
-- Full picture: agent → name → team, with merchant counts
SELECT p.sales_user_id,
       p.display_name,
       p.sales_email,
       m.team_lead_name,
       m.team_lead_email,
       (SELECT COUNT(*) FROM dim_merchant dm
         WHERE dm.tenant_id = p.tenant_id
           AND dm.sales_user_id = p.sales_user_id) AS merchant_count
FROM sales_agent_profile p
LEFT JOIN sales_user_assignment a
       ON a.tenant_id = p.tenant_id AND a.sales_user_id = p.sales_user_id
LEFT JOIN sales_team_mapping m ON m.id = a.team_lead_id
WHERE p.tenant_id = 1
ORDER BY m.team_lead_name NULLS LAST, p.display_name;

-- Agents still missing a name
SELECT sales_user_id, sales_email FROM sales_agent_profile
WHERE tenant_id = 1 AND (display_name IS NULL OR display_name = '');

-- Agents not yet assigned to any team
SELECT p.sales_user_id, p.display_name
FROM sales_agent_profile p
LEFT JOIN sales_user_assignment a
       ON a.tenant_id = p.tenant_id AND a.sales_user_id = p.sales_user_id
WHERE p.tenant_id = 1 AND a.id IS NULL;

-- SIDs in your Excel that don't exist in merchant data (typos / mismatches):
-- run the VALUES list through this
SELECT v.sid FROM (VALUES ('S1001'), ('S1002')) AS v(sid)
LEFT JOIN sales_agent_profile p
       ON p.tenant_id = 1 AND p.sales_user_id = v.sid
WHERE p.id IS NULL;
```

---

## 9. Pitfalls checklist

- [ ] `tenant_id` changed from `1` to the correct tenant in EVERY script.
- [ ] SIDs match `dim_merchant.sales_user_id` **exactly** (case-sensitive, trimmed).
- [ ] Sync (step 1) run **before** setting names or targets — profile rows must exist first.
- [ ] Team leads keyed by **email** — reusing an email updates that lead, it doesn't create a second team.
- [ ] Exactly one default lead per tenant (`is_default = true`).
- [ ] Never hand-edit `sales_agent_profile.sales_email` (sync will overwrite it).
- [ ] Merchant re-uploads may re-create `dim_merchant` rows — profiles, teams, and assignments live in their own tables and survive, but re-run sync (step 1) after big uploads to pick up NEW SIDs.
- [ ] API routes need a logged-in admin with menu access to `/sales/agents` and `/sales/team-management`; targets need ADMIN/SUPER_ADMIN role.
