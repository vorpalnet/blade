#!/usr/bin/env bash
# ============================================================================
# install-occas.sh - Silent install + dynamic-cluster domain config for OCCAS.
#
# The OCCAS GUI installer needs X11, which is painful/broken over modern macOS
# X11 clients. This drives the two headless steps instead:
#
#   1. install    silent product install (java -jar <installer> -silent ...)
#   2. configure  create the domain from Oracle's DYNAMIC-CLUSTER template
#                 (occas-replicated-dynamiccluster.py + .properties), driven by
#                 your conf. Dynamic clusters generate engine servers from a
#                 count, so adding a node is a config bump, not hand-editing a
#                 server list.
#
# Run this ON the box being installed.
#
# Usage:
#   ./install-occas.sh [<env> [<step>]] [--dry-run]
#     no args → just does the next thing: init if there's no conf, all if
#               OCCAS isn't installed (prep auto-runs via sudo the first
#               time), configure if the domain is missing, start if the
#               AdminServer is down, and stops when everything is up
#     <env>   name → build-profiles/occas/<name>.conf (+ <name>.secret), or a path
#     <step>  init | prep | download | install | configure | secure | start
#             | engines | all | uninstall
#
#   init       short interview → writes the env conf (env name = the WebLogic
#              domain name; machines auto-named machine0=admin, machine1..N)
#   prep       root setup, run AUTOMATICALLY via sudo when first needed:
#              creates install.user ('oracle') + inventory.group ('oinstall')
#              and the oracle.home / installer / inventory / java.dir dirs —
#              owned by YOU, group-shared with oracle (setgid 2775), so no
#              logout/login. Manual form: sudo ./install-occas.sh <env> prep
#   download   fetch the OCCAS media from Oracle eDelivery — headless (curl),
#              mirroring Oracle's generated wget.sh: the per-file URLs carry a
#              license-acceptance token (valid ~8 h) and each request sends the
#              dialog's access token as a Bearer header (valid ~1 h). Neither
#              can be minted from the CLI, so in a browser:
#                1. sign in at https://edelivery.oracle.com, cart the OCCAS
#                   release, pick the platform, accept the license
#                2. 'WGET Options' → 'Download wget.sh' — the script asks for
#                   its path and stashes it as build-profiles/occas/<env>.urls
#                   (gitignored)
#                3. 'WGET Options' → 'Generate Token' → Copy — pasted at the
#                   prompt (or $BLADE_EDELIVERY_TOKEN for headless runs)
#              Files land next to installer.jar (override: download.dir; falls
#              back to ~/occas-media when that's not writable), zips are
#              unpacked (recursively — Oracle nests the media), and it's a
#              no-op once installer.jar exists. 'install' auto-runs this step
#              when installer.jar is missing.
#              Also fetches the official Oracle JDKs into java.dir (~/java):
#              java.runtime (the OCCAS-certified JDK, used to run the
#              installer) and java.javadoc (for blade javadoc builds) — from
#              download.oracle.com permalinks, no login, sha256-verified.
#   install    silent product install — idempotent: skips if ORACLE_HOME already
#              populated, so on a SHARED filesystem it installs once, not per node
#   configure  create the dynamic-cluster domain
#   secure     wire TLS into the EXISTING domain (offline WLST readDomain/
#              updateDomain — run with the domain STOPPED): enable the SSL
#              listen ports with the certs.sh keystores on the AdminServer,
#              the engine server-template, and the static engine; with
#              tls.only=true also disable the plaintext HTTP listen ports and
#              delete the plaintext 'sip' channels (HTTPS/SIPS/t3s only).
#              Conf keys (defaults):  admin.server.name (AdminServer),
#              admin.ssl.port (7002), ssl.listen.port (8002),
#              engine.template.name (BEA_ENGINE_TIER_CLUST-template),
#              tls.only (false), certs.dir (~/.blade/certs/<env>),
#              identity.keystore / trust.keystore ({certs.dir}/identity.p12,
#              {certs.dir}/trust.p12), identity.keystore.type /
#              trust.keystore.type (PKCS12), identity.alias (server).
#              Secret: store.password (or $BLADE_STORE_PASSWORD) — same one
#              certs.sh used.
#   start      boot the domain on THIS box: writes AdminServer boot.properties,
#              starts the per-domain NodeManager, nmStart(AdminServer) via
#              misc/start-admin-nm.sh, waits for the console and prints its
#              URL; engine boxes get a copy-paste NodeManager one-liner
#   engines    ship each engine box what it needs at the SAME paths (rsync over
#              key-based ssh: OCCAS home incl. the domain, runtime JDK, env
#              certs), start its NodeManager, then nmStart its engine server.
#              Unreachable boxes are skipped with a warning; re-runs resume.
#   uninstall  stop everything and DELETE the product, domain, inventory, and
#              certs — this box AND the engine boxes (type the env name to
#              confirm, or pass --yes). KEEPS the eDelivery media, the JDKs,
#              the env conf/secret, and prep's users/dirs — so the next run
#              reinstalls end-to-end unattended. Built for repeated testing.
#   all        install → configure → secure → start → engines (auto-downloads
#              as needed; certs auto-generate — WebLogic demo certs are never
#              used, and NodeManager is re-pointed at the env identity too;
#              replace the generated PKI later via './certs.sh <env> import')
#
# Examples:
#   ./install-occas.sh oci init              # build the env conf interactively
#   ./install-occas.sh oci download          # fetch media from eDelivery (see 'download')
#   ./install-occas.sh oci all --dry-run     # preview install + configure
#   ./install-occas.sh oci install           # just the silent product install
#   ./install-occas.sh oci configure         # just the domain (dynamic cluster)
#
# ----------------------------------------------------------------------------
# ADDING A NODE (the point of the dynamic cluster):
#   * More servers on existing machines → raise dynamic.server.count, re-run
#       ./install-occas.sh <env> configure
#     (or online: connect()/edit(); cd to the cluster's DynamicServers;
#      setMaximumDynamicServerCount(n); activate(); start the new servers.)
#   * A new physical host → add a machine.N line (machineK:<ip>:5556:ssl),
#     append machineK to machine.match.expression, bump dynamic.server.count,
#     then re-run configure.
# ----------------------------------------------------------------------------
#
# !! configure writes the domain with OverwriteDomain=true. If domain.name
#    points at an EXISTING domain dir, it is CLOBBERED. Back up first.
#
# Secrets: admin.password (build-profiles/occas/<env>.secret) or $BLADE_WLS_PASSWORD.
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OCCAS_DIR="${SCRIPT_DIR}/build-profiles/occas"

if [ -z "${NO_COLOR:-}" ] && [ -t 1 ]; then
    C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
    C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_DIM=""; C_BOLD=""; C_RESET=""; fi
log()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s⚠%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()  { printf '%s✗%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

# --- Parse args ---
ENV_ARG=""; STEP=""; DRY_RUN=false; ASSUME_YES=false
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,104p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --dry-run) DRY_RUN=true ;;
        --yes|-y)  ASSUME_YES=true ;;
        -*) die "Unknown option: $1" ;;
        *)  POSITIONAL+=("$1") ;;
    esac
    shift
done
ENV_ARG="${POSITIONAL[0]:-}"
STEP="${POSITIONAL[1]:-}"

read_prop() {
    local file="$1" key="$2"
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

# ask VAR "label" "default"  — prompt with a default (Enter accepts it).
ask() {
    local __v="$1" __label="$2" __def="${3:-}" __in
    if [ -n "$__def" ]; then read -r -p "  ${__label} [${__def}]: " __in
    else read -r -p "  ${__label}: " __in; fi
    [ -z "$__in" ] && __in="$__def"
    printf -v "$__v" '%s' "$__in"
}

# defask VAR "label" "hardcoded-default" "conf-key"  — default = existing conf value, else hardcoded.
defask() {
    local __def; __def="$(read_prop "$CONF_FILE" "$4")"; [ -z "$__def" ] && __def="$3"
    ask "$1" "$2" "$__def"
}

# Default environment name: on OCI, the instance metadata service knows the
# region ('us-ashburn-1' → 'ashburn'); elsewhere the 2s probes fail fast → 'prod'.
guess_env_name() {
    local r
    r="$(curl -sf --max-time 2 -H 'Authorization: Bearer Oracle' \
         http://169.254.169.254/opc/v2/instance/canonicalRegionName 2>/dev/null)" || r=""
    [ -z "$r" ] && { r="$(curl -sf --max-time 2 \
         http://169.254.169.254/opc/v1/instance/canonicalRegionName 2>/dev/null)" || r=""; }
    if [ -n "$r" ]; then
        printf '%s' "$r" | awk -F- '{print $(NF-1)}'
    else
        printf 'prod'
    fi
}

# --- No <env> arg → the only conf wins; otherwise ask ---
if [ -z "$ENV_ARG" ]; then
    CONFS=()
    for f in "$OCCAS_DIR"/*.conf; do [ -e "$f" ] && CONFS+=("$(basename "${f%.conf}")"); done
    if [ ${#CONFS[@]} -eq 1 ]; then
        ENV_ARG="${CONFS[0]}"
    elif [ ${#CONFS[@]} -eq 0 ]; then
        [ -t 0 ] || die "Usage: ./install-occas.sh <env> <init|download|install|configure|secure|all> [--dry-run]"
        warn "No env confs in build-profiles/occas/ yet."
        log "The environment names this site's conf AND its WebLogic domain (not DNS)."
        ask ENV_ARG "Environment name" "$(guess_env_name)"
        [ -n "$ENV_ARG" ] || die "No environment name given."
    else
        [ -t 0 ] || die "Usage: ./install-occas.sh <env> <init|download|install|configure|secure|all> [--dry-run]"
        log "Environments:  ${CONFS[*]}"
        ask ENV_ARG "Environment" "${CONFS[0]}"
        [ -n "$ENV_ARG" ] || die "No environment given."
    fi
fi

# --- Resolve conf + secret ---
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"; ENV_NAME="$(basename "${ENV_ARG%.conf}")"; SECRET_FILE="${ENV_ARG%.conf}.secret"
else
    ENV_NAME="$ENV_ARG"; CONF_FILE="${OCCAS_DIR}/${ENV_NAME}.conf"; SECRET_FILE="${OCCAS_DIR}/${ENV_NAME}.secret"
fi

# --- No <step> arg → just do the next logical thing, no menu ---
INIT_THEN_ALL=false
if [ -z "$STEP" ]; then
    if [ ! -f "$CONF_FILE" ]; then
        STEP="init"
        INIT_THEN_ALL=true
        info "No conf for '${ENV_NAME}' yet — a short interview, then the full install."
    else
        _OH="$(read_prop "$CONF_FILE" "oracle.home")"
        _DN="$(read_prop "$CONF_FILE" "domain.name")"
        if [ ! -d "${_OH}/wlserver" ]; then
            STEP="all"
        elif [ -n "$_DN" ] && [ ! -d "${_OH}/user_projects/domains/${_DN}" ]; then
            STEP="configure"
        else
            _AP="$(read_prop "$CONF_FILE" "admin.port")"; _AP="${_AP:-7001}"
            # AdminServer binds machine.1's addr (dynamic-cluster domains), not
            # localhost; probe that, falling back to localhost for dev domains.
            IFS=: read -r _ _AA _ _ <<< "$(read_prop "$CONF_FILE" "machine.1")"; _AA="${_AA:-127.0.0.1}"
            if (exec 3<>"/dev/tcp/${_AA}/${_AP}") 2>/dev/null || (exec 3<>"/dev/tcp/127.0.0.1/${_AP}") 2>/dev/null; then
                # AdminServer is up — are the engine boxes' NodeManagers?
                _TO=""; command -v timeout >/dev/null 2>&1 && _TO="timeout 3"
                _i=2; _DOWN=""
                while :; do
                    _M="$(read_prop "$CONF_FILE" "machine.${_i}")"
                    [ -n "$_M" ] || break
                    IFS=: read -r _MN _MA _MP _ <<< "$_M"
                    if ! $_TO bash -c "exec 3<>/dev/tcp/${_MA}/${_MP:-5556}" 2>/dev/null; then
                        _DOWN="$_MN"; break
                    fi
                    _i=$((_i + 1))
                done
                if [ -n "$_DOWN" ]; then
                    STEP="engines"
                    info "AdminServer is up, but ${_DOWN}'s NodeManager isn't reachable — running the engines step."
                else
                    ok "Nothing to do: OCCAS installed, domain '${_DN}' up, engine NodeManagers reachable."
                    log "  Rebuild the domain (OVERWRITES it):  ./install-occas.sh ${ENV_NAME} configure"
                    log "  Re-push to engine boxes:             ./install-occas.sh ${ENV_NAME} engines"
                    exit 0
                fi
            else
                STEP="start"
            fi
        fi
    fi
fi
case "$STEP" in init|prep|download|install|configure|secure|start|engines|all|uninstall) ;; *) die "Unknown step: ${STEP}" ;; esac

# Interactive builder for build-profiles/occas/<env>.conf (+ optional secret).
# Crisp on purpose: the environment name IS the WebLogic domain name; machines
# are auto-named machine0 (this admin box) and machine1..N (one per engine);
# NM port 5556/ssl, engine prefix, max cluster size 99, and the installer/
# inventory/java paths (derived from ORACLE_HOME) are assumed — everything is
# editable in the written conf.
do_init() {
    log "${C_BOLD}OCCAS env builder${C_RESET} → ${CONF_FILE}   (WebLogic domain: '${ENV_NAME}')"
    [ -f "$CONF_FILE" ] && warn "${CONF_FILE} exists — its values are offered as defaults; you'll confirm overwrite at the end."

    local oracle_home admin_user dyn_count base ver
    defask oracle_home "ORACLE_HOME"    "/opt/oracle/occas/8.3" "oracle.home"
    defask admin_user  "Admin username" "weblogic"              "admin.username"
    defask dyn_count   "Engine servers" "2"                     "dynamic.server.count"
    case "$dyn_count" in ''|*[!0-9]*|0) die "Engine servers must be a number ≥ 1." ;; esac
    base="$(dirname "$(dirname "$oracle_home")")"
    ver="$(basename "$oracle_home")"

    # machine0 = this admin box; machine1..N = one engine host per server.
    local machines=() match="" addr my_ip="" i=1
    my_ip="$(hostname -I 2>/dev/null | awk '{print $1}')" || true
    log "NodeManager addresses (port 5556, ssl — edit the conf to change):"
    ask addr "machine0 = this admin box" "$my_ip"
    [ -n "$addr" ] || die "machine0 address required."
    machines=("machine0:${addr}:5556:ssl")
    while [ "$i" -le "$dyn_count" ]; do
        ask addr "machine${i} = engine host" ""
        [ -n "$addr" ] || die "machine${i} address required."
        machines+=("machine${i}:${addr}:5556:ssl")
        match="${match:+${match},}machine${i}"
        i=$((i + 1))
    done

    # Always included: static test engine 'engine0' on the admin box — used for
    # testing and BLADE config-file generation, never SBC-targeted.
    local yn static_line="engine0:machine0:8001:5060:5061"

    if [ -f "$CONF_FILE" ]; then
        ask yn "Overwrite ${CONF_FILE}? (y/N)" "N"
        [[ "$yn" =~ ^[Yy] ]] || die "Aborted — conf not written."
    fi
    mkdir -p "$(dirname "$CONF_FILE")"
    {
        echo "# OCCAS install + dynamic-cluster domain '${ENV_NAME}' — by 'install-occas.sh ${ENV_NAME} init'."
        echo "# Admin password lives in $(basename "$SECRET_FILE") (gitignored), not here."
        echo "# NOTE: keep comments on their OWN lines — trailing '# ...' becomes part of the value."
        echo ""
        echo "# --- Silent product install (paths derived from ORACLE_HOME) ---"
        echo "oracle.home=${oracle_home}"
        echo "installer.jar=${base}/install/occas-${ver}/occas_generic.jar"
        echo "inventory.loc=${base}/oraInventory"
        echo "inventory.group=oinstall"
        echo "install.type=Complete with Examples"
        echo ""
        echo "# --- Oracle JDKs (fetched by the download step; runtime = certification matrix) ---"
        echo "java.runtime=21"
        echo "java.javadoc=25"
        echo "java.dir=${base}/java"
        echo ""
        echo "# --- Dynamic-cluster domain (domain name = environment name) ---"
        echo "domain.name=${ENV_NAME}"
        echo "server.start.mode=prod"
        echo "admin.username=${admin_user}"
        echo "server.name.prefix=engine"
        echo "machine.match.expression=${match}"
        echo "dynamic.server.count=${dyn_count}"
        echo "max.dynamic.cluster.size=99"
        echo ""
        echo "# --- Machines + NodeManagers (name:nmAddr:nmPort:nmType) ---"
        echo "# machine0 = the admin box; deliberately NOT in machine.match.expression, so"
        echo "# no dynamic engine lands on it. To ADD a host: add a machine.N line, append"
        echo "# its name to machine.match.expression, bump dynamic.server.count, then"
        echo "#   ./install-occas.sh ${ENV_NAME} configure"
        local idx=1 m
        for m in "${machines[@]}"; do echo "machine.${idx}=${m}"; idx=$((idx + 1)); done
        echo ""
        echo "# --- Static test engine on the admin box (never SBC-targeted; remove to drop) ---"
        echo "static.server=${static_line}"
    } > "$CONF_FILE"
    ok "Wrote ${CONF_FILE}"

    log ""
    ask yn "Save the admin password to $(basename "$SECRET_FILE") now? (y/N)" "N"
    if [[ "$yn" =~ ^[Yy] ]]; then
        if ! git -C "$SCRIPT_DIR" check-ignore -q "$SECRET_FILE" 2>/dev/null; then
            die "REFUSING: ${SECRET_FILE} is not gitignored — fix build-profiles/occas/.gitignore first."
        fi
        local pw pw2
        read -rs -p "  ${admin_user} password: " pw; echo
        read -rs -p "  confirm: " pw2; echo
        [ "$pw" = "$pw2" ] || die "Passwords didn't match — secret not written."
        [ -n "$pw" ]       || die "Empty password — secret not written."
        printf 'admin.password=%s\n' "$pw" > "$SECRET_FILE"
        chmod 600 "$SECRET_FILE"
        ok "Wrote ${SECRET_FILE} (mode 600)"
    fi

}

if [ "$STEP" = "init" ]; then
    do_init
    if [ "$INIT_THEN_ALL" = true ]; then
        STEP="all"
        log ""
        info "Conf written — continuing with the full install (Ctrl-C to stop; re-runs resume)."
    else
        log ""
        ok "Done. Preview the run:  ./install-occas.sh ${ENV_NAME} all --dry-run"
        exit 0
    fi
fi

[ -f "$CONF_FILE" ] || die "Conf not found: ${CONF_FILE} — run './install-occas.sh ${ENV_NAME} init' to build it."

ORACLE_HOME=$(read_prop  "$CONF_FILE" "oracle.home")
INSTALLER_JAR=$(read_prop "$CONF_FILE" "installer.jar")
# The installer location varies per box, so allow an env override without editing
# the conf:  BLADE_OCCAS_INSTALLER=/path/to/occas_generic.jar ./install-occas.sh ...
INSTALLER_JAR="${BLADE_OCCAS_INSTALLER:-$INSTALLER_JAR}"
INV_LOC=$(read_prop      "$CONF_FILE" "inventory.loc")
INV_GROUP=$(read_prop    "$CONF_FILE" "inventory.group"); INV_GROUP="${INV_GROUP:-oinstall}"
INSTALL_USER=$(read_prop "$CONF_FILE" "install.user");    INSTALL_USER="${INSTALL_USER:-oracle}"
INSTALL_TYPE=$(read_prop "$CONF_FILE" "install.type");    INSTALL_TYPE="${INSTALL_TYPE:-Complete with Examples}"

DOMAIN_NAME=$(read_prop  "$CONF_FILE" "domain.name")
START_MODE=$(read_prop   "$CONF_FILE" "server.start.mode"); START_MODE="${START_MODE:-prod}"
ADMIN_USER=$(read_prop   "$CONF_FILE" "admin.username");    ADMIN_USER="${ADMIN_USER:-weblogic}"
SRV_PREFIX=$(read_prop   "$CONF_FILE" "server.name.prefix")
MATCH_EXPR=$(read_prop   "$CONF_FILE" "machine.match.expression")
DYN_COUNT=$(read_prop    "$CONF_FILE" "dynamic.server.count")
MAX_SIZE=$(read_prop     "$CONF_FILE" "max.dynamic.cluster.size")
STATIC_SRV=$(read_prop   "$CONF_FILE" "static.server")

# --- eDelivery download (download step; 'install' auto-runs it when needed) ---
URLS_FILE=$(read_prop "$CONF_FILE" "download.urls.file")
if [ -n "$URLS_FILE" ]; then URLS_FILE="${URLS_FILE/#\~/$HOME}"
else URLS_FILE="${OCCAS_DIR}/${ENV_NAME}.urls"; fi
DL_DIR=$(read_prop "$CONF_FILE" "download.dir")
if [ -n "$DL_DIR" ]; then DL_DIR="${DL_DIR/#\~/$HOME}"
elif [ -n "$INSTALLER_JAR" ]; then DL_DIR="$(dirname "$INSTALLER_JAR")"
else DL_DIR="."; fi

# --- Oracle JDKs (fetched by 'download'; 'install' runs on java.runtime) ---
JAVA_DIR=$(read_prop "$CONF_FILE" "java.dir")
if [ -n "$JAVA_DIR" ]; then JAVA_DIR="${JAVA_DIR/#\~/$HOME}"; else JAVA_DIR="${HOME}/java"; fi
JAVA_RUNTIME=$(read_prop "$CONF_FILE" "java.runtime")
JAVA_JAVADOC=$(read_prop "$CONF_FILE" "java.javadoc")

[ -n "$ORACLE_HOME" ] || die "${CONF_FILE}: missing oracle.home"

# --- Machines (machine.1, machine.2, … = name:addr:port:type) ---
MACHINES=()
i=1
while :; do
    m=$(read_prop "$CONF_FILE" "machine.${i}")
    [ -n "$m" ] || break
    MACHINES+=("$m")
    i=$((i + 1))
done

# --- Admin password (configure + start): env > secret > prompt, asked ONCE ---
# Sets the ADMIN_PW global instead of echoing (a $(…) capture once swallowed
# the post-read newline into the password — see RELEASE 2.9.9).
ADMIN_PW=""
ensure_admin_pw() {
    [ -n "$ADMIN_PW" ] && return 0
    ADMIN_PW="${BLADE_WLS_PASSWORD:-}"
    [ -z "$ADMIN_PW" ] && [ -f "$SECRET_FILE" ] && ADMIN_PW=$(read_prop "$SECRET_FILE" "admin.password")
    if [ -z "$ADMIN_PW" ] && [ "$DRY_RUN" = false ]; then
        read -rs -p "Admin (${ADMIN_USER}) password: " ADMIN_PW; echo >&2
        [ -n "$ADMIN_PW" ] || die "No password provided."
    fi
}

# --- Keystore password: env > secret > AUTO-GENERATED into the env secret ---
# certs.sh's convention (one password for stores + keys). Minted on first need
# so the whole TLS bootstrap is hands-off; users with their own certs put
# their password in <env>.secret (store.password) before running.
STORE_PW=""
ensure_store_pw() {
    [ -n "$STORE_PW" ] && return 0
    STORE_PW="${BLADE_STORE_PASSWORD:-}"
    [ -z "$STORE_PW" ] && [ -f "$SECRET_FILE" ] && STORE_PW=$(read_prop "$SECRET_FILE" "store.password")
    if [ -z "$STORE_PW" ] && [ "$DRY_RUN" = false ]; then
        STORE_PW="$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20)"
        [ "${#STORE_PW}" -ge 12 ] || die "Could not generate a keystore password."
        printf 'store.password=%s\n' "$STORE_PW" >> "$SECRET_FILE"
        chmod 600 "$SECRET_FILE"
        ok "Keystore password minted → $(basename "$SECRET_FILE") (store.password)"
    fi
}

# The env's certs directory (certs.sh's convention).
certs_dir() {
    local d; d="$(read_prop "$CONF_FILE" "certs.dir")"; d="${d/#\~/$HOME}"
    [ -n "$d" ] || d="${HOME}/.blade/certs/${ENV_NAME}"
    printf '%s' "$d"
}

# Fresh self-signed PKI when the env has none — NEVER WebLogic's demo certs
# (their private key ships in every WLS download). Customers replace it later
# with './certs.sh <env> import'.
ensure_certs() {
    local cdir; cdir="$(certs_dir)"
    if [ -f "${cdir}/identity.p12" ]; then
        ok "Certs present: ${cdir}"
        return 0
    fi
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] ./certs.sh ${ENV_NAME} generate — self-signed PKI → ${cdir} (replace later via import)${C_RESET}"
        return 0
    fi
    ensure_store_pw
    info "No certs for '${ENV_NAME}' — generating a fresh PKI (replace later: ./certs.sh ${ENV_NAME} import) …"
    local rj jhome=""
    rj="$(runtime_java)"; [ "$rj" != "java" ] && jhome="${rj%/bin/java}"
    BLADE_STORE_PASSWORD="$STORE_PW" JAVA_HOME="${jhome:-${JAVA_HOME:-}}" \
        "${SCRIPT_DIR}/certs.sh" "$CONF_FILE" generate \
        || die "certs.sh generate failed."
}

# ----------------------------------------------------------------------------
# Step -1 — prep: root setup (user, group, directories) — auto-invoked
# ----------------------------------------------------------------------------
# Layout: the directories are owned by the ADMIN user (you — the one who ran
# sudo), so every later step runs immediately, no logout/login. The 'oracle'
# runtime user (install.user) shares them via inventory.group + setgid.
# Idempotent — safe to re-run.
do_prep() {
    local owner="${SUDO_USER:-$INSTALL_USER}" d
    local dirs=("$ORACLE_HOME" "$INV_LOC" "$JAVA_DIR")
    [ -n "$INSTALLER_JAR" ] && dirs+=("$(dirname "$INSTALLER_JAR")")
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] group ${INV_GROUP}; user ${INSTALL_USER} (-m -g ${INV_GROUP})${C_RESET}"
        for d in "${dirs[@]}"; do
            log "${C_DIM}  [dry-run] mkdir -p ${d}; chown ${owner}:${INV_GROUP}; chmod 2775${C_RESET}"
        done
        log "${C_DIM}  [dry-run] write /etc/profile.d/blade-occas.sh (MW_HOME=${ORACLE_HOME}, JAVA_HOME from ${JAVA_DIR})${C_RESET}"
        return 0
    fi
    [ "$(id -u)" -eq 0 ] || die "prep needs root:  sudo ./install-occas.sh ${ENV_NAME} prep"
    info "Prep: users, groups, directories"
    getent group "$INV_GROUP" >/dev/null 2>&1 || { groupadd "$INV_GROUP"; ok "group ${INV_GROUP} created"; }
    if id -u "$INSTALL_USER" >/dev/null 2>&1; then
        usermod -aG "$INV_GROUP" "$INSTALL_USER"
        ok "user ${INSTALL_USER} exists — ensured ${INV_GROUP} membership"
    else
        useradd -m -g "$INV_GROUP" -s /bin/bash "$INSTALL_USER"
        ok "user ${INSTALL_USER} created (group ${INV_GROUP})"
    fi
    for d in "${dirs[@]}"; do
        mkdir -p "$d"
        chown "${owner}:${INV_GROUP}" "$d"
        chmod 2775 "$d"
        ok "${d}  → ${owner}:${INV_GROUP} (setgid, group-shared with ${INSTALL_USER})"
    done

    # Let cluster traffic flow between the machines: firewalld on OCI/OEL
    # images allows only ssh, which blocks NM (5556), t3 (7001), SIP, and
    # coherence replication. Trust the cluster subnet; public stays filtered.
    local fw_src; fw_src="$(trusted_source)"
    if [ -n "$fw_src" ] && [ "$fw_src" != "none" ] \
       && command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        firewall-cmd --permanent --zone=trusted --add-source="$fw_src" >/dev/null
        firewall-cmd --reload >/dev/null
        ok "firewalld: trusted source ${fw_src} (cluster traffic; public interfaces stay filtered)"
    fi

    # System-wide env for future shells: $MW_HOME (blade's build.sh/bootstrap.sh
    # convention) and a JAVA_HOME/PATH. Prefers the javadoc JDK so blade builds
    # on this box produce javadocs; the OCCAS runtime doesn't care — the domain
    # records its own JAVA_HOME (the JDK the installer ran on) at create time.
    local prof="/etc/profile.d/blade-occas.sh" jglobs=""
    [ -n "$JAVA_JAVADOC" ] && jglobs="\"${JAVA_DIR}\"/jdk-${JAVA_JAVADOC}*"
    [ -n "$JAVA_RUNTIME" ] && jglobs="${jglobs} \"${JAVA_DIR}\"/jdk-${JAVA_RUNTIME}*"
    {
        echo "# Written by blade install-occas.sh ('${ENV_NAME}' prep) — safe to delete."
        echo "export MW_HOME=\"${ORACLE_HOME}\""
        if [ -n "$jglobs" ]; then
            echo 'if [ -z "${JAVA_HOME:-}" ]; then'
            echo "    for __d in ${jglobs}; do"
            echo '        if [ -d "$__d" ]; then export JAVA_HOME="$__d"; break; fi'
            echo '    done'
            echo '    unset __d'
            echo '    [ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"'
            echo 'fi'
        fi
    } > "$prof"
    chmod 644 "$prof"
    ok "${prof}  (MW_HOME + JAVA_HOME for new logins)"
}

# The subnet whose traffic the cluster boxes trust (NM 5556, t3 7001, SIP,
# coherence replication all flow machine-to-machine, and OCI images ship
# firewalld allowing only ssh). Default: machine0's /24; conf override
# firewall.trusted.source=<cidr> (or 'none' to leave firewalld alone).
trusted_source() {
    local s; s="$(read_prop "$CONF_FILE" "firewall.trusted.source")"
    if [ -z "$s" ] && [ ${#MACHINES[@]} -gt 0 ]; then
        local a0; IFS=: read -r _ a0 _ _ <<< "${MACHINES[0]}"
        case "$a0" in *.*.*.*) s="${a0%.*}.0/24" ;; esac
    fi
    printf '%s' "$s"
}

# Runs prep via sudo the first time a step needs the directories — at most
# one sudo password prompt, and nothing else to do by hand.
ensure_prepped() {
    if [ -w "$ORACLE_HOME" ] || { [ ! -e "$ORACLE_HOME" ] && [ -w "$(dirname "$ORACLE_HOME")" ]; }; then
        # dirs are fine — but re-prep if an older prep didn't write the env drop-in
        if [ "$(uname -s)" != "Linux" ] || [ -f /etc/profile.d/blade-occas.sh ]; then
            return 0
        fi
    fi
    if [ "$DRY_RUN" = true ]; then
        if [ -z "${PREP_NOTED:-}" ]; then
            PREP_NOTED=1
            log "${C_DIM}  [dry-run] box not prepped — would run: sudo ./install-occas.sh ${ENV_NAME} prep${C_RESET}"
        fi
        return 0
    fi
    if [ "$(id -u)" -eq 0 ]; then do_prep; return 0; fi
    info "Box not prepped (can't write ${ORACLE_HOME}) — running prep with sudo …"
    sudo "${BASH_SOURCE[0]}" "$CONF_FILE" prep \
        || die "prep failed — run it yourself:  sudo ./install-occas.sh ${ENV_NAME} prep"
}

# ----------------------------------------------------------------------------
# Step 0 — download: OCCAS media from Oracle eDelivery (headless)
# ----------------------------------------------------------------------------
# Same auth model as Oracle's generated wget.sh (2026 token flavor): each
# softwareDownload URL embeds a license-acceptance token (URLs are valid ~8 h),
# and the request carries "Authorization: Bearer <access token>" — the token
# the WGET Options dialog's 'Generate Token' button mints (valid ~1 h). Both
# come from the browser; neither can be minted from the CLI, which is why the
# one-time browser step exists.
# The extracted occas_generic.jar often isn't exactly where the conf's
# installer.jar points (the media unzips into subdirs like combined_scan/) —
# adopt it from wherever a previous run left it, so re-runs do NOTHING.
adopt_installer() {
    local d found
    for d in "$DL_DIR" "${HOME}/occas-media"; do
        [ -d "$d" ] || continue
        found="$(find "$d" -maxdepth 3 -name occas_generic.jar 2>/dev/null | head -1)"
        if [ -n "$found" ]; then
            INSTALLER_JAR="$found"
            return 0
        fi
    done
    return 1
}

do_download() {
    if [ -f "$INSTALLER_JAR" ]; then
        ok "Installer already present: ${INSTALLER_JAR} — skipping download."
        return 0
    fi
    if adopt_installer; then
        ok "Installer already downloaded: ${INSTALLER_JAR} — skipping download."
        return 0
    fi
    ensure_prepped
    if [ ! -f "$URLS_FILE" ]; then
        if [ "$DRY_RUN" = true ]; then
            log "${C_DIM}  [dry-run] no ${URLS_FILE} — would ask for Oracle's wget.sh${C_RESET}"
            return 0
        fi
        log "Getting the OCCAS media takes a ONE-TIME browser step (Oracle's license click):"
        log "  1. sign in at https://edelivery.oracle.com and cart the OCCAS release"
        log "     (search 'Oracle Communications Converged Application Server'),"
        log "     pick the platform, accept the license"
        log "  2. click 'WGET Options' → 'Download wget.sh'"
        log "     (browsing on another machine is fine — scp it to this box)"
        log "  3. in the same dialog, click 'Generate Token' → Copy — you'll paste it here"
        log "  (full walkthrough: build-profiles/occas/README.md)"
        local wsh=""
        [ -t 0 ] && ask wsh "Path to that wget.sh (Enter to quit)" ""
        [ -n "$wsh" ] || die "No wget.sh yet — do the browser step above, then just re-run this; it resumes where it left off."
        wsh="${wsh/#\~/$HOME}"
        [ -f "$wsh" ] || die "Not found: ${wsh}"
        cp "$wsh" "$URLS_FILE"
        ok "Saved as ${URLS_FILE} (gitignored) — Oracle says its URLs are good for ~8 hours."
    fi

    # Pull the tokened URLs out of whatever was saved (Oracle's whole wget.sh,
    # or bare URLs one per line).
    local urls=() u f
    while IFS= read -r u; do urls+=("$u"); done \
        < <(grep -oE 'https://edelivery\.oracle\.com/osdc/softwareDownload\?[^"'"'"'[:space:]]+' "$URLS_FILE" | sort -u)
    [ ${#urls[@]} -ge 1 ] || die "No eDelivery softwareDownload URLs found in ${URLS_FILE}."

    info "Download ${#urls[@]} file(s) from eDelivery → ${DL_DIR}"
    if [ "$DRY_RUN" = true ]; then
        for u in "${urls[@]}"; do f="${u##*fileName=}"; f="${f%%&*}"; log "${C_DIM}  [dry-run] ${f}${C_RESET}"; done
        log "${C_DIM}  [dry-run] curl each URL with the Bearer access token, unzip into ${DL_DIR}${C_RESET}"
        return 0
    fi

    command -v curl  >/dev/null || die "curl not found."
    command -v unzip >/dev/null || die "unzip not found."
    # installer.jar often lives under another user's home (per-box conf) — if
    # that's not writable, download to ~/occas-media and adopt the jar there.
    if ! mkdir -p "$DL_DIR" 2>/dev/null || [ ! -w "$DL_DIR" ]; then
        warn "Can't write to ${DL_DIR} — downloading to ${HOME}/occas-media instead."
        DL_DIR="${HOME}/occas-media"
        mkdir -p "$DL_DIR" || die "Can't create ${DL_DIR}."
    fi

    # The Bearer access token from the WGET Options dialog (valid ~1 hour) —
    # asked for lazily, only when a file actually needs fetching.
    local token="${BLADE_EDELIVERY_TOKEN:-}"

    local dest zips=()
    for u in "${urls[@]}"; do
        f="${u##*fileName=}"; f="${f%%&*}"
        { [ -n "$f" ] && [ "$f" != "$u" ]; } || die "Could not parse fileName= from URL: ${u}"
        dest="${DL_DIR}/${f}"
        # A pre-prep run may have parked the media in the fallback dir — reclaim
        # it rather than burning another token on a re-download.
        if [ ! -f "$dest" ] && [ -f "${HOME}/occas-media/${f}" ] && [ "$DL_DIR" != "${HOME}/occas-media" ]; then
            if mv "${HOME}/occas-media/${f}" "$dest" 2>/dev/null; then
                info "Reclaimed ${f} from ~/occas-media."
            fi
        fi
        if [ -f "$dest" ] && unzip -tqq "$dest" >/dev/null 2>&1; then
            ok "${f} — already downloaded and intact."
        else
            if [ -z "$token" ]; then
                [ -t 0 ] || die "No access token — set \$BLADE_EDELIVERY_TOKEN (browser: WGET Options → 'Generate Token')."
                ask token "Access token ('Generate Token' in the WGET Options dialog, valid ~1 h)" ""
                [ -n "$token" ] || die "No access token given."
            fi
            info "Fetching ${f} …"
            # The token goes in via --config on stdin so it stays out of `ps`.
            # -A: Akamai in front of eDelivery sniffs the User-Agent — curl's
            # default and even custom Mozilla strings get 403; a wget UA (what
            # Oracle's own script sends) passes. Verified 2026-07-15.
            curl -f -L --progress-bar -A "Wget/1.21" -C - -o "$dest" --config - "$u" <<EOF || die "Download failed: ${f} — 401/403 means the access token (~1 h) or the URLs (~8 h) expired. Re-run with a fresh token; if it still fails, delete ${URLS_FILE} and re-run for a fresh wget.sh."
header = "Authorization: Bearer ${token}"
EOF
            if ! unzip -tqq "$dest" >/dev/null 2>&1; then
                if head -c 1024 "$dest" | grep -qi "<html"; then
                    rm -f "$dest"
                    die "eDelivery sent an HTML page instead of ${f} — token or URLs expired. Re-run with a fresh token; if it still fails, delete ${URLS_FILE} and re-run for a fresh wget.sh."
                fi
                die "${dest} is not a valid zip (truncated download?) — delete it and re-run."
            fi
        fi
        zips+=("$dest")
    done

    info "Unpacking into ${DL_DIR} …"
    local z unpacked=" "
    for z in "${zips[@]}"; do unzip -oq "$z" -d "$DL_DIR"; unpacked="${unpacked}${z} "; done

    # Oracle nests the media (V*.zip → OCCAS<ver>GA.zip → occas_generic.jar):
    # keep unpacking whatever zips fall out until the installer shows up.
    local inner found_new
    while [ ! -f "$INSTALLER_JAR" ] \
          && ! find "$DL_DIR" -maxdepth 3 -name occas_generic.jar 2>/dev/null | grep -q .; do
        found_new=false
        while IFS= read -r inner; do
            case "$unpacked" in *" ${inner} "*) continue ;; esac
            info "Unpacking nested $(basename "$inner") …"
            unzip -oq "$inner" -d "$DL_DIR"
            unpacked="${unpacked}${inner} "
            found_new=true
        done < <(find "$DL_DIR" -maxdepth 3 -name '*.zip' 2>/dev/null)
        [ "$found_new" = true ] || break
    done

    if [ -f "$INSTALLER_JAR" ]; then
        ok "Installer ready: ${INSTALLER_JAR}"
    elif adopt_installer; then
        ok "Installer ready: ${INSTALLER_JAR}"
        log "  (not where the conf's installer.jar points — using the downloaded one)"
    else
        warn "No occas_generic.jar in the downloaded media — check what ${URLS_FILE} points at."
    fi
}

# ----------------------------------------------------------------------------
# Step 0b — Oracle JDKs (download.oracle.com permalinks — no login, no token)
# ----------------------------------------------------------------------------
# java.runtime = the JDK OCCAS runs on (keep to Oracle's certification matrix);
# java.javadoc = the JDK for blade javadoc builds (Markdown /// needs 23+).
# Tarballs come from https://download.oracle.com/java/<ver>/latest/ under the
# NFTC license, sha256-verified, unpacked side by side into java.dir. (When a
# version ages out of NFTC — roughly a year after the next LTS ships — new
# updates move behind an OTN login; place a JDK in java.dir by hand then.)

jdk_home_of() {  # <version> → echoes the jdk-<version>* dir under JAVA_DIR, if any
    [ -d "$JAVA_DIR" ] || return 0   # find on a missing dir + pipefail = silent set -e death
    find "$JAVA_DIR" -maxdepth 1 -type d -name "jdk-${1}*" 2>/dev/null | head -1
}

ensure_jdk() {  # <version> <label>
    local ver="$1" label="$2" existing old os_arch tarball url want got
    [ -n "$ver" ] || return 0
    existing="$(jdk_home_of "$ver")"
    if [ -n "$existing" ]; then
        ok "JDK ${ver} (${label}): ${existing}"
        return 0
    fi
    # Reclaim a JDK an earlier run put under ~/java (the old default) rather
    # than re-downloading it.
    if [ -d "${HOME}/java" ] && [ "$JAVA_DIR" != "${HOME}/java" ]; then
        old="$(find "${HOME}/java" -maxdepth 1 -type d -name "jdk-${ver}*" 2>/dev/null | head -1)"
        if [ -n "$old" ] && [ "$DRY_RUN" = false ]; then
            mkdir -p "$JAVA_DIR"
            if mv "$old" "${JAVA_DIR}/" 2>/dev/null; then
                ok "JDK ${ver} (${label}): reclaimed from ${old}"
                return 0
            fi
        fi
    fi
    case "$(uname -s)-$(uname -m)" in
        Linux-x86_64)  os_arch="linux-x64" ;;
        Linux-aarch64) os_arch="linux-aarch64" ;;
        Darwin-arm64)  os_arch="macos-aarch64" ;;
        Darwin-x86_64) os_arch="macos-x64" ;;
        *) die "No Oracle JDK download mapping for $(uname -s)/$(uname -m)." ;;
    esac
    tarball="jdk-${ver}_${os_arch}_bin.tar.gz"
    url="https://download.oracle.com/java/${ver}/latest/${tarball}"
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] JDK ${ver} (${label}): curl ${url} → tar -xz into ${JAVA_DIR}${C_RESET}"
        return 0
    fi
    info "Fetching Oracle JDK ${ver} (${label}) …"
    mkdir -p "$JAVA_DIR"
    curl -f -L --progress-bar -o "${JAVA_DIR}/${tarball}" "$url" \
        || die "JDK ${ver} download failed: ${url}"
    want="$(curl -fsSL "${url}.sha256" 2>/dev/null | awk '{print $1; exit}')"
    if [ -n "$want" ]; then
        if command -v sha256sum >/dev/null 2>&1; then got="$(sha256sum "${JAVA_DIR}/${tarball}" | cut -d' ' -f1)"
        else got="$(shasum -a 256 "${JAVA_DIR}/${tarball}" | cut -d' ' -f1)"; fi
        [ "$got" = "$want" ] || die "JDK ${ver}: sha256 mismatch — delete ${JAVA_DIR}/${tarball} and retry."
    else
        warn "JDK ${ver}: no .sha256 published — skipping checksum."
    fi
    tar -xzf "${JAVA_DIR}/${tarball}" -C "$JAVA_DIR" || die "Extract failed: ${JAVA_DIR}/${tarball}"
    rm -f "${JAVA_DIR}/${tarball}"
    existing="$(jdk_home_of "$ver")"
    [ -n "$existing" ] || die "JDK ${ver}: extracted, but no jdk-${ver}* dir appeared under ${JAVA_DIR}."
    ok "JDK ${ver} (${label}) → ${existing}"
}

do_java() {
    [ -n "${JAVA_RUNTIME}${JAVA_JAVADOC}" ] || return 0
    info "Oracle JDKs → ${JAVA_DIR}"
    ensure_jdk "$JAVA_RUNTIME" "OCCAS runtime"
    ensure_jdk "$JAVA_JAVADOC" "javadoc build"
}

# The java to launch the OCCAS installer with: the managed runtime JDK when
# configured, else whatever 'java' is on the PATH.
runtime_java() {
    local jh
    if [ -n "$JAVA_RUNTIME" ]; then
        jh="$(jdk_home_of "$JAVA_RUNTIME")"
        if [ -n "$jh" ]; then
            if   [ -x "${jh}/bin/java" ];               then printf '%s' "${jh}/bin/java"; return 0
            elif [ -x "${jh}/Contents/Home/bin/java" ]; then printf '%s' "${jh}/Contents/Home/bin/java"; return 0
            fi
        fi
    fi
    printf 'java'
}

# ----------------------------------------------------------------------------
# Step 1 — silent product install
# ----------------------------------------------------------------------------
do_install() {
    # Shared filesystem: the binaries are installed ONCE and every node mounts
    # the same ORACLE_HOME. If it's already populated, this is a no-op — so the
    # step is safe to invoke from any node without reinstalling.
    if [ -d "${ORACLE_HOME}/wlserver" ]; then
        ok "OCCAS already present at ${ORACLE_HOME} (shared filesystem) — skipping install."
        return 0
    fi
    [ -n "$INSTALLER_JAR" ] || die "${CONF_FILE}: missing installer.jar"
    [ -n "$INV_LOC" ]       || die "${CONF_FILE}: missing inventory.loc"
    ensure_prepped
    if [ ! -f "$INSTALLER_JAR" ]; then
        do_download
        log ""
        [ "$DRY_RUN" = true ] || [ -f "$INSTALLER_JAR" ] \
            || die "Still no installer at ${INSTALLER_JAR} after the download — fix installer.jar in ${CONF_FILE} (or set BLADE_OCCAS_INSTALLER)."
    fi
    do_java
    local javabin; javabin="$(runtime_java)"
    if [ "$javabin" = "java" ] && ! command -v java >/dev/null 2>&1; then
        die "No java on the PATH and no managed JDK — set java.runtime in ${CONF_FILE} (the download step fetches it)."
    fi
    info "Silent install → ${ORACLE_HOME}  (installer: ${INSTALLER_JAR}, java: ${javabin})"

    local rsp inv d
    rsp="$(mktemp /tmp/occas-install.XXXXXX.rsp)"
    inv="$(mktemp /tmp/occas-oraInst.XXXXXX.loc)"
    cat > "$rsp" <<EOF
[ENGINE]
Response File Version=1.0.0.0.0

[GENERIC]
DECLINE_AUTO_UPDATES=true
ORACLE_HOME=${ORACLE_HOME}
INSTALL_TYPE=${INSTALL_TYPE}
EOF
    cat > "$inv" <<EOF
inventory_loc=${INV_LOC}
inst_group=${INV_GROUP}
EOF

    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] response file:${C_RESET}"; sed 's/^/    /' "$rsp"
        log "${C_DIM}  [dry-run] oraInst.loc:${C_RESET}";   sed 's/^/    /' "$inv"
        log "${C_DIM}  [dry-run] ${javabin} -jar ${INSTALLER_JAR} -silent -responseFile <rsp> -invPtrLoc <oraInst.loc> -ignoreSysPrereqs${C_RESET}"
        rm -f "$rsp" "$inv"; return 0
    fi
    [ -f "$INSTALLER_JAR" ] || { rm -f "$rsp" "$inv"; die "installer.jar not found: ${INSTALLER_JAR}"; }
    # Fail with the fix, not the installer's cryptic inventory/home errors.
    for d in "$ORACLE_HOME" "$INV_LOC"; do
        [ -d "$d" ] || mkdir -p "$d" 2>/dev/null \
            || die "Can't create ${d} — run:  sudo ./install-occas.sh ${ENV_NAME} prep"
        [ -w "$d" ] \
            || die "No write access to ${d} — run:  sudo ./install-occas.sh ${ENV_NAME} prep"
    done
    "$javabin" -jar "$INSTALLER_JAR" -silent -responseFile "$rsp" -invPtrLoc "$inv" -ignoreSysPrereqs
    rm -f "$rsp" "$inv"
    ok "Product installed at ${ORACLE_HOME}"
}

# Emit the offline-WLST block that creates the optional static test engine as a
# configured member of BEA_ENGINE_TIER_CLUST, with the sip/sips channels added
# by hand (a configured server doesn't inherit the dynamic server template).
# Arg: name:machine:listenPort:sipPort:sipsPort
emit_static_block() {
    local sname smach sport ssip ssips
    IFS=: read -r sname smach sport ssip ssips <<< "$1"
    [ -n "$sname" ] && [ -n "$smach" ] && [ -n "$sport" ] && [ -n "$ssip" ] && [ -n "$ssips" ] \
        || die "bad static.server '$1' (want name:machine:listenPort:sipPort:sipsPort)"
    cat <<PYBLOCK
# --- BLADE: static test engine '${sname}' on machine '${smach}' (config-gen; never SBC-targeted) ---
# A configured server does NOT inherit BEA_ENGINE_TIER_CLUST-template, so its SIP
# channels are added explicitly to mirror that template (sip ${ssip}, sips ${ssips}).
cd('/')
create('${sname}','Server')
cd('/Servers/${sname}')
set('Cluster','BEA_ENGINE_TIER_CLUST')
set('Machine','${smach}')
set('ListenPort',${sport})
create('sip','NetworkAccessPoint')
cd('/Servers/${sname}/NetworkAccessPoints/sip')
set('Protocol','sip')
set('ListenPort',${ssip})
cd('/Servers/${sname}')
create('sips','NetworkAccessPoint')
cd('/Servers/${sname}/NetworkAccessPoints/sips')
set('Protocol','sips')
set('ListenPort',${ssips})
PYBLOCK
}

# ----------------------------------------------------------------------------
# Step 2 — dynamic-cluster domain (Oracle's template, parameterized)
# ----------------------------------------------------------------------------
TEMPLATE_REL="occas/common/templates/scripts/wlst"
do_configure() {
    for k in DOMAIN_NAME SRV_PREFIX MATCH_EXPR DYN_COUNT MAX_SIZE; do
        [ -n "${!k}" ] || die "${CONF_FILE}: missing $(echo "$k" | tr 'A-Z_' 'a-z.') (required for configure)"
    done
    [ ${#MACHINES[@]} -ge 1 ] || die "${CONF_FILE}: no machine.N entries defined"

    local tmpl_dir="${ORACLE_HOME}/${TEMPLATE_REL}"
    local src_py="${tmpl_dir}/occas-replicated-dynamiccluster.py"
    local pw; ensure_admin_pw; pw="$ADMIN_PW"

    info "Configure domain '${DOMAIN_NAME}' (${START_MODE}) — dynamic cluster"
    log  "  prefix=${SRV_PREFIX}  match=${MATCH_EXPR}  count=${DYN_COUNT}  max=${MAX_SIZE}"
    log  "  machines:"
    local m name addr port type idx=1
    # Build the generated .properties body (machine loop) up front so dry-run shows it.
    local props
    props="ADMIN_USERNAME=${ADMIN_USER}
ADMIN_PASSWORD=__PW__
ServerNamePrefix=${SRV_PREFIX}
MachineNameMatchExpression=${MATCH_EXPR}
MaximumDynamicServerCount=${DYN_COUNT}
MaxDynamicClusterSize=${MAX_SIZE}"
    for m in "${MACHINES[@]}"; do
        IFS=: read -r name addr port type <<< "$m"
        [ -n "$name" ] && [ -n "$addr" ] && [ -n "$port" ] && [ -n "$type" ] \
            || die "bad machine entry '${m}' (want name:addr:port:type)"
        log  "    ${idx}. ${name}  nm=${addr}:${port} (${type})"
        props="${props}
Machine${idx}Name=${name}
Machine${idx}NodemanagerListenPort=${port}
Machine${idx}NodemanagerListenAddress=${addr}
Machine${idx}NodemanagerNMType=${type}"
        idx=$((idx + 1))
    done

    if [ -n "$STATIC_SRV" ]; then
        log  "  static test engine: ${STATIC_SRV}  (name:machine:listen:sip:sips)"
    fi

    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] stage ${src_py} + generated .properties into a workdir${C_RESET}"
        log "${C_DIM}  [dry-run] generated .properties (password redacted):${C_RESET}"
        printf '%s\n' "$props" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] sed .py: domainName='${DOMAIN_NAME}', ServerStartMode='${START_MODE}'${C_RESET}"
        if [ -n "$STATIC_SRV" ]; then
            log "${C_DIM}  [dry-run] inject static-engine WLST before writeDomain:${C_RESET}"
            emit_static_block "$STATIC_SRV" | sed 's/^/    /'
        fi
        log "${C_DIM}  [dry-run] source setWLSEnv.sh; BEA_HOME=${ORACLE_HOME}; java weblogic.WLST occas-replicated-dynamiccluster.py${C_RESET}"
        return 0
    fi

    [ -f "$src_py" ] || die "Template not found: ${src_py} (is OCCAS installed? run the install step first)"
    local setwls="${ORACLE_HOME}/wlserver/server/bin/setWLSEnv.sh"
    [ -f "$setwls" ] || die "setWLSEnv.sh not found: ${setwls}"

    # Stage in a temp workdir (the .py reads the .properties by RELATIVE name, so
    # both must sit in the cwd we run from). Shred it after — it holds the password.
    local work; work="$(mktemp -d /tmp/occas-cfg.XXXXXX)"
    trap 'rm -rf "$work"' RETURN
    cp "$src_py" "${work}/occas-replicated-dynamiccluster.py"
    printf '%s\n' "${props/__PW__/$pw}" > "${work}/occas-replicated-dynamiccluster.properties"
    chmod 600 "${work}/occas-replicated-dynamiccluster.properties"

    # Parameterize the staged .py copy (domain name + start mode); OverwriteDomain
    # is already true in the template.
    sed "s/^domainName=.*/domainName='${DOMAIN_NAME}'/; \
         s/setOption('ServerStartMode', '[^']*')/setOption('ServerStartMode', '${START_MODE}')/" \
        "${work}/occas-replicated-dynamiccluster.py" > "${work}/.py.tmp" \
        && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"

    # Inject the static test-engine WLST just before writeDomain (so the 'admin'
    # machine the loop created already exists when we reference it).
    if [ -n "$STATIC_SRV" ]; then
        emit_static_block "$STATIC_SRV" > "${work}/static-engine.block"
        awk 'NR==FNR { blk = blk $0 ORS; next }
             /OverwriteDomain/ && !ins { printf "%s", blk; ins = 1 }
             { print }' \
            "${work}/static-engine.block" "${work}/occas-replicated-dynamiccluster.py" \
            > "${work}/.py.tmp" && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"
    fi

    # Seed the domain's NodeManager credentials (Oracle's template doesn't) —
    # without them every nmConnect gets "Access to domain ... denied". Uses the
    # admin account; injected before writeDomain like the static block.
    {
        echo "# --- BLADE: NodeManager credentials (nmConnect auth) ---"
        echo "cd('/SecurityConfiguration/' + domainName)"
        echo "set('NodeManagerUsername', '${ADMIN_USER}')"
        echo "set('NodeManagerPasswordEncrypted', '${pw}')"
    } > "${work}/nm-creds.block"
    awk 'NR==FNR { blk = blk $0 ORS; next }
         /OverwriteDomain/ && !ins { printf "%s", blk; ins = 1 }
         { print }' \
        "${work}/nm-creds.block" "${work}/occas-replicated-dynamiccluster.py" \
        > "${work}/.py.tmp" && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"
    chmod 600 "${work}/nm-creds.block" "${work}/occas-replicated-dynamiccluster.py"

    # Run Oracle's offline WLST from the workdir.
    (
        cd "$work"
        # Oracle's env scripts predate 'set -u' and read unset vars
        # (commEnv.sh wants MW_HOME, commBaseEnv.sh reads JAVA_VENDOR, …).
        export MW_HOME="$ORACLE_HOME"
        set +u
        # shellcheck disable=SC1090
        . "$setwls" >/dev/null
        export BEA_HOME="$ORACLE_HOME"          # the .py uses BEA_HOME for the domain dir
        java weblogic.WLST occas-replicated-dynamiccluster.py
    )
    ok "Domain '${DOMAIN_NAME}' written under ${ORACLE_HOME}/user_projects/domains/"
}

# ----------------------------------------------------------------------------
# Step 3 — start: NodeManager + AdminServer on THIS box (engines get a one-liner)
# ----------------------------------------------------------------------------
# Boots the freshly configured domain: writes AdminServer boot.properties
# (unattended starts; WebLogic encrypts it on first boot), starts the
# per-domain NodeManager if it isn't listening, then drives nmStart(AdminServer)
# through misc/start-admin-nm.sh and waits for the console port. Engine boxes
# only need their NodeManager started — printed as a per-box one-liner (the
# domain lives on the shared filesystem, so the path is the same there).
listening() { (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null; }   # <host> <port>

# AdminServer up? Dynamic-cluster domains bind it to machine0's configured
# address (not localhost); dev domains bind localhost/all — accept either.
admin_listening() { listening "$1" "$2" || listening 127.0.0.1 "$2"; }   # <admin-addr> <port>

# When secure_nodemanager swapped NM onto the env certificate, WLST's nmConnect
# must trust the env CA instead of Oracle's demo trust. The passphrase rides a
# -D flag — visible in ps on this box for the seconds WLST runs.
nm_wlst_props() {  # <domain_dir> → echoes WLST_PROPERTIES ('' when NM is on demo certs)
    local cdir pw
    if grep -q "^CustomIdentityKeyStoreFileName=" "$1/nodemanager/nodemanager.properties" 2>/dev/null; then
        # read-only password lookup — runs inside $(…), so no minting/printing here
        pw="${STORE_PW:-${BLADE_STORE_PASSWORD:-}}"
        [ -z "$pw" ] && [ -f "$SECRET_FILE" ] && pw="$(read_prop "$SECRET_FILE" "store.password")"
        [ -n "$pw" ] || return 0
        cdir="$(certs_dir)"
        printf '%s' "-Dweblogic.security.TrustKeyStore=CustomTrust -Dweblogic.security.CustomTrustKeyStoreFileName=${cdir}/trust.p12 -Dweblogic.security.CustomTrustKeyStoreType=PKCS12 -Dweblogic.security.CustomTrustKeyStorePassPhrase=${pw}"
    fi
}

do_start() {
    local domain_dir="${ORACLE_HOME}/user_projects/domains/${DOMAIN_NAME}"
    local name addr port type admin_port wait_secs
    IFS=: read -r name addr port type <<< "${MACHINES[0]:-machine0:localhost:5556:ssl}"
    port="${port:-5556}"; type="${type:-ssl}"
    admin_port="$(read_prop "$CONF_FILE" "admin.port")"; admin_port="${admin_port:-7001}"
    wait_secs="${BLADE_START_WAIT:-480}"   # first prod-mode boot exceeds 240s

    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] write ${domain_dir}/servers/AdminServer/security/boot.properties${C_RESET}"
        log "${C_DIM}  [dry-run] nohup ${domain_dir}/bin/startNodeManager.sh  (unless :${port} already listens)${C_RESET}"
        log "${C_DIM}  [dry-run] nmStart(AdminServer) via misc/start-admin-nm.sh (NM ${port}/${type}); wait for console :${admin_port}${C_RESET}"
        return 0
    fi
    [ -d "$domain_dir" ] || die "Domain not found: ${domain_dir} — run the configure step first."

    if admin_listening "$addr" "$admin_port"; then
        ok "AdminServer already listening — console: http://${addr}:${admin_port}/console"
        return 0
    fi

    ensure_admin_pw

    local bootdir="${domain_dir}/servers/AdminServer/security"
    if [ ! -f "${bootdir}/boot.properties" ]; then
        mkdir -p "$bootdir"
        printf 'username=%s\npassword=%s\n' "$ADMIN_USER" "$ADMIN_PW" > "${bootdir}/boot.properties"
        chmod 600 "${bootdir}/boot.properties"
        ok "boot.properties written (encrypted by WebLogic on first start)"
    fi

    # NM binds the ListenAddress from nodemanager.properties (machine0's addr),
    # not localhost — probe and connect to THAT address.
    if listening "$addr" "$port"; then
        ok "NodeManager already listening on ${addr}:${port}"
    else
        info "Starting NodeManager (${addr}:${port} ${type}) …"
        mkdir -p "${domain_dir}/nodemanager"
        nohup "${domain_dir}/bin/startNodeManager.sh" >> "${domain_dir}/nodemanager/nodemanager.out" 2>&1 &
    fi

    # start-admin-nm.sh waits for the NM listener itself, then nmStart()s.
    local wlst_props; wlst_props="$(nm_wlst_props "$domain_dir")"
    info "Starting AdminServer through Node Manager …"
    MW_HOME="$ORACLE_HOME" DOMAIN_NAME="$DOMAIN_NAME" NM_HOST="$addr" \
    NM_PORT="$port" NM_TYPE="$type" NM_USER="$ADMIN_USER" NM_PASSWORD="$ADMIN_PW" \
    NM_ACTION="start" WLST_PROPERTIES="$wlst_props" "${SCRIPT_DIR}/misc/start-admin-nm.sh" \
        || die "AdminServer start failed — see ${domain_dir}/servers/AdminServer/logs/ and ${domain_dir}/nodemanager/nodemanager.out"

    info "Waiting for the console on :${admin_port} (up to ${wait_secs}s) …"
    local i=0
    until admin_listening "$addr" "$admin_port"; do
        i=$((i + 2))
        if [ "$i" -ge "$wait_secs" ]; then
            warn "Console not up after ${wait_secs}s — it may still be starting; check ${domain_dir}/servers/AdminServer/logs/AdminServer.log"
            return 0
        fi
        sleep 2
    done
    ok "AdminServer up — console: http://${addr}:${admin_port}/console  (user: ${ADMIN_USER})"

    if [ ${#MACHINES[@]} -gt 1 ]; then
        log "  Engine boxes: './install-occas.sh ${ENV_NAME} engines' ships everything and starts them ('all' does it next)."
    fi
}

# ----------------------------------------------------------------------------
# Step 4 — engines: ship binaries/JDK/certs/domain to each engine box, start it
# ----------------------------------------------------------------------------
# Engines are node-LOCAL in this layout (see sync-occas.sh: "engines don't
# depend on the shared FS at runtime") — nothing mounts the admin box's disks.
# So everything an engine needs goes over rsync to the SAME absolute paths
# (the domain config bakes them in): the OCCAS home (the domain lives inside
# it under user_projects/), the runtime JDK, and the env certs. Then the
# engine's NodeManager is started over ssh and its server through nmStart.
# Requires key-based ssh (BatchMode) to each engine; a box that isn't
# reachable is skipped with a warning — fix ssh and re-run, it resumes.
do_engines() {
    if [ ${#MACHINES[@]} -le 1 ]; then
        ok "No engine machines in the conf — nothing to do."
        return 0
    fi
    local domain_dir="${ORACLE_HOME}/user_projects/domains/${DOMAIN_NAME}"
    local cdir; cdir="$(certs_dir)"
    local sshu; sshu="$(read_prop "$CONF_FILE" "ssh.user")"; sshu="${sshu:-$(id -un)}"
    local jdk=""; [ -n "$JAVA_RUNTIME" ] && jdk="$(jdk_home_of "$JAVA_RUNTIME")"
    local wlst_props; wlst_props="$(nm_wlst_props "$domain_dir")"
    local fw_src; fw_src="$(trusted_source)"
    local admin_addr admin_port
    IFS=: read -r _ admin_addr _ _ <<< "${MACHINES[0]:-machine0:localhost:5556:ssl}"
    admin_port="$(read_prop "$CONF_FILE" "admin.port")"; admin_port="${admin_port:-7001}"
    local m name addr port type i=0 failed=""

    for m in "${MACHINES[@]:1}"; do
        i=$((i + 1))
        IFS=: read -r name addr port type <<< "$m"
        if [ "$DRY_RUN" = true ]; then
            log "${C_DIM}  [dry-run] ${name} (${addr}): ssh sudo install -d dirs; rsync ${ORACLE_HOME}, ${jdk:-<jdk>}, ${cdir}; start NM; nmStart(engine${i})${C_RESET}"
            continue
        fi
        info "Engine ${name} (${addr}) as ${sshu} …"
        if ! ssh -o BatchMode=yes -o ConnectTimeout=5 "${sshu}@${addr}" true 2>/dev/null; then
            warn "${name}: no key-based ssh to ${sshu}@${addr} — skipped. (ssh-copy-id ${sshu}@${addr}, then re-run './install-occas.sh ${ENV_NAME} engines')"
            failed="${failed} ${name}"; continue
        fi
        if ! ssh "${sshu}@${addr}" "sudo install -d -o ${sshu} '$(dirname "$ORACLE_HOME")' '${JAVA_DIR}' '$(dirname "$cdir")'" 2>/dev/null; then
            warn "${name}: could not create target dirs (passwordless sudo?) — skipped."
            failed="${failed} ${name}"; continue
        fi
        # Same firewalld opening prep did on the admin box (see trusted_source).
        if [ -n "$fw_src" ] && [ "$fw_src" != "none" ]; then
            ssh "${sshu}@${addr}" "command -v firewall-cmd >/dev/null 2>&1 && sudo firewall-cmd --state >/dev/null 2>&1 && { sudo firewall-cmd --permanent --zone=trusted --add-source='${fw_src}' >/dev/null; sudo firewall-cmd --reload >/dev/null; } || true" \
                || warn "${name}: firewalld opening failed — cluster ports may be blocked."
        fi
        info "  rsync OCCAS home + domain (first run moves a few GB) …"
        rsync -a \
              --exclude 'user_projects/domains/*/servers/*/logs/' \
              --exclude 'user_projects/domains/*/servers/*/tmp/' \
              --exclude 'user_projects/domains/*/servers/*/cache/' \
              --exclude 'user_projects/domains/*/nodemanager/*.log*' \
              --exclude 'user_projects/domains/*/nodemanager/*.pid' \
              "${ORACLE_HOME}/" "${sshu}@${addr}:${ORACLE_HOME}/" \
            || { warn "${name}: rsync of ${ORACLE_HOME} failed — skipped."; failed="${failed} ${name}"; continue; }
        if [ -n "$jdk" ]; then
            rsync -a "$jdk" "${sshu}@${addr}:${JAVA_DIR}/" \
                || { warn "${name}: rsync of ${jdk} failed — skipped."; failed="${failed} ${name}"; continue; }
        fi
        rsync -a "${cdir}/" "${sshu}@${addr}:${cdir}/" \
            || { warn "${name}: rsync of ${cdir} failed — skipped."; failed="${failed} ${name}"; continue; }

        # The synced nodemanager.properties carries the ADMIN box's
        # ListenAddress — repoint it at this machine's own address.
        ssh "${sshu}@${addr}" "sed -i 's/^ListenAddress=.*/ListenAddress=${addr}/' '${domain_dir}/nodemanager/nodemanager.properties'" \
            || { warn "${name}: could not set NM ListenAddress — skipped."; failed="${failed} ${name}"; continue; }

        if ssh "${sshu}@${addr}" "timeout 3 bash -c 'exec 3<>/dev/tcp/${addr}/${port}'" 2>/dev/null; then
            ok "  NodeManager already listening on ${name}:${port}"
        else
            info "  Starting NodeManager on ${name} …"
            ssh "${sshu}@${addr}" "mkdir -p '${domain_dir}/nodemanager'; nohup '${domain_dir}/bin/startNodeManager.sh' >> '${domain_dir}/nodemanager/nodemanager.out' 2>&1 & disown" \
                || { warn "${name}: NodeManager start failed."; failed="${failed} ${name}"; continue; }
        fi

        # Dynamic servers map 1:1 onto machine1..N in creation order → engine<i>.
        info "  Starting server engine${i} through ${name}'s Node Manager …"
        ensure_admin_pw
        if ! MW_HOME="$ORACLE_HOME" DOMAIN_NAME="$DOMAIN_NAME" ADMIN_SERVER="engine${i}" \
             NM_HOST="$addr" NM_PORT="$port" NM_TYPE="$type" NM_USER="$ADMIN_USER" \
             NM_PASSWORD="$ADMIN_PW" NM_ACTION="start" WLST_PROPERTIES="$wlst_props" \
             NM_ADMINURL="t3://${admin_addr}:${admin_port}" \
             "${SCRIPT_DIR}/misc/start-admin-nm.sh"; then
            warn "${name}: nmStart(engine${i}) failed — start it from the console once NM is up."
            failed="${failed} ${name}"
        fi
    done

    if [ -n "$failed" ]; then
        warn "Engines with issues:${failed} — fix and re-run './install-occas.sh ${ENV_NAME} engines' (it resumes)."
    else
        [ "$DRY_RUN" = true ] || ok "All engine boxes provisioned and started."
    fi
}

# ----------------------------------------------------------------------------
# Step 3 — secure: TLS on the EXISTING domain (offline WLST; run domain-stopped)
# ----------------------------------------------------------------------------
# Emits the Jython for one server-ish mbean: custom identity/trust keystores on
# the server, an enabled SSL child at the given port, and (tls.only) plaintext
# HTTP off. Args: <mbean-path> <name> <ssl-port> <has-sip-channel yes|no>
emit_tls_block() {
    local path="$1" name="$2" sslport="$3" has_sip="$4"
    cat <<PYBLOCK

# --- ${name}: keystores + SSL :${sslport} ---
cd('${path}')
set('KeyStores','CustomIdentityAndCustomTrust')
set('CustomIdentityKeyStoreFileName','${ID_STORE}')
set('CustomIdentityKeyStoreType','${ID_TYPE}')
set('CustomIdentityKeyStorePassPhraseEncrypted','${STORE_PW_TOKEN}')
set('CustomTrustKeyStoreFileName','${TRUST_STORE}')
set('CustomTrustKeyStoreType','${TRUST_TYPE}')
set('CustomTrustKeyStorePassPhraseEncrypted','${STORE_PW_TOKEN}')
try:
    create('${name}','SSL')
except:
    pass
cd('${path}/SSL/${name}')
set('Enabled','true')
set('ListenPort',${sslport})
set('ServerPrivateKeyAlias','${ID_ALIAS}')
set('ServerPrivateKeyPassPhraseEncrypted','${STORE_PW_TOKEN}')
PYBLOCK
    if [ "$TLS_ONLY" = "true" ]; then
        cat <<PYBLOCK
cd('${path}')
set('ListenPortEnabled','false')
PYBLOCK
        if [ "$has_sip" = "yes" ]; then
            cat <<PYBLOCK
# tls.only: drop the plaintext sip channel — sips stays
try:
    delete('sip','NetworkAccessPoint')
except:
    pass
PYBLOCK
        fi
    fi
}

do_secure() {
    local domain_dir="${ORACLE_HOME}/user_projects/domains/${DOMAIN_NAME}"
    local cdir admin_name admin_ssl engine_ssl tmpl_name

    ensure_certs

    cdir="$(certs_dir)"
    admin_name="$(read_prop "$CONF_FILE" "admin.server.name")";    admin_name="${admin_name:-AdminServer}"
    admin_ssl="$(read_prop "$CONF_FILE" "admin.ssl.port")";        admin_ssl="${admin_ssl:-7002}"
    engine_ssl="$(read_prop "$CONF_FILE" "ssl.listen.port")";      engine_ssl="${engine_ssl:-8002}"
    tmpl_name="$(read_prop "$CONF_FILE" "engine.template.name")";  tmpl_name="${tmpl_name:-BEA_ENGINE_TIER_CLUST-template}"
    TLS_ONLY="$(read_prop "$CONF_FILE" "tls.only")";               TLS_ONLY="${TLS_ONLY:-false}"
    ID_STORE="$(read_prop "$CONF_FILE" "identity.keystore")";      ID_STORE="${ID_STORE:-${cdir}/identity.p12}"; ID_STORE="${ID_STORE/#\~/$HOME}"
    ID_TYPE="$(read_prop "$CONF_FILE" "identity.keystore.type")";  ID_TYPE="${ID_TYPE:-PKCS12}"
    ID_ALIAS="$(read_prop "$CONF_FILE" "identity.alias")";         ID_ALIAS="${ID_ALIAS:-server}"
    TRUST_STORE="$(read_prop "$CONF_FILE" "trust.keystore")";      TRUST_STORE="${TRUST_STORE:-${cdir}/trust.p12}"; TRUST_STORE="${TRUST_STORE/#\~/$HOME}"
    TRUST_TYPE="$(read_prop "$CONF_FILE" "trust.keystore.type")";  TRUST_TYPE="${TRUST_TYPE:-PKCS12}"
    STORE_PW_TOKEN="__STORE_PW__"

    info "Secure domain '${DOMAIN_NAME}' — TLS on admin + engines"
    log  "  admin:    ${admin_name}  SSL :${admin_ssl}"
    log  "  engines:  ${tmpl_name} (+ static, if any)  SSL :${engine_ssl}"
    log  "  identity: ${ID_STORE} (${ID_TYPE}, alias '${ID_ALIAS}')"
    log  "  trust:    ${TRUST_STORE} (${TRUST_TYPE})"
    log  "  tls.only: ${TLS_ONLY}$([ "$TLS_ONLY" = "true" ] && echo '  — plaintext HTTP + sip channels OFF (HTTPS/SIPS/t3s only)')"

    # Generate the WLST script (password as a token; substituted at run time).
    local py
    py="readDomain('${domain_dir}')"
    py="${py}$(emit_tls_block "/Servers/${admin_name}" "${admin_name}" "${admin_ssl}" no)"
    py="${py}$(emit_tls_block "/ServerTemplates/${tmpl_name}" "${tmpl_name}" "${engine_ssl}" yes)"
    if [ -n "$STATIC_SRV" ]; then
        local sname
        IFS=: read -r sname _ <<< "$STATIC_SRV"
        py="${py}$(emit_tls_block "/Servers/${sname}" "${sname}" "${engine_ssl}" yes)"
    fi
    py="${py}
updateDomain()
closeDomain()
print('secure: domain updated')"

    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] generated WLST (password redacted):${C_RESET}"
        printf '%s\n' "$py" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] source setWLSEnv.sh; java weblogic.WLST secure-domain.py${C_RESET}"
        log "${C_DIM}  [dry-run] nodemanager.properties → custom identity keystore (replaces the DEMO cert)${C_RESET}"
        return 0
    fi

    [ -d "$domain_dir" ]   || die "Domain not found: ${domain_dir} — run the configure step first."
    [ -f "$ID_STORE" ]     || die "Identity keystore not found: ${ID_STORE} — run './certs.sh ${ENV_NAME} generate' (or import) first."
    [ -f "$TRUST_STORE" ]  || die "Trust keystore not found: ${TRUST_STORE}"
    local setwls="${ORACLE_HOME}/wlserver/server/bin/setWLSEnv.sh"
    [ -f "$setwls" ] || die "setWLSEnv.sh not found: ${setwls}"

    ensure_store_pw
    local pw="$STORE_PW"
    local work; work="$(mktemp -d /tmp/occas-secure.XXXXXX)"
    trap 'rm -rf "$work"' RETURN
    printf '%s\n' "${py//${STORE_PW_TOKEN}/$pw}" > "${work}/secure-domain.py"
    chmod 600 "${work}/secure-domain.py"

    (
        cd "$work"
        # Oracle's env scripts predate 'set -u' — see do_configure.
        export MW_HOME="$ORACLE_HOME"
        set +u
        # shellcheck disable=SC1090
        . "$setwls" >/dev/null
        java weblogic.WLST secure-domain.py
    )
    secure_nodemanager
    ok "Domain '${DOMAIN_NAME}' secured — restart NodeManagers + servers."
    if [ "$TLS_ONLY" = "true" ]; then
        warn "tls.only: plaintext HTTP and sip are OFF. Use https://…:${admin_ssl} for the console, t3s in deploy confs, and SIPS/TLS toward the SBC."
    fi
}

# NodeManager's SSL listener has its OWN keystore config (nodemanager.properties)
# — the domain-level secure step doesn't reach it, and its default is Oracle's
# DEMO certificate, whose private key ships in every WebLogic download. Point it
# at the env identity keystore; NM encrypts the passphrases in-place on its
# next start. (Pre-creating the file works — NM merges its defaults in.)
secure_nodemanager() {
    local nmdir="${ORACLE_HOME}/user_projects/domains/${DOMAIN_NAME}/nodemanager"
    local props="${nmdir}/nodemanager.properties"
    local kv key
    mkdir -p "$nmdir"
    touch "$props"; chmod 600 "$props"
    for kv in "SecureListener=true" \
              "KeyStores=CustomIdentityAndCustomTrust" \
              "CustomIdentityKeyStoreFileName=${ID_STORE}" \
              "CustomIdentityKeyStoreType=${ID_TYPE}" \
              "CustomIdentityKeyStorePassPhrase=${STORE_PW}" \
              "CustomIdentityAlias=${ID_ALIAS}" \
              "CustomIdentityPrivateKeyPassPhrase=${STORE_PW}"; do
        key="${kv%%=*}"
        if grep -q "^${key}=" "$props" 2>/dev/null; then
            sed -i.bak "s|^${key}=.*|${kv//&/\\&}|" "$props" && rm -f "${props}.bak"
        else
            printf '%s\n' "$kv" >> "$props"
        fi
    done
    ok "NodeManager SSL → ${ID_STORE} (demo cert replaced; NM encrypts the passphrases on next start)"
}

# ----------------------------------------------------------------------------
# uninstall — tear it all down, keep what's expensive (repeatable E2E testing)
# ----------------------------------------------------------------------------
# Removes: domain processes (this box + engines), the domain, the OCCAS
# product, the inventory, the env certs; engines lose their synced copies.
# Keeps: the eDelivery media (re-downloading needs a browser trip), the JDKs,
# the env conf/secret/urls, and prep's users/dirs/profile — so the next
# './install-occas.sh <env>' runs the whole install again unattended.
# The pkill pattern uses the [d]omains trick so it can never match itself.
do_uninstall() {
    local domain_dir="${ORACLE_HOME}/user_projects/domains/${DOMAIN_NAME}"
    local cdir; cdir="$(certs_dir)"
    local sshu; sshu="$(read_prop "$CONF_FILE" "ssh.user")"; sshu="${sshu:-$(id -un)}"
    local m name addr port type

    # The media must survive uninstall (re-downloading needs an eDelivery
    # browser trip) — refuse if the conf parks it inside oracle.home.
    case "$(dirname "$INSTALLER_JAR")" in
        "$ORACLE_HOME"|"$ORACLE_HOME"/*)
            die "installer.jar lives INSIDE oracle.home (${INSTALLER_JAR}) — uninstall would delete the downloaded media. Move it (download.dir / installer.jar in ${CONF_FILE}) first." ;;
    esac

    warn "UNINSTALL '${ENV_NAME}' — this box and $(( ${#MACHINES[@]} > 0 ? ${#MACHINES[@]} - 1 : 0 )) engine box(es):"
    log  "  stops  : all processes of domain '${DOMAIN_NAME}'"
    log  "  deletes: ${ORACLE_HOME} (product + domain), ${INV_LOC}, ${cdir}"
    log  "  keeps  : media in $(dirname "$INSTALLER_JAR"), JDKs in ${JAVA_DIR}, the env conf/secret, prep's users+dirs"
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] nothing removed${C_RESET}"
        return 0
    fi
    if [ "$ASSUME_YES" != true ]; then
        [ -t 0 ] || die "Refusing without confirmation — run interactively or pass --yes."
        local confirm; ask confirm "Type the environment name to confirm" ""
        [ "$confirm" = "$ENV_NAME" ] || die "Not confirmed — nothing removed."
    fi

    if [ ${#MACHINES[@]} -gt 1 ]; then
        for m in "${MACHINES[@]:1}"; do
            IFS=: read -r name addr port type <<< "$m"
            if ssh -o BatchMode=yes -o ConnectTimeout=5 "${sshu}@${addr}" true 2>/dev/null; then
                info "Engine ${name} (${addr}): stopping + removing synced copies …"
                if ssh "${sshu}@${addr}" "pkill -f '[d]omains/${DOMAIN_NAME}' 2>/dev/null; sleep 2; pkill -9 -f '[d]omains/${DOMAIN_NAME}' 2>/dev/null; rm -rf '${ORACLE_HOME}' '${cdir}'; true"; then
                    ok "  ${name} cleaned"
                else
                    warn "  ${name}: cleanup incomplete — check by hand."
                fi
            else
                warn "Engine ${name} (${addr}): unreachable — clean it by hand or re-run uninstall when ssh works."
            fi
        done
    fi

    info "This box: stopping domain '${DOMAIN_NAME}' processes …"
    pkill -f "[d]omains/${DOMAIN_NAME}" 2>/dev/null || true
    sleep 3
    pkill -9 -f "[d]omains/${DOMAIN_NAME}" 2>/dev/null || true

    # Empty rather than delete ORACLE_HOME/INV_LOC — prep created the dirs
    # themselves (their root-owned parents make them non-recreatable as us).
    info "Removing product, domain, inventory, certs …"
    rm -rf "${ORACLE_HOME:?}"/* "${ORACLE_HOME}"/.[!.]* 2>/dev/null || true
    rm -rf "${INV_LOC:?}"/* "${INV_LOC}"/.[!.]* 2>/dev/null || true
    rm -rf "$cdir"
    ok "Uninstalled '${ENV_NAME}'. Re-run:  ./install-occas.sh ${ENV_NAME}   (media + JDKs kept — no token, no interview)"
}

# --- Header + dispatch ---
log "${C_BOLD}OCCAS install${C_RESET}"
log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
log "  oracle.home:  ${ORACLE_HOME}"
log "  step:         ${STEP}"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

case "$STEP" in
    prep)      do_prep ;;
    download)  do_download; do_java ;;
    install)   do_install ;;
    configure) do_configure ;;
    secure)    do_secure ;;
    start)     do_start ;;
    engines)   do_engines ;;
    uninstall) do_uninstall ;;
    all)       do_install; log ""; do_configure; log ""; do_secure; log ""; do_start; log ""; do_engines ;;
esac

log ""
ok "${STEP}: done"
