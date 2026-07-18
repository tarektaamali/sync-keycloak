# Keycloak User Sync

An OIDC-protected admin tool to **list and sync users** across identity directories, driven from a web UI. It supports two independent, configurable sync paths:

- **Samba AD → Keycloak** — reads Active Directory over LDAP, writes users into a target Keycloak via the Admin REST API.
- **Keycloak → Keycloak** — reads users from a source bank Keycloak and writes them into a target bank Keycloak.

Connections are **saved profiles** (editable in the UI; UBS/CS/Samba ship pre-seeded), secrets live in **HashiCorp Vault**, Keycloak access uses **least-privilege service-account clients** (no admin password), and every sync supports a **dry-run preview** and is written to an **audit log**.

## Architecture at a glance

A Docker Compose stack runs three Keycloak instances (`keycloak-ubs`, `keycloak-cs`, `keycloak-app`), **HashiCorp Vault** (secrets), a **Samba AD**, and per-instance Postgres. A **Spring Boot** backend exposes an OIDC-secured REST API and stores connection profiles + the audit log in an embedded **H2** file. An **Angular + Bootstrap** SPA logs in via `keycloak-app` and presents a sidebar console: Connections, the two sync flows, and History. See [`architecture.md`](architecture.md) for the C4/arc42 breakdown and [`security-audit.md`](security-audit.md) for the secret-handling rationale.

## Prerequisites

- Docker + Docker Compose
- Java 21 (Maven wrapper or `mvn`)
- Node 20 + npm

## Run it

```bash
# 1. Infrastructure (Keycloaks + Vault + Postgres)
docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault

# 2. Backend (http://localhost:9090) — seeds UBS/CS/Samba connections on first run
cd backend && mvn spring-boot:run

# 3. Frontend (http://localhost:4200)
cd frontend && npm install && npm start
```

Open **http://localhost:4200** and log in with **`admin` / `admin`**.

> Add the `samba-ad` container (`docker compose up -d samba-ad`) if you want to exercise the Samba → Keycloak flow.

## Hostnames (important)

Browser cookies are scoped by **hostname, not port**, so three Keycloaks on plain `localhost` share one cookie jar and their admin-console sessions clobber each other ("Cookie not found" / login loop). Reach each instance by a distinct hostname — `*.localtest.me` all resolve to `127.0.0.1`:

| Instance | Admin console |
|---|---|
| UBS | http://ubs.localtest.me:8080/admin/ |
| CS | http://cs.localtest.me:8081/admin/ |
| App | http://app.localtest.me:8082/admin/ |

The Angular app logs in via `http://app.localtest.me:8082/realms/app`.

## Using the tool

1. **Connections** — the seeded UBS/CS/Samba profiles appear. Add a new one with **+ New connection** (Samba type offers **Use Samba defaults** to pre-fill). Click **Test** to validate reachability/auth before use. Secrets are shown only as a `vault://…` reference.
2. **Keycloak → KC** / **Samba → KC** — pick a source + target connection, choose a **mode** and whether to **include roles** (each option has inline help + a "See example"), click **Preview (dry-run)** to see exactly what would change, then **Confirm & run**.
3. **History** — every executed sync is recorded (actor, source→target, mode, counts, status).

## Secrets

Connection secrets (Keycloak service-account client secrets, LDAP bind passwords) are stored in **Vault**; the profile database holds only a `secretRef`. No end-user passwords are stored — the app authenticates via OIDC. Details and the standards mapping are in [`security-audit.md`](security-audit.md).

## Tests

```bash
cd backend  && mvn test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
