# Configurable Connections + UI/UX Redesign — Design

**Date:** 2026-07-18
**Status:** Approved
**Branch:** `feat/configurable-ui-ux`
**Builds on:** the merged user-sync tool (`docs/superpowers/specs/2026-07-18-keycloak-user-sync-design.md`)

## 1. Purpose

Turn the hardcoded UBS→CS / Samba demo into a **configurable, standards-compliant, well-documented** admin tool with a redesigned Bootstrap UI:

- Connections (Keycloak or LDAP/Samba) are **saved profiles**, editable in the UI, working for *any* instance. UBS/CS/Samba ship as seeded examples; Samba is **pre-filled**.
- **Banking-grade secret handling**: secrets live in **HashiCorp Vault**; Keycloak connections authenticate via **service-account clients** (no stored admin password); profiles store only a `secretRef`.
- **UI/UX overhaul** on **Bootstrap**: left-sidebar console, and **hybrid inline help + examples + tooltips** on every option.
- Architect-level extras: **dry-run preview**, **audit log**, **test connection**.
- **Clear docs** to a recognized structure (README + arc42/C4 architecture + security/audit doc).

## 2. Key decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Config model | Saved connection **profiles**, persisted |
| Sync model | **Two separate flows** (Samba→KC, KC→KC), each picks saved connections |
| Secrets | **Vault** (dev container) + **service-account-only** auth for Keycloak; LDAP bind pw in Vault via `secretRef` |
| Profile metadata store | Embedded **H2 file** (no secrets, only refs) |
| Layout | **Left-sidebar console**, Bootstrap |
| Help style | **Hybrid**: always-on one-liner + expandable example + `ⓘ` tooltip |
| Keycloak auth | **Service-account only** (client credentials) |
| Docs | Full set: README + arc42/C4 architecture + security/audit |
| Extras | Dry-run preview, Audit log, Test connection |

## 3. Architecture & components

**New infra (docker-compose):**
- `vault` — HashiCorp Vault in **dev mode**, holds connection secrets under `usersync/<conn>`.
- (Backend embedded) **H2 file DB** — connection profiles + audit log.

**Backend additions (Spring Boot):**
- Connection CRUD + `test` endpoint.
- `SecretStore` interface → `VaultSecretStore` (`spring-cloud-vault`), resolving `vault://usersync/<conn>#<field>`.
- Keycloak admin access refactored from hardcoded master `admin/admin` to **client-credentials** using each connection's `user-sync-agent` service-account client (secret from Vault).
- Sync services (still two independent pipelines) parameterized by **source/target connection IDs**; each gains `plan()` (dry-run) and writes a `SyncRun` audit record on execute.

**Frontend (Angular + Bootstrap):**
- Left-sidebar console: **Connections · Samba→KC · KC→KC · History · Docs**.
- Connections page (list + editor form, Samba pre-filled, Test connection, vault-ref secret fields).
- Two sync flows with connection pickers, hybrid help, dry-run preview → confirm.
- History page (audit log).

## 4. Data model

**`Connection`** (JPA entity, H2):
`id, name, type (KEYCLOAK|LDAP), serverUrl, realm (KC) | baseDn (LDAP), authMethod, clientId (KC) | bindDn (LDAP), secretRef, userSearchBase (LDAP), createdAt`
— **no secret material stored**; `secretRef` points into Vault.

**`SyncRun`** (JPA entity, H2, audit log):
`id, timestamp, actor, sourceConn, targetConn, mode, includeRoles, created, updated, deleted, skipped, errorCount, status`

## 5. API surface

- `GET/POST/PUT/DELETE /api/connections` — profile CRUD (secrets never returned; write path stores secret to Vault + `secretRef` to H2).
- `POST /api/connections/{id}/test` — validate reachability + auth (KC: token via service account; LDAP: bind).
- `POST /api/keycloak/plan` / `POST /api/samba/plan` — dry-run: `{sourceConnId, targetConnId, mode, includeRoles}` → per-user planned actions (created/updated/deleted), **no writes**.
- `POST /api/keycloak/sync` / `POST /api/samba/sync` — execute; returns `SyncResult`; writes a `SyncRun`.
- `GET /api/audit` — list `SyncRun` records.

All `/api/**` remain OIDC-protected (app realm), unchanged.

## 6. Security & standards

- App login unchanged (OIDC via `app` realm).
- **Service-account clients**: seed a `user-sync-agent` confidential client in the `ubs` and `cs` realm JSONs with **least-privilege** `realm-management` roles: `view-users`, `manage-users`, `view-realm`, `manage-realm` (the last two cover role read/create used by includeRoles).
- **Vault**: dev-mode server; backend uses `spring-cloud-vault`. Secrets at `usersync/<conn>` with fields `client-secret` (KC) / `bind-password` (LDAP).
- **H2 stores no secrets** — only `secretRef` strings.
- Rationale documented in `docs/security-audit.md`: hash-vs-encrypt-vs-vault distinction, no user passwords stored (OIDC), PCI-DSS §3.5–3.6, NIST SP 800-57, OWASP ASVS V2/V6. Vault-dev is explicitly labeled a dev control with the production path (real Vault + KMS-backed unseal, rotation) described.

## 7. Out-of-the-box bootstrap (zero-setup)

On first startup, an idempotent bootstrap:
1. Writes default secrets to Vault-dev (`usersync/ubs`, `usersync/cs`, `usersync/samba`).
2. Inserts default `Connection` rows into H2 (UBS, CS, Samba — Samba pre-filled).

So `docker compose up` + backend + frontend yields a working, pre-populated tool with no manual configuration.

## 8. Extras (in scope)

- **Dry-run preview**: `plan()` computes intended changes; UI shows created/updated/deleted per user with a **Confirm & run** gate.
- **Audit log**: every execute persists a `SyncRun`, viewable on the History page.
- **Test connection**: per-profile validation before save.

## 9. Documentation

- `docs/README.md` — setup, run, usage, the `*.localtest.me` hostname note.
- `docs/architecture.md` — arc42/C4 structure (context, containers, components, runtime flows, decisions).
- `docs/security-audit.md` — secret-handling rationale + standards mapping for the auditor.

## 10. Testing

- **Backend unit**: connection CRUD/service, `SecretStore` (fake impl), dry-run `plan()` logic, audit recording, service-account admin client construction.
- **Backend integration** (live containers): `test` endpoints, real dry-run + sync via service account, Vault secret resolution.
- **Frontend**: connection editor (incl. Samba pre-fill + test), sync panel with dry-run/confirm, help components (inline/tooltip/example).

## 11. Out of scope (this iteration)

- Real (non-dev) Vault deployment, unseal/rotation automation (documented as the production path, not built).
- Password-based Keycloak auth (service-account only).
- Bidirectional sync; scheduled/automatic syncs (still manual, UI-triggered).
- Kerberos/mTLS LDAP bind (documented as a stronger option; bind password via Vault is used).
