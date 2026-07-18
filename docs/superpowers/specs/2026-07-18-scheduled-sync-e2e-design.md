# Scheduled Sync + Playwright Walkthrough — Design

**Date:** 2026-07-18
**Status:** Approved
**Branch:** `feat/scheduled-sync-e2e` (off `feat/configurable-ui-ux`)
**Builds on:** the configurable-connections tool (`2026-07-18-configurable-ui-ux-design.md`)

## 1. Purpose

Two additions:

1. **Configurable scheduled (cron) syncs** — define recurring sync jobs in the UI; an in-process scheduler runs them on their cron, and each run lands in the existing audit log.
2. **Playwright screenshot walkthrough** — an end-to-end test that drives the running app through the happy path, capturing a screenshot per step into `docs/screenshots/`, embedded step-by-step in the README as living documentation.

## 2. Decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Scheduling model | UI-managed `ScheduledJob` entity |
| Scheduling engine | Spring dynamic `ThreadPoolTaskScheduler` (in-process), cron triggers |
| Scheduled run identity | executes real sync as actor `scheduler`; writes a `SyncRun` (shows in History) |
| Playwright scope | Full happy path: login → connections → test → KC dry-run → confirm → history |
| Screenshots | `docs/screenshots/01..06-*.png`, embedded in README |

## 3. Scheduled sync — backend

**Entity `ScheduledJob`** (H2): `id, name, type (KEYCLOAK|SAMBA), sourceConnId, targetConnId, mode, includeRoles, cron, enabled`.

**`ScheduleService`**:
- CRUD over `ScheduledJob`.
- Holds a `ThreadPoolTaskScheduler` and a `Map<Long, ScheduledFuture>` of live registrations.
- `register(job)` schedules a `CronTrigger(job.cron)`; `unregister(id)` cancels. Create/update/enable/disable/delete re-register accordingly. Disabled jobs are not scheduled.
- On startup (`ApplicationRunner`), schedules all `enabled` jobs.
- **Overlap guard**: a per-job `AtomicBoolean`/lock so a still-running job doesn't start again on the next tick; skipped ticks are logged.
- **Execution**: dispatches to `KeycloakSyncService.sync(...)` (type KEYCLOAK) or `SambaSyncService.sync(...)` (type SAMBA) with actor `scheduler`. Errors are caught and recorded (the sync already collects per-user errors; a hard failure is logged and the job stays scheduled).

**REST** (`/api/schedules`, OIDC-protected):
- `GET` → list jobs; `POST` → create; `PUT /{id}` → update; `DELETE /{id}` → delete; `POST /{id}/run` → run immediately (returns `SyncResult`).
- Cron is Spring's 6-field format (`sec min hour dom mon dow`).

## 4. Scheduled sync — frontend

- New **Schedules** route `/schedules` + sidebar item.
- **List**: name, source→target, cron, mode/roles, enabled (toggle), actions (Run now, Edit, Delete).
- **Editor**: name, type (KEYCLOAK|SAMBA), source/target connection pickers (filtered by type like the sync flow), mode + includeRoles (reusing help components), **cron input with hybrid help + examples** (e.g. `0 0 2 * * ?` = daily 02:00), enabled checkbox.
- Uses the existing `ApiService` pattern (new methods for schedules) and help components.

## 5. Playwright walkthrough → README

- Playwright added as a **dev dependency** in `frontend/`, with `playwright.config.ts` (baseURL `http://localhost:4200`, screenshots dir `../docs/screenshots`).
- One spec `e2e/walkthrough.spec.ts` that, against the **already-running** stack:
  1. Opens the app, clicks Login, completes the **Keycloak login form** (`admin`/`admin`) → `01-login.png` (login page) then dashboard.
  2. Connections page → `02-connections.png`; click Test on UBS → `03-test-connection.png`.
  3. Keycloak→KC: pick UBS→CS, Create+update, include roles, Preview → `04-sync-preview.png`.
  4. Confirm & run → `05-sync-result.png`.
  5. History → `06-history.png`.
- **README** gains a **Walkthrough** section embedding the six screenshots with a one-line caption each, and a **Running the E2E test** subsection (prereq: full stack + backend + frontend up; command `npx playwright test`).
- Scope note: this is an **integration/E2E** test requiring the live stack (Keycloak, Vault, backend, frontend) — not part of the unit-test suites; documented as such.

## 6. Testing

- **Backend**: unit tests for `ScheduleService` register/unregister/overlap logic (fake scheduler/sync seams) and the schedules controller (mocked service). `computeNextRun`/dispatch tested without real timers.
- **Frontend**: component test for the schedule editor (emits a schedule request; cron help renders).
- **Playwright**: the walkthrough itself is the E2E test; it must pass (all steps + screenshots produced) against the running stack.

## 7. Plans

- **Plan A** — scheduling: backend (`ScheduledJob`, `ScheduleService`, controller, seeding-none, tests) + frontend (Schedules page/editor, API, route).
- **Plan B** — Playwright walkthrough + README screenshots.

## 8. Out of scope

- Distributed/clustered scheduling, misfire recovery across restarts (in-process scheduler; jobs reload from DB on boot but a missed run while down is not backfilled).
- Per-run notifications/alerting.
- Scheduling dry-runs (schedules always execute real syncs).
