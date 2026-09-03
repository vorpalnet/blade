#!/usr/bin/env bash
#
# deploy-rest.sh — deploy/undeploy/status via the WebLogic REST Management API
# over plain HTTPS. This is the tunnel-free remote engine: it talks to the same
# endpoint the WebLogic Remote Console uses (/management/weblogic/latest), so it
# rides the admin server's HTTPS listener (or an nginx in front of it) with no
# t3 port, no SSH tunnel, and no WLS HTTP-tunneling. deploy.sh selects it
# automatically when wls.adminurl is http(s); the wlst engine still serves
# on-admin-box deploys.
#
# Contract (env), matching misc/deploy-wls.sh:
#   WLS_ADMINURL  https://host[:port]   (no /management suffix, no t3)
#   WLS_USER WLS_PASSWORD               basic auth (password is plaintext here)
#   WLS_ACTION    deploy|undeploy|status|start|stop
#   WLS_NAME      deployment name
#   WLS_SOURCE    artifact path (deploy)
#   WLS_TARGETS   comma-separated WebLogic target NAMES (deploy)
#   WLS_LIBRARY   true → shared library, else application
#   WLS_INSECURE  true → skip TLS verify;  WLS_CACERT → PEM to verify against
# Exit: 0 ok, 3 could-not-connect (so a batch aborts), else 1.
set -u

BASE="${WLS_ADMINURL%/}/management/weblogic/latest"
ACTION="${WLS_ACTION:-status}"
NAME="${WLS_NAME:-}"
SOURCE="${WLS_SOURCE:-}"
TARGETS="${WLS_TARGETS:-}"
LIBRARY="${WLS_LIBRARY:-false}"
RES="appDeployments"; [ "$LIBRARY" = true ] && RES="libraries"

# Credentials go in a mode-600 config file, never on the command line (argv is
# world-readable via ps) nor in any echoed output.
cfg=$(mktemp "${TMPDIR:-/tmp}/blade-rest.XXXXXX")
body=$(mktemp "${TMPDIR:-/tmp}/blade-rest-body.XXXXXX")
trap 'rm -f "$cfg" "$body"' EXIT
umask 077
printf 'user = "%s:%s"\n' "$WLS_USER" "$WLS_PASSWORD" > "$cfg"

TLS=(); [ "${WLS_INSECURE:-}" = true ] && TLS=(-k)
[ -n "${WLS_CACERT:-}" ] && TLS=(--cacert "$WLS_CACERT")

# curl → prints HTTP code, leaves the response in $body. code 000 = no connect.
req() {
    curl -s ${TLS[@]+"${TLS[@]}"} -K "$cfg" -H 'X-Requested-By: blade-deploy' -H 'Accept: application/json' \
        --max-time 600 -o "$body" -w '%{http_code}' "$@"
}

# A deployment job response is a success unless a target actually failed. On this
# rig engine1/engine2 are down, so the operation is "deferred" for them and
# succeeds on engine0 — deferred is NOT failure; a non-empty failedTargets is.
job_failed() {
    grep -q '"progress"[[:space:]]*:[[:space:]]*"failed"' "$body" && return 0
    grep -Eq '"failedTargets"[[:space:]]*:[[:space:]]*\[[^]]*"[^]]*\]' "$body" && return 0
    return 1
}

print_messages() {  # concise: the last few distinct deploy messages + any failure detail
    # JSON escapes embedded quotes as \" — swap to a placeholder so a message
    # (which itself contains "quoted" words) can be grabbed whole, then restore.
    sed 's/\\"/@/g' "$body" | grep -oE '\[Deployer:[0-9]+\][^"]*' | sed 's/@/"/g' \
        | awk '!seen[$0]++' | tail -4 | sed 's/^/    /'
    grep -oE '"detail"[[:space:]]*:[[:space:]]*"[^"]*"' "$body" \
        | sed -E 's/.*"detail"[^"]*"([^"]*)".*/    \1/'
}

# Map a target NAME to its edit-tree identity (clusters vs servers): /edit/clusters/<n> is 200 for a cluster.
target_identities() {
    local out="" t code
    IFS=',' read -ra names <<< "$TARGETS"
    for t in ${names[@]+"${names[@]}"}; do
        t="${t// /}"; [ -n "$t" ] || continue
        code=$(req "$BASE/edit/clusters/$t?fields=name&links=none")
        local kind="servers"; [ "$code" = 200 ] && kind="clusters"
        out="${out:+$out,}{\"identity\":[\"$kind\",\"$t\"]}"
    done
    printf '[%s]' "$out"
}

case "$ACTION" in
  status)
    # Emit the same machine-readable listing the wlst/maven engines do, so the
    # deploy.sh EAR/loose-WAR collision pre-check can read it:
    #   [app] <name> @ <csv targets>   [lib] <name>   then the DEPLOY_OK sentinel.
    code=$(req "$BASE/edit/appDeployments?fields=name,targets&links=none")
    [ "$code" = 000 ] && { echo "  could not connect to $WLS_ADMINURL" >&2; exit 3; }
    if [ "$code" != 200 ]; then echo "  status query failed (HTTP $code)" >&2; print_messages >&2; exit 1; fi
    # In the pretty JSON, a target is the quoted line right after "clusters"/"servers".
    awk -F'"' '
        /"name":/ { if (nm!="") print "  [app] " nm " @ " tg; nm=$4; tg=""; next }
        ($2=="clusters" || $2=="servers") && $0 !~ /:/ { want=1; next }
        want==1 { tg=(tg==""?$2:tg","$2); want=0; next }
        END { if (nm!="") print "  [app] " nm " @ " tg }
    ' "$body"
    if [ "$(req "$BASE/edit/libraries?fields=name&links=none")" = 200 ]; then
        awk -F'"' '/"name":/ { print "  [lib] " $4 }' "$body"
    fi
    echo "DEPLOY_OK"
    exit 0 ;;

  undeploy)
    code=$(req -X DELETE "$BASE/edit/$RES/$NAME")
    [ "$code" = 000 ] && { echo "  could not connect to $WLS_ADMINURL" >&2; exit 3; }
    if [ "$code" -ge 400 ] || job_failed; then echo "  undeploy $NAME failed (HTTP $code)" >&2; print_messages >&2; exit 1; fi
    echo "  undeployed $NAME"; exit 0 ;;

  deploy)
    [ -f "$SOURCE" ] || { echo "  source not found: $SOURCE" >&2; exit 1; }
    # exists → redeploy (keeps targets); else create with the requested targets.
    code=$(req "$BASE/edit/$RES/$NAME?fields=name&links=none")
    [ "$code" = 000 ] && { echo "  could not connect to $WLS_ADMINURL" >&2; exit 3; }
    if [ "$code" = 200 ]; then
        code=$(req -F "model={\"name\":\"$NAME\"};type=application/json" -F "sourcePath=@${SOURCE}" \
                   -X POST "$BASE/edit/$RES/$NAME/redeploy")
        verb="redeployed"
    else
        local_targets=$(target_identities)
        code=$(req -F "model={\"name\":\"$NAME\",\"targets\":$local_targets};type=application/json" \
                   -F "sourcePath=@${SOURCE}" -X POST "$BASE/edit/$RES")
        verb="deployed"
    fi
    [ "$code" = 000 ] && { echo "  could not connect to $WLS_ADMINURL" >&2; exit 3; }
    if [ "$code" -ge 400 ] || job_failed; then echo "  deploy $NAME failed (HTTP $code)" >&2; print_messages >&2; exit 1; fi
    echo "  $verb $NAME${TARGETS:+ -> $TARGETS}"; print_messages; exit 0 ;;

  start|stop)
    code=$(req -X POST "$BASE/edit/$RES/$NAME/$ACTION")
    [ "$code" = 000 ] && { echo "  could not connect to $WLS_ADMINURL" >&2; exit 3; }
    if [ "$code" -ge 400 ] || job_failed; then echo "  $ACTION $NAME failed (HTTP $code)" >&2; print_messages >&2; exit 1; fi
    echo "  ${ACTION}ed $NAME"; exit 0 ;;

  *) echo "  unknown action: $ACTION" >&2; exit 1 ;;
esac
