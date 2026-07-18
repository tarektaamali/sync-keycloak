# Keycloak User Sync Tool — Design

**Date:** 2026-07-18
**Status:** Approved

## 1. Purpose

A small internal admin tool to **list** and **sync** users between user directories, driven from a web UI. Two independent sync paths:

1. **Samba AD → Keycloak** — the app talks to Samba Active Directory directly over LDAP, then writes users into a chosen target Keycloak via the Admin REST API.
2. **Keycloak UBS → Keycloak CS** — the app reads users from a source bank Keycloak and writes them into a target bank Keycloak, both via the Admin REST API.

Each Keycloak instance represents a bank/enterprise seeded with its own users and roles. The tool itself is protected by OIDC login.

## 2. Architecture & Components

Runs locally via a single `docker-compose.yml`.

| Service | Purpose | Port (example) |
|---|---|---|
| `keycloak-ubs` | Source bank Keycloak — seeded "UBS" realm (users + roles) | 8080 |
| `keycloak-cs` | Target bank Keycloak — seeded "CS" realm | 8081 |
| `keycloak-app` | Own container — dedicated realm for **app admin login**, seeded with an example admin user | 8082 |
| `samba-ad` | Samba Active Directory (LDAP source) | 389 / 636 |
| `backend` | Spring Boot REST API | 9090 |
| `frontend` | Angular SPA served via nginx | 4200 |
| `postgres-ubs`, `postgres-cs`, `postgres-app` | DB storage per Keycloak | 5432+ |

**Flow:** Angular (OIDC login via `keycloak-app`) → Spring Boot backend → one of two pipelines → target Keycloak Admin REST API.

**Seeding:** realms are imported at startup via Keycloak realm-export JSON (`--import-realm`), so users/roles/admin exist immediately with no manual setup.

## 3. Backend (Spring Boot) — Two Independent Pipelines

Approach 2: two self-contained pipelines, no shared abstraction.

**Endpoints** (all require a valid OIDC token from `keycloak-app`; Spring Security resource-server):

- `GET /api/samba/users` — LDAP pipeline: list users from Samba AD.
- `POST /api/samba/sync` — body `{ target, mode, includeRoles }`: read Samba users, write into target Keycloak.
- `GET /api/keycloak/users` — read users from `keycloak-ubs` (source) via Admin REST API.
- `POST /api/keycloak/sync` — body `{ mode, includeRoles }`: read from `keycloak-ubs`, write into `keycloak-cs`.

Services: `SambaSyncService` (LDAP) and `KeycloakSyncService` (REST) — independent, no shared sync engine.

## 4. Data Flow & Sync Semantics

**Fields synced:** `username`, `email`, `firstName`, `lastName`, `enabled` (+ roles when `includeRoles` is true).

**Configurable sync mode** (chosen in UI per sync):

- `create-only` — add users missing in target; skip existing.
- `create-update` — upsert (create if missing, update details if present).
- `mirror` — upsert + delete target users not present in source.

**Roles:** when `includeRoles` is true, missing roles are created in the target on the fly, then assigned.

**Result handling:** sync returns a per-user result list (created / updated / skipped / error). Individual user failures do NOT abort the whole run — they are collected into a summary.

## 5. Frontend (Angular)

- OIDC login against `keycloak-app` (`angular-auth-oidc-client` or `keycloak-js`).
- One page, **two tabs**: "Samba" and "Keycloak UBS→CS".
- Each tab: a **user table** + a **Sync panel** (source/target labels, `mode` dropdown, "include roles" checkbox, **Sync** button) + a **result summary** (created / updated / skipped / errors).

## 6. Error Handling

- Backend collects per-user errors into the sync result rather than failing the batch.
- Missing target roles auto-created when `includeRoles` is on.
- Connection failures (LDAP unreachable, Keycloak admin auth fails) return a clear API error surfaced in the UI.

## 7. Testing

- **Backend:** unit tests for `SambaSyncService` and `KeycloakSyncService` (mock LDAP + Keycloak admin client); optional integration test against dockerized Keycloaks via Testcontainers.
- **Frontend:** component tests for the sync panel + result summary.

## 8. Out of Scope (first version)

- Keycloak native LDAP federation for Samba (app talks to Samba directly instead).
- Scheduled/automatic syncs (manual, UI-triggered only).
- Bidirectional sync (all paths are one-way: source → target).
