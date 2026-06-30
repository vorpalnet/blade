#!/usr/bin/env bash
# start-admin-nm.sh — start an OCCAS/WebLogic AdminServer through Node Manager.
#
# In the BLADE layout, Node Manager runs in its OWN domain (nmdomain) and serves
# the app/cluster domains enrolled into it. This connects WLST straight to that
# Node Manager (nmConnect) — NOT to the AdminServer, which isn't up yet — then
# nmStart('AdminServer') for the APP domain named below.
#
# Required env: MW_HOME, DOMAIN_NAME (the app domain). Others have defaults.
set -euo pipefail

: "${MW_HOME:?set MW_HOME to your OCCAS/WLS middleware home}"
: "${DOMAIN_NAME:?set DOMAIN_NAME to the app domain whose AdminServer to start}"
DOMAIN_HOME="${DOMAIN_HOME:-$MW_HOME/user_projects/domains/$DOMAIN_NAME}"
ADMIN_SERVER="${ADMIN_SERVER:-AdminServer}"
NM_HOST="${NM_HOST:-localhost}"
NM_PORT="${NM_PORT:-5556}"
NM_USER="${NM_USER:-weblogic}"
NM_TYPE="${NM_TYPE:-ssl}"          # ssl|plain — must match SecureListener in nodemanager.properties
NM_ACTION="${NM_ACTION:-start}"    # start | kill (stop) the server via Node Manager

# NM password: env var, else a gitignored secret file next to this script, else prompt.
SECRET="$(dirname "$0")/.nmsecret"          # one line:  NM_PASSWORD=...
[ -z "${NM_PASSWORD:-}" ] && [ -f "$SECRET" ] && . "$SECRET"
[ -z "${NM_PASSWORD:-}" ] && { read -rs -p "Node Manager password for ${NM_USER}: " NM_PASSWORD; echo; }

WLST="$MW_HOME/oracle_common/common/bin/wlst.sh"
[ -x "$WLST" ]        || { echo "wlst.sh not found/executable: $WLST" >&2; exit 1; }
[ -d "$DOMAIN_HOME" ] || { echo "app domain home not found: $DOMAIN_HOME" >&2; exit 1; }

# Wait for Node Manager to be listening before nmConnect. At boot the systemd
# nodemanager.service is "active" as soon as its process spawns, but the NM
# listener takes a few seconds more — without this, an ordered weblogic.service
# would race it and fail. Interactively (blade 's') NM is already up, so this
# returns immediately. NM_WAIT_SECS=0 disables.
NM_WAIT_SECS="${NM_WAIT_SECS:-90}"
if [ "$NM_WAIT_SECS" -gt 0 ]; then
    i=0
    until (exec 3<>"/dev/tcp/${NM_HOST}/${NM_PORT}") 2>/dev/null; do
        i=$((i + 1))
        [ "$i" -ge "$NM_WAIT_SECS" ] && { echo "Node Manager not listening on ${NM_HOST}:${NM_PORT} after ${NM_WAIT_SECS}s" >&2; exit 1; }
        sleep 1
    done
    exec 3>&- 2>/dev/null || true
fi

PY="$(mktemp /tmp/nmstart.XXXXXX.py)"
trap 'rm -f "$PY"' EXIT

# WLST is Jython 2.x — note the 'except Exception, e' syntax.
cat > "$PY" <<EOF
try:
    nmConnect('${NM_USER}', '${NM_PASSWORD}', '${NM_HOST}', '${NM_PORT}',
              '${DOMAIN_NAME}', '${DOMAIN_HOME}', '${NM_TYPE}')
    print('Connected to Node Manager at ${NM_HOST}:${NM_PORT}; ${NM_ACTION} ${ADMIN_SERVER}...')
    if '${NM_ACTION}' == 'kill':
        nmKill('${ADMIN_SERVER}')
        print('Status: ' + nmServerStatus('${ADMIN_SERVER}'))
    else:
        nmStart('${ADMIN_SERVER}')
        print('Status: ' + nmServerStatus('${ADMIN_SERVER}'))
    nmDisconnect()
except Exception, e:
    print('FAILED: ' + str(e))
    exit(exitcode=1)
EOF

echo "${NM_ACTION} ${ADMIN_SERVER} (domain ${DOMAIN_NAME}) via Node Manager ${NM_HOST}:${NM_PORT} (${NM_TYPE})..."
"$WLST" "$PY"
