  #!/usr/bin/env bash
  # start-admin-nm.sh — start the OCCAS/WebLogic AdminServer via Node Manager.
  #
  # The trick: connect WLST straight to Node Manager (nmConnect) — NOT to the
  # AdminServer, which isn't up yet — then nmStart('AdminServer').
  set -euo pipefail

  # ---- config (override via env, or edit these) ------------------------------
  : "${MW_HOME:?set MW_HOME to your OCCAS/WLS middleware home}"
  DOMAIN_NAME="${DOMAIN_NAME:-base_domain}"
  DOMAIN_HOME="${DOMAIN_HOME:-$MW_HOME/user_projects/domains/$DOMAIN_NAME}"
  ADMIN_SERVER="${ADMIN_SERVER:-AdminServer}"
  NM_HOST="${NM_HOST:-localhost}"
  NM_PORT="${NM_PORT:-5556}"
  NM_USER="${NM_USER:-weblogic}"
  NM_TYPE="${NM_TYPE:-ssl}"          # 'ssl' or 'plain' — must match SecureListener in nodemanager.properties

  # NM password: env var, else a gitignored secrets file next to this script, else prompt.
  SECRET="$(dirname "$0")/.nmsecret"          # one line:  NM_PASSWORD=...
  [ -z "${NM_PASSWORD:-}" ] && [ -f "$SECRET" ] && . "$SECRET"
  [ -z "${NM_PASSWORD:-}" ] && { read -rs -p "Node Manager password for ${NM_USER}: " NM_PASSWORD; echo; }

  WLST="$MW_HOME/oracle_common/common/bin/wlst.sh"
  PY="$(mktemp /tmp/nmstart.XXXXXX.py)"
  trap 'rm -f "$PY"' EXIT

  cat > "$PY" <<EOF
  # WLST (Jython). Connect to Node Manager and start the AdminServer.
  try:
      nmConnect('${NM_USER}', '${NM_PASSWORD}', '${NM_HOST}', '${NM_PORT}',
                '${DOMAIN_NAME}', '${DOMAIN_HOME}', '${NM_TYPE}')
      print('Connected to Node Manager at ${NM_HOST}:${NM_PORT}. Starting ${ADMIN_SERVER}...')
      nmStart('${ADMIN_SERVER}')
      print('Status: ' + nmServerStatus('${ADMIN_SERVER}'))
      nmDisconnect()
  except Exception, e:
      print('FAILED: ' + str(e))
      exit(exitcode=1)
  EOF

  echo "Starting ${ADMIN_SERVER} via Node Manager (${NM_HOST}:${NM_PORT}, ${NM_TYPE})..."
  "$WLST" "$PY"
