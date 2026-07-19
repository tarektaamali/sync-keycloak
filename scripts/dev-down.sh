#!/usr/bin/env bash
#
# dev-down.sh — stop everything started by dev-up.sh.
#
#   scripts/dev-down.sh          # stop backend + frontend, stop & remove Docker stack
#   KEEP_DB=1 scripts/dev-down.sh   # same, but keep the H2 dev DB (backend/data)
#
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

log() { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

log "Stopping backend + frontend…"
pkill -f "spring-boot:run" 2>/dev/null && ok "backend stopped" || echo "  (backend not running)"
pkill -f "ng serve" 2>/dev/null; pkill -f "npm start" 2>/dev/null; ok "frontend stopped"

log "Removing Docker stack…"
docker compose down 2>&1 | tail -3
ok "Docker stack down"

if [ "${KEEP_DB:-0}" != "1" ]; then
  # H2 persists connection profiles + watches across runs; wipe for a clean slate
  # (also avoids the ddl-auto 'Column not found' trap after entity changes).
  rm -f backend/data/usersync* 2>/dev/null && ok "wiped H2 dev DB (backend/data)" || true
else
  ok "kept H2 dev DB (KEEP_DB=1)"
fi
