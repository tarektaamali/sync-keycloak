# Dev scripts

One-command local dev stack for Keycloak User Sync.

## Prerequisites

- **Docker** + Compose (daemon running)
- **Java 21** — the scripts pin it via `/usr/libexec/java_home -v 21` (macOS). Homebrew's `mvn` otherwise runs on Java 25, which breaks the build.
- **Node 20** (for the Angular dev server)

## Usage

```bash
scripts/dev-up.sh        # start everything, then open http://localhost:4200
scripts/dev-down.sh      # stop & remove everything
```

Log in with **`admin` / `admin`**, then use **Connections**, **Keycloak → KC**, **Schedules**, **👁 Watches**, **History**.

### What `dev-up.sh` does

1. Starts the Docker infra — 3 Keycloaks (ubs/cs/app), Vault, 3 Postgres.
2. Waits for the Keycloaks + Vault to be ready.
3. **Provisions the bits not baked into the images** (this is what made the manual setup fiddly):
   - sets `sslRequired=NONE` on the `app`/`ubs`/`cs` realms — otherwise the host-run backend gets 403 / "HTTPS required" from the Keycloak admin API and can't resolve the OIDC issuer;
   - grants the `user-sync-agent` service accounts the `realm-management` roles (`view-users`, `query-users`, `manage-users`);
   - writes the service-account secrets into Vault (`secret/usersync/{UBS,CS,Samba}`).
4. Starts the **backend** on `:9090` (Java 21) → `logs/backend.log`.
5. Starts the **Angular dev server** on `:4200` → `logs/frontend.log`.

All provisioning is idempotent, so re-running is safe.

### Options

| Command | Effect |
|---|---|
| `WITH_SAMBA=1 scripts/dev-up.sh` | also start the Samba AD container (see caveat below) |
| `KEEP_DB=1 scripts/dev-down.sh` | tear down but keep the H2 dev DB (`backend/data`) |

## Notes & gotchas

- **Fresh realms each run.** `dev-down.sh` runs `docker compose down`, so Keycloak/Vault state is wiped; `dev-up.sh` re-provisions from scratch. That's why provisioning runs every time.
- **H2 dev DB** (`backend/data/usersync*`) holds connection profiles + watches and persists across runs. `dev-down.sh` wipes it by default. If you ever see `Column "…" not found` after pulling code that changed an entity, wipe it (`ddl-auto=update` creates new tables but doesn't always add columns to existing ones).
- **Samba/LDAP path.** The dev Samba image requires TLS on LDAP simple bind, so the Samba → Keycloak flow can't be exercised over plain `ldap://` without extra LDAPS/cert setup. The Keycloak → Keycloak flow works fully. `WITH_SAMBA=1` starts the container but does not make the plain-LDAP bind succeed.
- **These realm tweaks are dev-only.** `sslRequired=NONE` must never be used against a real Keycloak.

## Manual equivalent

See the repo root [`README.md`](../README.md) Quick start for the step-by-step manual version.
