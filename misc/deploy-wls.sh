#!/usr/bin/env bash
# deploy-wls.sh — deploy / redeploy / undeploy / status a WebLogic application or
# shared library via WLST. Self-contained: needs only the OCCAS install's
# wlst.sh (no Maven, no weblogic-maven-plugin). Driven entirely by env vars.
#
# Required: MW_HOME, WLS_ADMINURL (t3://host:port or t3s://host:sslport), and
#   for deploy/undeploy a WLS_NAME (and WLS_SOURCE/WLS_TARGETS for deploy).
# Optional: WLS_USER (weblogic), WLS_PASSWORD (else prompt), WLS_ACTION
#   (deploy|undeploy|status, default deploy), WLS_LIBRARY (true → shared library).
#   For t3s: WLS_TRUSTSTORE (+ WLS_TRUSTSTORE_TYPE, default PKCS12, and
#   WLS_TRUSTSTORE_PASSWORD) — CA trust for the AdminServer's cert. Without it
#   the JVM default truststore is used (CA imported into cacerts).
set -euo pipefail

: "${MW_HOME:?set MW_HOME}"
: "${WLS_ADMINURL:?set WLS_ADMINURL, e.g. t3://10.0.0.10:7001}"
WLS_USER="${WLS_USER:-weblogic}"
WLS_ACTION="${WLS_ACTION:-deploy}"
WLS_NAME="${WLS_NAME:-}"
WLS_SOURCE="${WLS_SOURCE:-}"
WLS_TARGETS="${WLS_TARGETS:-}"
WLS_LIBRARY="${WLS_LIBRARY:-false}"

[ -z "${WLS_PASSWORD:-}" ] && { read -rs -p "WebLogic password for ${WLS_USER}: " WLS_PASSWORD; echo; }

WLST="$MW_HOME/oracle_common/common/bin/wlst.sh"
[ -x "$WLST" ] || { echo "wlst.sh not found/executable: $WLST" >&2; exit 1; }

# t3s: hand SSL trust to the WLST JVM (wlst.sh honors WLST_PROPERTIES).
case "$WLS_ADMINURL" in
    t3s://*)
        # The AdminServer's identity cert names its own hostname (e.g. the CA CN),
        # but T3 rebinds the connection to the server's configured ListenAddress,
        # so the client verifies the cert against THAT and rejects the mismatch.
        # A blade domain is reached by its routable name, not the cert CN, so turn
        # hostname verification off — the trust check below still authenticates the
        # server. wlst.sh puts WLST_PROPERTIES on the java command line, so this is
        # a real startup property (unlike a late env-var read).
        WLST_PROPERTIES="${WLST_PROPERTIES:-} -Dweblogic.security.SSL.ignoreHostnameVerification=true"
        if [ -n "${WLS_TRUSTSTORE:-}" ]; then
            [ -f "$WLS_TRUSTSTORE" ] || { echo "WLS_TRUSTSTORE not found: $WLS_TRUSTSTORE" >&2; exit 1; }
            WLST_PROPERTIES="${WLST_PROPERTIES} -Dweblogic.security.TrustKeyStore=CustomTrust"
            WLST_PROPERTIES="${WLST_PROPERTIES} -Dweblogic.security.CustomTrustKeyStoreFileName=${WLS_TRUSTSTORE}"
            WLST_PROPERTIES="${WLST_PROPERTIES} -Dweblogic.security.CustomTrustKeyStoreType=${WLS_TRUSTSTORE_TYPE:-PKCS12}"
            [ -n "${WLS_TRUSTSTORE_PASSWORD:-}" ] && \
                WLST_PROPERTIES="${WLST_PROPERTIES} -Dweblogic.security.CustomTrustKeyStorePassPhrase=${WLS_TRUSTSTORE_PASSWORD}"
        fi
        export WLST_PROPERTIES
        ;;
esac

case "$WLS_ACTION" in
    deploy)   [ -n "$WLS_NAME" ] && [ -f "$WLS_SOURCE" ] && [ -n "$WLS_TARGETS" ] || { echo "deploy needs WLS_NAME + WLS_SOURCE(file) + WLS_TARGETS" >&2; exit 1; } ;;
    undeploy) [ -n "$WLS_NAME" ] || { echo "undeploy needs WLS_NAME" >&2; exit 1; } ;;
    status)   : ;;
    start|stop) [ -n "$WLS_NAME" ] || { echo "$WLS_ACTION needs WLS_NAME" >&2; exit 1; } ;;
    *) echo "WLS_ACTION must be deploy|undeploy|status|start|stop" >&2; exit 1 ;;
esac

# Jython booleans for the WLST kwargs.
lib="false"; [ "$WLS_LIBRARY" = "true" ] && lib="true"

# For a library deploy, derive the to-deploy identity (Name#Spec@Impl) from the
# WAR manifest. WebLogic names a deployed library exactly that way, so WLST can
# compare and SKIP an unchanged redeploy — saving the work, and dodging the
# "can't undeploy a referenced library" error entirely.
WLS_LIB_ID=""
if [ "$WLS_LIBRARY" = "true" ] && [ "$WLS_ACTION" = "deploy" ] && [ -f "$WLS_SOURCE" ] && command -v unzip >/dev/null 2>&1; then
    _mf="$(unzip -p "$WLS_SOURCE" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r')"
    _ext="$(printf  '%s' "$_mf" | sed -n 's/^Extension-Name:[[:space:]]*//p'        | head -1)"
    _spec="$(printf '%s' "$_mf" | sed -n 's/^Specification-Version:[[:space:]]*//p'  | head -1)"
    _impl="$(printf '%s' "$_mf" | sed -n 's/^Implementation-Version:[[:space:]]*//p' | head -1)"
    WLS_LIB_ID="$_ext"
    [ -n "$_spec" ] && WLS_LIB_ID="${WLS_LIB_ID}#${_spec}"
    [ -n "$_impl" ] && WLS_LIB_ID="${WLS_LIB_ID}@${_impl}"
fi

PY="$(mktemp /tmp/blade-deploy.XXXXXX.py)"
trap 'rm -f "$PY"' EXIT

# WLST is Jython 2.x — note 'except Exception, e'. Pure ASCII (no em-dashes!).
cat > "$PY" <<EOF
# -*- coding: utf-8 -*-
def base(n): return n.split('#')[0]
def app_map(): return dict((base(a.getName()), a.getName()) for a in cmo.getAppDeployments())
def lib_map(): return dict((base(l.getName()), l.getName()) for l in cmo.getLibraries())
# Connect on its own: a connect failure means the AdminServer is unreachable, so
# EVERY app in a batch would fail identically. Exit with a distinct code (3) so
# the caller aborts the whole run instead of retrying a fresh WLST per app.
try:
    connect('${WLS_USER}', '${WLS_PASSWORD}', '${WLS_ADMINURL}')
except Exception, e:
    print('CONNECT_FAILED: ' + str(e))
    exit(exitcode=3)
try:
    action = '${WLS_ACTION}'
    name   = '${WLS_NAME}'
    isLib  = ${lib}
    if action == 'status':
        print('--- deployments on ${WLS_ADMINURL} ---')
        for l in cmo.getLibraries():       print('  [lib] ' + l.getName())
        for a in cmo.getAppDeployments():
            # Append the app's targets (server/cluster names) so a caller can tell
            # WHERE each app runs — a context-root collision is per-target, so the
            # deploy.sh pre-deploy collision guard needs this to scope its check.
            try:    tg = ','.join([t.getName() for t in a.getTargets()])
            except: tg = ''
            print('  [app] ' + a.getName() + ' @ ' + tg)
        print('DEPLOY_OK')
    elif action in ('start', 'stop'):
        # An app the container left PREPARED (activation deferred) is started in
        # place; stop is the mirror. Both need the deployed name (Name#Version).
        m = app_map()
        if name in m:
            if action == 'start': startApplication(m[name], block='true')
            else:                 stopApplication(m[name], block='true')
            print(action.upper() + 'ED ' + m[name])
        else:
            print('NOT_DEPLOYED ' + name)
        print('DEPLOY_OK')
    elif action == 'undeploy':
        m = lib_map() if isLib else app_map()
        if name in m:
            if isLib: undeploy(m[name], block='true', libraryModule='true')
            else:     undeploy(m[name], block='true')
            print('UNDEPLOYED ' + m[name])
        else:
            print('NOT_DEPLOYED ' + name)
        print('DEPLOY_OK')
    else:
        if isLib:
            m = lib_map()
            target_id = '${WLS_LIB_ID}'
            if name in m:
                if m[name] == target_id:
                    # Same Name#Spec@Impl already deployed - nothing to do.
                    print('LIBRARY_UNCHANGED ' + m[name] + ' (same version; not redeploying)')
                else:
                    # Version changed. A referenced library can't be redeployed in
                    # place - undeploy the referencing apps first (undeploy-all),
                    # then redeploy. We do NOT attempt it here (it would fail).
                    print('LIBRARY_VERSION_DIFFERS deployed=' + m[name] + ' new=' + target_id)
                    print('  -> run undeploy-all, then deploy again to update the library.')
            else:
                print('deploy library ' + name + ' (' + target_id + ') -> ${WLS_TARGETS} ...')
                deploy(name, '${WLS_SOURCE}', targets='${WLS_TARGETS}', upload='true', block='true', libraryModule='true')
                print('DEPLOYED_LIBRARY ' + name)
        else:
            m = app_map()
            if name in m:
                print('redeploy ' + m[name] + ' from ${WLS_SOURCE} ...')
                redeploy(m[name], '${WLS_SOURCE}', upload='true', block='true')
                print('REDEPLOYED ' + m[name])
            else:
                print('deploy ' + name + ' -> ${WLS_TARGETS} ...')
                deploy(name, '${WLS_SOURCE}', targets='${WLS_TARGETS}', upload='true', block='true')
                print('DEPLOYED ' + name)
        print('DEPLOY_OK')
    disconnect()
except Exception, e:
    print('DEPLOY_FAILED: ' + str(e))
    exit(exitcode=1)
EOF

echo "WLST ${WLS_ACTION} ${WLS_NAME} via ${WLS_ADMINURL} ..."
"$WLST" "$PY"
