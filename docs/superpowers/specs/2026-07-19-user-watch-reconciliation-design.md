# User Watch & Reconciliation — Design

**Date:** 2026-07-19
**Status:** Approved
**Branch:** `feat/scheduled-sync-e2e` (new work; may branch off before implementation)
**Builds on:** scheduled-sync (`2026-07-18-scheduled-sync-e2e-design.md`) and configurable connections (`2026-07-18-configurable-ui-ux-design.md`)

## 1. Purpose

Add **scoped, per-user reconciliation** to the sync tool. Today the app syncs a whole
realm at once in three modes (`CREATE_ONLY`, `CREATE_UPDATE`, `MIRROR`); `MIRROR` deletes
any target user missing from the source — a whole-realm blast radius that is dangerous for
a bank.

This feature introduces a safer shape: the operator **selects specific users** (a
watchlist) or a **filter rule**, and a cron-scheduled **watch** keeps exactly those users
reconciled on the target Keycloak. If a watched user is **disabled** at the source, the
target is disabled too. If a watched user is **removed** at the source, a per-watch
**policy** decides what happens (disable / delete / ignore). A watch never touches any user
outside its managed set.

Everything is configurable per watch. The feature is built to satisfy a banking-grade audit:
safe defaults, dry-run, and an immutable per-run record.

## 2. Decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Architecture | **Approach A** — new `UserWatch` feature layered on the existing scheduler; the whole-realm `ScheduledJob`/`MIRROR` path is left untouched |
| On source **delete** | **Configurable per watch**: `DISABLE` (default) \| `DELETE` \| `IGNORE` |
| On source **disable** | Always propagated (disable → disable); not separately configurable |
| User selection | **Both** supported per watch: `LIST` (hand-picked usernames) or `FILTER` (search rule) |
| Run action on drift | **Configurable per watch**: `REPORT_ONLY` (default, dry-run) \| `ENFORCE` |
| Sources | `KEYCLOAK` and `SAMBA`/LDAP — same rules for both |
| Run identity & record | executes as actor `watch:<name>`; writes a `SyncRun` (shows in History) |

## 3. Data model

Two new H2 entities, independent of `ScheduledJob`.

### `UserWatch`

| Field | Type | Meaning |
|---|---|---|
| `id` | Long | pk |
| `name` | String | label |
| `type` | enum `KEYCLOAK \| SAMBA` | source kind |
| `sourceConnId` | Long | source connection |
| `targetConnId` | Long | target connection (always a Keycloak) |
| `selectionMode` | enum `LIST \| FILTER` | how covered users are chosen |
| `selectionPayload` | String | `LIST` → comma/JSON list of usernames; `FILTER` → a search rule (see §4.1) |
| `includeRoles` | boolean | reuse existing role handling |
| `onDelete` | enum `DISABLE \| DELETE \| IGNORE` | action when a member disappears from source; default `DISABLE` |
| `runMode` | enum `REPORT_ONLY \| ENFORCE` | default `REPORT_ONLY` |
| `cron` | String | Spring 6-field cron (daily/weekly/monthly are just cron) |
| `enabled` | boolean | scheduled only when true |

### `WatchMember`

A persisted snapshot of exactly which identities each watch governs — this makes deletion
detection deterministic and gives the auditor a durable governed-identity record.

| Field | Type | Meaning |
|---|---|---|
| `id` | Long | pk |
| `watchId` | Long | owning watch |
| `username` | String | governed identity |
| `lastState` | enum `PRESENT \| DISABLED \| REMOVED` | last observed source state |
| `firstSeen` | Instant | first time this watch managed the user |
| `lastSeen` | Instant | last run that observed the user |

Unique on `(watchId, username)`.

## 4. Reconciliation semantics — `ReconcileService`

Pure **plan + apply**, scoped to the managed set only — never the whole realm.

### 4.1 Resolve the covered set

1. Read source users (Keycloak: existing `readAll`; Samba: existing `SambaUserRepository.findAll`,
   which already exposes `enabled` via `userAccountControl & 0x2`).
2. Determine covered users:
   - `LIST` → the named usernames (present in source or not).
   - `FILTER` → source users matching the rule. The rule is a single case-insensitive
     **search term** matched against `username` (and, for Keycloak, delegated to
     `realm.users().search(term)`); for Samba it is an additional substring match on
     `sAMAccountName`. Documented per source; group/role filters are out of scope (§8).
3. Reconcile `WatchMember`: upsert members for currently-covered users (update `lastSeen`);
   members previously governed but no longer covered are candidates for the removal path (§4.2).

### 4.2 Per-user decision

For each governed username:

- **Present & enabled** at source → `CREATE` on target if missing, else `UPDATE` attributes
  (roles if `includeRoles`), `enabled=true`. `lastState=PRESENT`.
- **Present & disabled** at source → `UPDATE` with `enabled=false`. This is the non-optional
  "if disabled, do the same" rule. `lastState=DISABLED`.
- **Was a member, now absent** from source (deleted, or dropped out of the filter) → apply
  `onDelete`:
  - `DISABLE` → `UPDATE` target with `enabled=false`.
  - `DELETE` → remove target user.
  - `IGNORE` → no mutation; recorded only.
  `lastState=REMOVED`.

### 4.3 Apply vs report

- `ENFORCE` applies the plan.
- `REPORT_ONLY` computes the exact same plan and records what it **would** do — mutates
  nothing.

### 4.4 Result & audit

Each run writes an immutable `SyncRun` (actor `watch:<name>`) with counts and per-user
actions, visible in the existing History. **`SyncResult` gains a `disabled` counter**
(today: created/updated/skipped/deleted) so disable actions are distinguishable from plain
updates; this is a small change to the `SyncResult` record, the `SyncRun` entity, and
`AuditService.record`. Per-user errors are collected as today (a failure on one user does not
abort the run).

## 5. Scheduling — `WatchService`

CRUD over `UserWatch` plus cron registration, mirroring the proven `ScheduleService` pattern:

- Holds a `ThreadPoolTaskScheduler` and a `Map<Long, ScheduledFuture>` of live registrations.
- `register(watch)` schedules a `CronTrigger(watch.cron)`; create/update/enable/disable/delete
  re-register accordingly; disabled watches are not scheduled.
- Boot (`ApplicationRunner`) registers all enabled watches.
- **Per-watch overlap guard** (`AtomicBoolean`/lock) so a still-running watch does not start
  again on the next tick; skipped ticks are logged.
- Execution delegates to `ReconcileService` with the watch's `type`, connections, selection,
  policies, and `runMode`. Hard failures are caught and logged; the watch stays scheduled.

## 6. REST API — `/api/watches` (OIDC-protected)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/watches` | list watches |
| POST | `/api/watches` | create |
| PUT | `/api/watches/{id}` | update |
| DELETE | `/api/watches/{id}` | delete (and unregister) |
| POST | `/api/watches/{id}/run` | run now; returns `SyncResult` (respects `runMode`) |
| GET | `/api/watches/{id}/preview` | compute plan without applying (dry-run) |
| GET | `/api/watches/{id}/members` | the managed membership snapshot (audit view) |

Cron uses Spring's 6-field format, consistent with the existing schedules feature.

## 7. Frontend — "Watches" page

New route `/watches` + sidebar item, reusing `ApiService`, connection pickers, and cron-help
components.

- **List**: name, type, source→target, selection summary ("5 users" / "filter: …"),
  `onDelete` policy, a `REPORT`/`ENFORCE` badge, cron, enabled toggle, and actions
  (Run now, Preview, Members, Edit, Delete).
- **Editor**: name; type; source/target connection pickers (filtered by type, like the sync
  flow); **selection toggle** — `LIST` shows a user-picker fetched from the source (checkboxes);
  `FILTER` shows a rule input with per-source help; `includeRoles`; `onDelete` select
  (default `DISABLE`); `runMode` select (default `REPORT_ONLY`); cron input with the existing
  hybrid help; enabled checkbox.
- **Members / Preview** views surface the audit snapshot and the dry-run plan.

## 8. Testing

- **Backend unit**
  - `ReconcileService`: present-enabled, present-disabled, and member-now-absent × each
    `onDelete` (`DISABLE`/`DELETE`/`IGNORE`) × `LIST` and `FILTER`; `REPORT_ONLY` produces a
    non-empty plan but performs zero mutations. Covers both Keycloak and Samba source seams
    (fake source readers / target resource).
  - `WatchService`: register/unregister/overlap-guard logic with a fake scheduler/reconciler.
  - Watches controller: mocked service.
- **Frontend**: watch-editor component test — `LIST`/`FILTER` toggle, emits the expected
  request, cron help renders.
- The Playwright walkthrough is not required for this feature; extending it with a watch
  scenario is optional and out of scope for the core work.

## 9. Audit & security posture (banking)

- **Scoped by design**: a watch mutates only its managed set; there is no whole-realm delete
  path in this feature.
- **Safe defaults**: `onDelete=DISABLE`, `runMode=REPORT_ONLY`. Hard `DELETE` is an explicit,
  per-watch opt-in; disable is reversible.
- **Immutable per-run record**: every run — including report-only — writes a `SyncRun` with
  actor, counts, and per-user actions.
- **Durable governed-identity record**: `WatchMember` proves which identities each watch
  governs and their last observed state.
- **Secrets**: unchanged — resolved from Vault via the existing connection/secret path; no new
  secret handling.
- **Least privilege**: unchanged — service-account Keycloak clients only.
- **Passwords**: never synced or stored (OIDC).

## 10. Out of scope (YAGNI)

- Notifications/alerting on drift or failure (audit log is the record).
- Write-back to Samba/LDAP (target is always Keycloak; one direction).
- Event-driven / real-time sync (cron only; a run missed while the app is down is not
  backfilled, matching the existing scheduler).
- Group/role-based filters beyond the documented single search term (can extend later).
- Distributed/clustered scheduling (in-process, like the existing scheduler).

## 11. Plan outline

- **Plan A — backend**: `UserWatch` + `WatchMember` entities/repos; `ReconcileService`
  (plan + apply, both sources); `SyncResult.disabled` counter threaded through `SyncRun`/audit;
  `WatchService` (CRUD + cron registration + overlap guard + boot); `WatchController`; tests.
- **Plan B — frontend**: watch models + `ApiService` methods; Watches list + editor
  (user-picker, filter, policy/runMode selects, cron help); route + sidebar; component test.
