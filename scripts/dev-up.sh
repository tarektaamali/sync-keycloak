#!/usr/bin/env bash
#
# dev-up.sh — one-command local dev stack for Keycloak User Sync.
#
# Brings up the Docker infra (3 Keycloaks + Vault + Postgres), provisions the bits
# that aren't in the images (realm SSL relaxation, service-account roles, Vault
# secrets), then starts the backend (Java 21) and the Angular dev server.
#
# Usage:
#   scripts/dev-up.sh            # infra + backend + frontend
#   WITH_SAMBA=1 scripts/dev-up.sh   # also start the Samba AD container
#
# Stop everything with: scripts/dev-down.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p logs

# The user-sync-agent client secret is fixed by the realm import (infra/realms/*).
AGENT_SECRET="agent-secret"
SAMBA_BIND_PW="Passw0rd!2024"

log()  { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die "docker not found"
docker info >/dev/null 2>&1 || die "docker daemon not running"

JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" || die "Java 21 not found (see scripts/README.md)"
export JAVA_HOME
log "Using JAVA_HOME=$JAVA_HOME"

INFRA="postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault"
[ "${WITH_SAMBA:-0}" = "1" ] && INFRA="$INFRA samba-ad"

# ── 1. Infra ────────────────────────────────────────────────────────────────
log "Starting Docker infra…"
docker compose up -d $INFRA || die "docker compose up failed"

wait_http() { # url label
  for _ in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)
    [ "$code" = "200" ] && { ok "$2 ready"; return 0; }
    sleep 3
  done
  die "timed out waiting for $2 ($1)"
}
log "Waiting for Keycloaks + Vault (realm import ~15–45s)…"
wait_http "http://localhost:8082/realms/app"       "keycloak-app (8082)"
wait_http "http://localhost:8080/realms/master"    "keycloak-ubs (8080)"
wait_http "http://localhost:8081/realms/master"    "keycloak-cs  (8081)"
wait_http "http://localhost:8200/v1/sys/health"    "vault (8200)"

# ── 2. Provision Keycloak (idempotent) ──────────────────────────────────────
kc() { docker compose exec -T "$1" /opt/keycloak/bin/kcadm.sh "${@:2}"; }
kc_login() { kc "$1" config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1; }

log "Relaxing realm SSL (dev only) + granting service-account roles…"
# Realms default to sslRequired=external, which blocks HTTP from the host-run backend.
kc_login keycloak-app; kc keycloak-app update realms/app -s sslRequired=NONE >/dev/null 2>&1
kc_login keycloak-ubs; kc keycloak-ubs update realms/ubs -s sslRequired=NONE >/dev/null 2>&1
kc_login keycloak-cs;  kc keycloak-cs  update realms/cs  -s sslRequired=NONE >/dev/null 2>&1
# The user-sync-agent service accounts need realm-management roles to read/write users.
kc keycloak-ubs add-roles -r ubs --uusername service-account-user-sync-agent \
   --cclientid realm-management --rolename view-users --rolename query-users --rolename manage-users >/dev/null 2>&1
kc keycloak-cs  add-roles -r cs  --uusername service-account-user-sync-agent \
   --cclientid realm-management --rolename view-users --rolename query-users --rolename manage-users >/dev/null 2>&1
ok "Keycloak provisioned"

# ── 3. Provision Vault secrets ──────────────────────────────────────────────
log "Writing service-account secrets to Vault…"
vault_put() { docker compose exec -T -e VAULT_ADDR=http://localhost:8200 -e VAULT_TOKEN=root vault \
              vault kv put "secret/usersync/$1" "$2=$3" >/dev/null 2>&1; }
vault_put UBS   client-secret "$AGENT_SECRET"
vault_put CS    client-secret "$AGENT_SECRET"
vault_put Samba bind-password "$SAMBA_BIND_PW"
ok "Vault secrets written"

# ── 4. Backend (Java 21) ────────────────────────────────────────────────────
if lsof -ti:9090 >/dev/null 2>&1; then
  ok "Backend already running on :9090"
else
  log "Starting backend on :9090…"
  ( cd backend && nohup mvn spring-boot:run > "$ROOT/logs/backend.log" 2>&1 & )
  for _ in $(seq 1 60); do
    grep -q "Started UserSyncApplication" logs/backend.log 2>/dev/null && break
    sleep 2
  done
  grep -q "Started UserSyncApplication" logs/backend.log 2>/dev/null \
    && ok "Backend up (logs/backend.log)" || die "backend didn't start — see logs/backend.log"
fi

# ── 5. Frontend (Angular) ───────────────────────────────────────────────────
if lsof -ti:4200 >/dev/null 2>&1; then
  ok "Frontend already running on :4200"
else
  [ -d frontend/node_modules ] || ( log "Installing frontend deps…"; cd frontend && npm install >/dev/null 2>&1 )
  log "Starting Angular dev server on :4200…"
  ( cd frontend && nohup npm start > "$ROOT/logs/frontend.log" 2>&1 & )
  for _ in $(seq 1 90); do
    grep -qE "Local:.*4200|Application bundle generation complete" logs/frontend.log 2>/dev/null && break
    sleep 2
  done
  ok "Frontend up (logs/frontend.log)"
fi

echo
ok "Stack ready →  http://localhost:4200   (login: admin / admin)"
echo "   Backend API : http://localhost:9090/api   ·  logs/backend.log"
echo "   Keycloak    : ubs.localtest.me:8080 · cs.localtest.me:8081 · app.localtest.me:8082"
echo "   Stop all    : scripts/dev-down.sh"
