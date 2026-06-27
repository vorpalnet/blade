#!/usr/bin/env bash
# ============================================================================
# install-ssl.sh - Install the TLS keystores and turn on HTTPS / t3s / SIP TLS.
#
# Run this ON the AdminServer node (it WLST-connects to the running AdminServer
# over the existing plaintext t3, and scp's keystores to the engine nodes).
# Reuses the SAME env conf as deploy.sh — connection, engine.nodes, ssh.user —
# so host facts live in exactly one place.
#
# Usage:
#   ./tls/install-ssl.sh <env> [tier] [action] [--dry-run]
#
#   <env>     name → ../build-profiles/deploy/<name>.conf (+ <name>.secret), or a path
#   tier      keystores | ssl | sip   (omit = all three, in that order)
#   action    apply (default) | status
#   --dry-run print what would happen; run nothing
#
# Tiers:
#   keystores  copy blade-identity.p12 + blade-trust.p12 from tls/out/<env>/
#              into tls.keystore.dir on the AdminServer (local) and every
#              engine node (scp). Run make-certs.sh first.
#   ssl        WLST: set CustomIdentityAndCustomTrust + enable the SSL listen
#              port on each server (covers admin HTTPS AND t3s — same port).
#   sip        WLST: create/update the 'sips' NetworkAccessPoint (SIP TLS) on
#              each engine (cluster) server, EnabledProtocolVersions=TLSv1.2,
#              two-way SSL per sip.tls.twoway.
#
# Conf keys (in addition to the deploy keys deploy.sh already reads):
#   tls.keystore.dir      absolute dir, identical on every node, for the p12s
#   tls.identity.alias    private-key alias            (default blade-identity)
#   tls.ssl.port          SSL listen port              (default 7002)
#   tls.ssl.servers       CSV of WLS server names for SSL (default: ALL servers)
#   sip.tls.enabled       master switch for the sip tier   (default true)
#   sip.tls.port          SIPS listen port             (default 5061)
#   sip.tls.twoway        true → mTLS (enforce SBC client cert)
#   sip.tls.servers       CSV of engine server names   (default: members of
#                                                        wls.targets.cluster)
#   sip.tls.versions      EnabledProtocolVersions      (default TLSv1.2)
#
# Secrets (../build-profiles/deploy/<env>.secret) — also accepted as env vars:
#   wls.password              (BLADE_WLS_PASSWORD)
#   tls.keystore.passphrase   (BLADE_TLS_KEYSTORE_PASS)
#   tls.trust.passphrase      (BLADE_TLS_TRUST_PASS)
#
# After this runs, the affected servers must be RESTARTED (SSL + SIP channel
# config is read at boot). HTTPS/t3s: restart that server. SIP TLS: restart the
# engine tier.
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEPLOY_DIR="${REPO_ROOT}/build-profiles/deploy"

ALL_TIERS=(keystores ssl sip)

if [ -z "${NO_COLOR:-}" ] && [ -t 1 ]; then
    C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
    C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
    C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_DIM=""; C_BOLD=""; C_RESET=""
fi
log()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s⚠%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()  { printf '%s✗%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

# --- Parse args ---
ENV_ARG=""; TIER=""; ACTION="apply"; DRY_RUN=false
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,70p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --dry-run) DRY_RUN=true ;;
        -*)        die "Unknown option: $1" ;;
        *)         POSITIONAL+=("$1") ;;
    esac
    shift
done
[ ${#POSITIONAL[@]} -ge 1 ] || die "Environment name or file required."
ENV_ARG="${POSITIONAL[0]}"
for i in "${!POSITIONAL[@]}"; do
    [ "$i" -eq 0 ] && continue
    case "${POSITIONAL[$i]}" in
        apply|status)            ACTION="${POSITIONAL[$i]}" ;;
        keystores|ssl|sip)       TIER="${POSITIONAL[$i]}" ;;
        *) die "Unknown argument: '${POSITIONAL[$i]}'. Expected a tier (keystores|ssl|sip) or action (apply|status)." ;;
    esac
done

# --- Resolve conf + secret ---
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"; ENV_NAME="$(basename "${ENV_ARG%.conf}")"; SECRET_FILE="${ENV_ARG%.conf}.secret"
else
    ENV_NAME="$ENV_ARG"; CONF_FILE="${DEPLOY_DIR}/${ENV_NAME}.conf"; SECRET_FILE="${DEPLOY_DIR}/${ENV_NAME}.secret"
fi
[ -f "$CONF_FILE" ] || die "Conf not found: ${CONF_FILE}"

read_prop() {
    local file="$1" key="$2"
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

WLS_ADMINURL=$(read_prop "$CONF_FILE" "wls.adminurl")
WLS_USER=$(read_prop     "$CONF_FILE" "wls.user")
WLS_CLUSTER=$(read_prop  "$CONF_FILE" "wls.targets.cluster")
SSH_USER=$(read_prop     "$CONF_FILE" "ssh.user")
ENGINE_NODES_RAW=$(read_prop "$CONF_FILE" "engine.nodes")
SHARED_FS=$(read_prop    "$CONF_FILE" "shared.filesystem")
KS_DIR=$(read_prop       "$CONF_FILE" "tls.keystore.dir")
ID_ALIAS=$(read_prop     "$CONF_FILE" "tls.identity.alias"); ID_ALIAS="${ID_ALIAS:-blade-identity}"
SSL_PORT=$(read_prop     "$CONF_FILE" "tls.ssl.port");       SSL_PORT="${SSL_PORT:-7002}"
SSL_SERVERS=$(read_prop  "$CONF_FILE" "tls.ssl.servers")
SIP_ENABLED=$(read_prop  "$CONF_FILE" "sip.tls.enabled");    SIP_ENABLED="${SIP_ENABLED:-true}"
SIP_PORT=$(read_prop     "$CONF_FILE" "sip.tls.port");       SIP_PORT="${SIP_PORT:-5061}"
SIP_TWOWAY=$(read_prop   "$CONF_FILE" "sip.tls.twoway");     SIP_TWOWAY="${SIP_TWOWAY:-false}"
SIP_SERVERS=$(read_prop  "$CONF_FILE" "sip.tls.servers")
SIP_VERSIONS=$(read_prop "$CONF_FILE" "sip.tls.versions");   SIP_VERSIONS="${SIP_VERSIONS:-TLSv1.2}"

[ -n "$KS_DIR" ] || die "${CONF_FILE}: missing tls.keystore.dir"

# --- Which tiers ---
if [ -n "$TIER" ]; then TIERS=("$TIER"); else TIERS=("${ALL_TIERS[@]}"); fi
# Drop the sip tier entirely if disabled (unless explicitly requested).
if [ -z "$TIER" ] && [ "$SIP_ENABLED" != "true" ]; then
    TIERS=(keystores ssl); warn "sip.tls.enabled != true — skipping the sip tier."
fi
needs() { local t; for t in "${TIERS[@]}"; do [ "$t" = "$1" ] && return 0; done; return 1; }

# --- Engine node list (CSV → array) ---
ENGINE_NODES=()
[ -n "$ENGINE_NODES_RAW" ] && IFS=', ' read -r -a ENGINE_NODES <<< "$ENGINE_NODES_RAW"

OUTDIR="${SCRIPT_DIR}/out/${ENV_NAME}"
ID_P12="${OUTDIR}/blade-identity.p12"
TRUST_P12="${OUTDIR}/blade-trust.p12"
ID_BASENAME="$(basename "$ID_P12")"
TRUST_BASENAME="$(basename "$TRUST_P12")"

# --- Secrets needed for WLST (ssl/sip tiers only) ---
NEEDS_WLST=false
needs ssl && NEEDS_WLST=true
needs sip && NEEDS_WLST=true

get_secret() {  # $1=env-var $2=secret-key $3=label $4=allow-empty
    local v="${!1:-}"
    [ -z "$v" ] && [ -f "$SECRET_FILE" ] && v=$(read_prop "$SECRET_FILE" "$2")
    if [ -z "$v" ] && [ "${4:-no}" != "yes" ] && [ "$DRY_RUN" = false ] && [ "$ACTION" != "status" ]; then
        read -rs -p "$3: " v; echo
        [ -n "$v" ] || die "No value for $3"
    fi
    printf '%s' "$v"
}
WLS_PASSWORD=""; KS_PASS=""; TRUST_PASS=""
if [ "$NEEDS_WLST" = true ]; then
    [ -n "$WLS_ADMINURL" ] || die "${CONF_FILE}: missing wls.adminurl"
    [ -n "$WLS_USER" ]     || die "${CONF_FILE}: missing wls.user"
    WLS_PASSWORD=$(get_secret BLADE_WLS_PASSWORD      "wls.password"            "WebLogic password for ${WLS_USER}@${WLS_ADMINURL}")
    KS_PASS=$(get_secret      BLADE_TLS_KEYSTORE_PASS "tls.keystore.passphrase" "Identity keystore passphrase")
    TRUST_PASS=$(get_secret   BLADE_TLS_TRUST_PASS    "tls.trust.passphrase"    "Trust keystore passphrase")
fi

# --- Header ---
log "${C_BOLD}BLADE TLS install${C_RESET}"
log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
log "  tiers:        ${TIERS[*]}"
log "  action:       ${ACTION}"
log "  keystore dir: ${KS_DIR}  (identity alias: ${ID_ALIAS})"
needs ssl && log "  SSL:          port ${SSL_PORT} on ${SSL_SERVERS:-ALL servers}"
needs sip && log "  SIP TLS:      'sips' port ${SIP_PORT}, ${SIP_VERSIONS}, twoway=${SIP_TWOWAY} on ${SIP_SERVERS:-cluster '${WLS_CLUSTER}'}"
[ "$NEEDS_WLST" = true ] && log "  WebLogic:     ${WLS_USER}@${WLS_ADMINURL}"
[ ${#ENGINE_NODES[@]} -gt 0 ] && log "  engine nodes: ${ENGINE_NODES[*]} (ssh ${SSH_USER})"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

# ----------------------------------------------------------------------------
# Tier: keystores — push the p12s to every node's tls.keystore.dir
# ----------------------------------------------------------------------------
tier_keystores() {
    if [ "$ACTION" = "status" ]; then
        info "keystores: present on each node?"
        if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] ls -l ${KS_DIR}/{${ID_BASENAME},${TRUST_BASENAME}} (local$([ "$SHARED_FS" = true ] && echo '; shared FS' || echo ' + each node'))${C_RESET}"; return 0; fi
        ls -l "${KS_DIR}/${ID_BASENAME}" "${KS_DIR}/${TRUST_BASENAME}" 2>&1 || warn "missing locally (AdminServer)"
        if [ "$SHARED_FS" != "true" ]; then
            local n; for n in "${ENGINE_NODES[@]}"; do
                ssh "${SSH_USER}@${n}" ls -l "${KS_DIR}/${ID_BASENAME}" "${KS_DIR}/${TRUST_BASENAME}" 2>&1 || warn "missing on ${n}"
            done
        fi
        return 0
    fi
    # Under --dry-run we only preview, so don't require the keystores to exist yet.
    if [ "$DRY_RUN" = true ]; then
        [ -f "$ID_P12" ] && [ -f "$TRUST_P12" ] || warn "keystores not generated yet (run make-certs.sh) — dry-run continues anyway."
    else
        [ -f "$ID_P12" ]    || die "Missing ${ID_P12} — run ./tls/make-certs.sh ${ENV_NAME} first."
        [ -f "$TRUST_P12" ] || die "Missing ${TRUST_P12} — run ./tls/make-certs.sh ${ENV_NAME} first."
    fi

    # Local (AdminServer)
    info "keystores → (local) ${KS_DIR}/"
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] mkdir -p ${KS_DIR}; cp ${ID_P12} ${TRUST_P12} ${KS_DIR}/${C_RESET}"
    else
        mkdir -p "$KS_DIR"; cp "$ID_P12" "$TRUST_P12" "$KS_DIR/"; chmod 600 "${KS_DIR}/${ID_BASENAME}" "${KS_DIR}/${TRUST_BASENAME}" 2>/dev/null || true
    fi
    # Engine nodes — skipped on a shared filesystem (the local copy is visible
    # to every node at the same path).
    if [ "$SHARED_FS" = "true" ]; then
        info "shared filesystem — keystores at ${KS_DIR}/ are visible to all nodes; skipping per-node copy."
    else
        local n; for n in "${ENGINE_NODES[@]}"; do
            [ -n "$SSH_USER" ] || die "${CONF_FILE}: engine.nodes set but ssh.user missing"
            info "keystores → ${SSH_USER}@${n}:${KS_DIR}/"
            if [ "$DRY_RUN" = true ]; then
                log "${C_DIM}  [dry-run] ssh ${SSH_USER}@${n} mkdir -p ${KS_DIR}; scp ${ID_BASENAME},${TRUST_BASENAME} → ${n}:${KS_DIR}/${C_RESET}"
            else
                ssh "${SSH_USER}@${n}" "mkdir -p '${KS_DIR}' && chmod 700 '${KS_DIR}'"
                scp "$ID_P12" "$TRUST_P12" "${SSH_USER}@${n}:${KS_DIR}/"
                ssh "${SSH_USER}@${n}" "chmod 600 '${KS_DIR}/${ID_BASENAME}' '${KS_DIR}/${TRUST_BASENAME}'"
            fi
        done
    fi
    ok "keystores in place"
}

# ----------------------------------------------------------------------------
# Tiers ssl + sip — one WLST edit session
# ----------------------------------------------------------------------------
run_wlst() {
    [ -n "${MW_HOME:-}" ] || die "MW_HOME not set — needed to find wlst.sh (run on the OCCAS host)."
    local wlst="${MW_HOME}/oracle_common/common/bin/wlst.sh"
    [ -x "$wlst" ] || die "wlst.sh not found/executable: ${wlst}"

    local do_ssl=false do_sip=false
    needs ssl && do_ssl=true
    needs sip && do_sip=true

    # Absolute paths the SERVERS will read (the p12s we pushed to KS_DIR).
    local id_path="${KS_DIR}/${ID_BASENAME}" trust_path="${KS_DIR}/${TRUST_BASENAME}"

    # Secrets + config to the .py via env ONLY — never written to the temp file.
    export BLADE_WLS_ADMINURL="$WLS_ADMINURL" BLADE_WLS_USER="$WLS_USER" BLADE_WLS_PASSWORD="$WLS_PASSWORD"
    export BLADE_DO_SSL="$do_ssl" BLADE_DO_SIP="$do_sip" BLADE_ACTION="$ACTION"
    export BLADE_ID_PATH="$id_path" BLADE_TRUST_PATH="$trust_path"
    export BLADE_KS_PASS="$KS_PASS" BLADE_TRUST_PASS="$TRUST_PASS" BLADE_ID_ALIAS="$ID_ALIAS"
    export BLADE_SSL_PORT="$SSL_PORT" BLADE_SSL_SERVERS="$SSL_SERVERS"
    export BLADE_SIP_PORT="$SIP_PORT" BLADE_SIP_TWOWAY="$SIP_TWOWAY" BLADE_SIP_SERVERS="$SIP_SERVERS"
    export BLADE_SIP_CLUSTER="$WLS_CLUSTER" BLADE_SIP_VERSIONS="$SIP_VERSIONS"

    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] ${wlst} <generated wlst>  (DO_SSL=${do_ssl} DO_SIP=${do_sip} action=${ACTION})${C_RESET}"
        log "${C_DIM}            connect ${WLS_USER}@${WLS_ADMINURL}; identity=${id_path}; trust=${trust_path}${C_RESET}"
        return 0
    fi

    local py; py="$(mktemp /tmp/blade-ssl.XXXXXX.py)"
    trap 'rm -f "$py"' RETURN
    cat > "$py" <<'PYEOF'
import os
adminurl = os.environ['BLADE_WLS_ADMINURL']
user     = os.environ['BLADE_WLS_USER']
pw       = os.environ['BLADE_WLS_PASSWORD']
do_ssl   = os.environ['BLADE_DO_SSL'] == 'true'
do_sip   = os.environ['BLADE_DO_SIP'] == 'true'
action   = os.environ['BLADE_ACTION']
id_path  = os.environ['BLADE_ID_PATH']
tr_path  = os.environ['BLADE_TRUST_PATH']
ks_pass  = os.environ['BLADE_KS_PASS']
tr_pass  = os.environ['BLADE_TRUST_PASS']
alias    = os.environ['BLADE_ID_ALIAS']
ssl_port = int(os.environ['BLADE_SSL_PORT'])
sip_port = int(os.environ['BLADE_SIP_PORT'])
two_way  = os.environ['BLADE_SIP_TWOWAY'] == 'true'
versions = os.environ['BLADE_SIP_VERSIONS']
ssl_srv_csv = os.environ.get('BLADE_SSL_SERVERS', '').strip()
sip_srv_csv = os.environ.get('BLADE_SIP_SERVERS', '').strip()
cluster     = os.environ.get('BLADE_SIP_CLUSTER', '').strip()

def csv(s):
    return [x.strip() for x in s.replace(',', ' ').split() if x.strip()]

connect(user, pw, adminurl)

dc = getMBean('/')                     # domain config root
all_names = [s.getName() for s in dc.getServers()]

def cluster_of(name):
    cd('/Servers/' + name)
    c = cmo.getCluster()
    return c.getName() if c is not None else None

# Which servers get SSL (default: all) and SIP (default: cluster members).
ssl_servers = csv(ssl_srv_csv) if ssl_srv_csv else list(all_names)
if sip_srv_csv:
    sip_servers = csv(sip_srv_csv)
elif cluster:
    sip_servers = [n for n in all_names if cluster_of(n) == cluster]
else:
    sip_servers = []

if action == 'status':
    print('--- SSL status ---')
    for n in all_names:
        cd('/Servers/' + n + '/SSL/' + n)
        print('  %-16s SSL enabled=%s port=%s alias=%s' % (n, cmo.isEnabled(), cmo.getListenPort(), cmo.getServerPrivateKeyAlias()))
    print('--- SIP (sips NAP) status ---')
    for n in all_names:
        cd('/Servers/' + n)
        nap = cmo.lookupNetworkAccessPoint('sips')
        if nap is None:
            print('  %-16s (no sips channel)' % n)
        else:
            print('  %-16s sips enabled=%s port=%s twoWaySSL=%s clientCertEnforced=%s' % (
                n, nap.isEnabled(), nap.getListenPort(), nap.isTwoWaySSLEnabled(), nap.isClientCertificateEnforced()))
    disconnect()
    exit()

edit()
startEdit()
try:
    if do_ssl:
        for n in ssl_servers:
            print('SSL: configuring ' + n)
            cd('/Servers/' + n)
            cmo.setKeyStores('CustomIdentityAndCustomTrust')
            cmo.setCustomIdentityKeyStoreFileName(id_path)
            cmo.setCustomIdentityKeyStoreType('PKCS12')
            cmo.setCustomIdentityKeyStorePassPhrase(ks_pass)
            cmo.setCustomTrustKeyStoreFileName(tr_path)
            cmo.setCustomTrustKeyStoreType('PKCS12')
            cmo.setCustomTrustKeyStorePassPhrase(tr_pass)
            cd('/Servers/' + n + '/SSL/' + n)
            cmo.setEnabled(true)
            cmo.setListenPort(ssl_port)
            cmo.setServerPrivateKeyAlias(alias)
            cmo.setServerPrivateKeyPassPhrase(ks_pass)

    if do_sip:
        from java.util import Properties
        for n in sip_servers:
            print('SIP TLS: configuring sips channel on ' + n)
            cd('/Servers/' + n)
            nap = cmo.lookupNetworkAccessPoint('sips')
            if nap is None:
                nap = cmo.createNetworkAccessPoint('sips')
            nap.setProtocol('sips')
            nap.setListenPort(sip_port)
            nap.setEnabled(true)
            nap.setOutboundEnabled(true)
            nap.setTwoWaySSLEnabled(two_way)
            nap.setClientCertificateEnforced(two_way)
            # Decompiled NetworkAccessPointMBeanUtil defaults EnabledProtocolVersions
            # to TLSv1 when unset — force a modern floor via the NAP custom property.
            try:
                props = nap.getCustomProperties()
                if props is None:
                    props = Properties()
                props.setProperty('EnabledProtocolVersions', versions)
                nap.setCustomProperties(props)
            except Exception, e:
                print('  WARN: could not set EnabledProtocolVersions via WLST (' + str(e) + ').')
                print('        Set it on the sips channel custom properties in the console:')
                print('        EnabledProtocolVersions=' + versions)
    save()
    activate()
    print('OK: TLS configuration activated.')
except Exception, e:
    print('FAILED: ' + str(e))
    try:
        cancelEdit('y')
    except:
        pass
    disconnect()
    exit(exitcode=1)
disconnect()
PYEOF
    "$wlst" "$py"
}

# --- Dispatch ---
RC=0
for t in "${TIERS[@]}"; do
    case "$t" in
        keystores) tier_keystores || RC=$? ;;
        ssl|sip)   : ;;   # handled together below
    esac
    [ ${#TIERS[@]} -gt 1 ] && [ "$t" = "keystores" ] && log ""
done
if needs ssl || needs sip; then
    info "WLST: ${ACTION} (ssl=$(needs ssl && echo yes || echo no), sip=$(needs sip && echo yes || echo no))"
    run_wlst || RC=$?
fi

log ""
if [ $RC -eq 0 ]; then
    ok "${TIER:-all tiers} ${ACTION}: done"
    if [ "$ACTION" = "apply" ] && [ "$DRY_RUN" = false ]; then
        warn "RESTART required: SSL/keystore changes take effect on server restart;"
        warn "SIP TLS takes effect on engine-tier restart."
    fi
else
    die "${TIER:-all tiers} ${ACTION}: failed (exit ${RC})"
fi
