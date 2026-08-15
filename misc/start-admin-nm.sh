#!/usr/bin/env bash
# start-admin-nm.sh — start/stop/restart an OCCAS/WebLogic AdminServer through
# Node Manager.
#
# In the BLADE layout, Node Manager runs in its OWN domain (nmdomain) and serves
# the app/cluster domains enrolled into it. This connects WLST straight to that
# Node Manager (nmConnect) — NOT to the AdminServer, which isn't up yet — then
# nmStart('AdminServer') for the APP domain named below.
#
# NM_ACTION=restart is the load-bearing primitive for the Files app's "restart
# to apply" button: it kills then re-starts the AdminServer. It works ONLY
# because Node Manager is a separate process that outlives the AdminServer — a
# webapp hosted ON the AdminServer cannot restart its own JVM, so it launches
# THIS script DETACHED (new session, see ServerControlAPI) and the script,
# running outside the dying JVM, drives the kill→start through NM.
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
NM_ACTION="${NM_ACTION:-start}"    # start | kill (stop) | restart, via Node Manager
NM_ADMINURL="${NM_ADMINURL:-}"     # for MANAGED servers: t3://<admin>:<port> passed as AdminURL

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
    # NB: no '2>/dev/null' here — on a bare 'exec' redirections are PERMANENT,
    # and it silenced the script's stderr (including WLST errors) forever after.
    exec 3>&- || true
fi

# Same race, one level up. A MANAGED server pulls its configuration from the
# AdminServer at boot, so nmStart fails outright if the admin box isn't up yet.
# On an engine box at boot that is a coin flip: engine and admin machines power
# on together and systemd on the engine has no way to order itself behind a
# service on a DIFFERENT host. So wait here instead. Only applies when
# NM_ADMINURL is set (that is what makes this a managed server);
# ADMIN_WAIT_SECS=0 disables. Kept under the unit's TimeoutStartSec=600.
ADMIN_WAIT_SECS="${ADMIN_WAIT_SECS:-300}"
if [ -n "$NM_ADMINURL" ] && [ "$ADMIN_WAIT_SECS" -gt 0 ]; then
    _hp="${NM_ADMINURL#*://}"       # t3://host:port -> host:port
    _hp="${_hp%%/*}"                # drop any trailing path
    _ah="${_hp%%:*}"
    _ap="${_hp##*:}"
    [ "$_ap" = "$_ah" ] && _ap=7001 # no port in the URL
    echo "Waiting for AdminServer at ${_ah}:${_ap} (up to ${ADMIN_WAIT_SECS}s)..."
    i=0
    until (exec 3<>"/dev/tcp/${_ah}/${_ap}") 2>/dev/null; do
        i=$((i + 1))
        [ "$i" -ge "$ADMIN_WAIT_SECS" ] && { echo "AdminServer not reachable at ${_ah}:${_ap} after ${ADMIN_WAIT_SECS}s" >&2; exit 1; }
        sleep 1
    done
    exec 3>&- || true
    echo "AdminServer is reachable."
fi

PY="$(mktemp /tmp/nmstart.XXXXXX.py)"
trap 'rm -f "$PY"' EXIT

# Derive the launch classpath + JVM args from the domain's own setDomainEnv --
# the canonical, version-correct source (it already folds in the memory tuning
# from setUserOverrides.sh). MBean-mode Node Manager has no start script, so we
# hand these to nmStart as props; without them NM launches a bare weblogic.Server
# that dies on ClassNotFoundException SipServerBean. Sourced in a subshell so it
# can't pollute this script's env or the WLST launch.
eval "$(cd "$DOMAIN_HOME/bin" 2>/dev/null && . ./setDomainEnv.sh >/dev/null 2>&1; \
        printf 'SRV_CP=%q\nSRV_ARGS=%q\n' "${CLASSPATH#:}" "${MEM_ARGS} ${JAVA_OPTIONS}")"

# WLST is Jython 2.x — note the 'except Exception, e' syntax. The generated
# file must declare an encoding (PEP 263): Jython hard-fails on any non-ASCII
# byte without it, with the real error otherwise easy to miss.
cat > "$PY" <<EOF
# -*- coding: utf-8 -*-
from java.util import Properties
def _nmstart(sv):
    # MBean-mode NM builds the JVM command from these startup props, NOT config.xml.
    # ClassPath + Arguments come from the domain's setDomainEnv, so the SIP classpath,
    # LaunchClassLoader, ClasspathServlet allow-list and -ea are all present -- a bare
    # nmStart would launch a weblogic.Server that dies on SipServerBean ClassNotFound.
    cp = '${SRV_CP}'
    if cp == '':
        print('WARNING: no setDomainEnv classpath derived -- bare nmStart (will fail on OCCAS)')
        if '${NM_ADMINURL}' != '':
            nmStart(sv, props=makePropertiesObject('AdminURL=${NM_ADMINURL}'))
        else:
            nmStart(sv)
        return
    p = Properties()
    p.put('ClassPath', cp)
    p.put('Arguments', '${SRV_ARGS}')
    if '${NM_ADMINURL}' != '':
        p.put('AdminURL', '${NM_ADMINURL}')
    nmStart(sv, props=p)
try:
    nmConnect('${NM_USER}', '${NM_PASSWORD}', '${NM_HOST}', '${NM_PORT}',
              '${DOMAIN_NAME}', '${DOMAIN_HOME}', '${NM_TYPE}')
    print('Connected to Node Manager at ${NM_HOST}:${NM_PORT}; ${NM_ACTION} ${ADMIN_SERVER}...')
    if '${NM_ACTION}' == 'kill':
        nmKill('${ADMIN_SERVER}')
        print('Status: ' + nmServerStatus('${ADMIN_SERVER}'))
    elif '${NM_ACTION}' == 'restart':
        # Kill, wait for the OS process to fully release (so the re-start does
        # not hit "address already in use"), then start. nmKill is synchronous
        # but the short poll guards the port-release race without heavy retry
        # scaffolding. A kill that fails because the server is already down is
        # not fatal - fall through to start.
        try:
            nmKill('${ADMIN_SERVER}')
            print('killed; status: ' + nmServerStatus('${ADMIN_SERVER}'))
        except Exception, ke:
            print('kill (continuing): ' + str(ke))
        import time
        for i in range(30):
            s = nmServerStatus('${ADMIN_SERVER}')
            if s != 'RUNNING':
                break
            time.sleep(2)
        _nmstart('${ADMIN_SERVER}')
        print('Status: ' + nmServerStatus('${ADMIN_SERVER}'))
    else:
        # Idempotent: a re-sync run may target an engine (or admin) that's
        # already up. nmStart on a RUNNING server errors ("already running"),
        # so skip it — leave the running server as-is and report success.
        if nmServerStatus('${ADMIN_SERVER}') == 'RUNNING':
            print('${ADMIN_SERVER} already RUNNING — nothing to start')
        else:
            _nmstart('${ADMIN_SERVER}')
        print('Status: ' + nmServerStatus('${ADMIN_SERVER}'))
    nmDisconnect()
except Exception, e:
    print('FAILED: ' + str(e))
    exit(exitcode=1)
EOF

echo "${NM_ACTION} ${ADMIN_SERVER} (domain ${DOMAIN_NAME}) via Node Manager ${NM_HOST}:${NM_PORT} (${NM_TYPE})..."
"$WLST" "$PY"
