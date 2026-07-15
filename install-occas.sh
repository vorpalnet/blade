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
#     no args → just does the next thing: init if there's no conf, prep
#               guidance if the box isn't set up yet, all if OCCAS isn't
#               installed, configure if the domain is missing, and stops
#               (saying so) when there's nothing left to do
#     <env>   name → build-profiles/occas/<name>.conf (+ <name>.secret), or a path
#     <step>  init | prep | download | install | configure | secure | all
#
#   init       interactively interview you and WRITE the env conf (+ optional secret)
#   prep       one-time root setup (sudo): creates install.user ('oracle') +
#              inventory.group ('oinstall'), the oracle.home / installer /
#              inventory / java.dir directories (oracle:oinstall, mode 2775),
#              and adds you to oinstall so every other step runs without sudo.
#              Log out/in afterwards to pick up the group membership.
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
#   all        install then configure (install auto-downloads when needed)
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
#   * A new physical host → add a machine.N line in the conf (its NodeManager),
#     make sure its name matches machine.match.expression, bump
#     dynamic.server.count, then re-run configure.
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
ENV_ARG=""; STEP=""; DRY_RUN=false
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,87p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --dry-run) DRY_RUN=true ;;
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

# --- No <env> arg → the only conf wins; otherwise ask ---
if [ -z "$ENV_ARG" ]; then
    CONFS=()
    for f in "$OCCAS_DIR"/*.conf; do [ -e "$f" ] && CONFS+=("$(basename "${f%.conf}")"); done
    if [ ${#CONFS[@]} -eq 1 ]; then
        ENV_ARG="${CONFS[0]}"
    elif [ ${#CONFS[@]} -eq 0 ]; then
        [ -t 0 ] || die "Usage: ./install-occas.sh <env> <init|download|install|configure|secure|all> [--dry-run]"
        warn "No env confs in build-profiles/occas/ yet — starting the init interview."
        ask ENV_ARG "Environment name" "$(hostname -s)"
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
if [ -z "$STEP" ]; then
    if [ ! -f "$CONF_FILE" ]; then
        STEP="init"
        info "No conf for '${ENV_NAME}' yet — running init."
    else
        _OH="$(read_prop "$CONF_FILE" "oracle.home")"
        _DN="$(read_prop "$CONF_FILE" "domain.name")"
        if [ ! -d "${_OH}/wlserver" ]; then
            if [ "$(id -u)" -eq 0 ]; then
                STEP="prep"
            elif [ -w "$_OH" ] || [ -w "$(dirname "$_OH")" ]; then
                STEP="all"
            else
                info "This box isn't prepped yet (no write access to ${_OH})."
                log  "  Run:   sudo ./install-occas.sh ${ENV_NAME} prep"
                log  "  (creates the oracle user + oinstall group and the install/inventory/java"
                log  "   dirs, and adds you to oinstall — then log out/in and re-run this)"
                exit 1
            fi
        elif [ -n "$_DN" ] && [ ! -d "${_OH}/user_projects/domains/${_DN}" ]; then
            STEP="configure"
        else
            ok "Nothing to do: OCCAS is at ${_OH} and domain '${_DN}' exists."
            log "  Rebuild the domain (OVERWRITES it):  ./install-occas.sh ${ENV_NAME} configure"
            log "  Wire TLS into it:                    ./install-occas.sh ${ENV_NAME} secure"
            exit 0
        fi
    fi
fi
case "$STEP" in init|prep|download|install|configure|secure|all) ;; *) die "Unknown step: ${STEP}" ;; esac

# Interactive builder for build-profiles/occas/<env>.conf (+ optional secret).
do_init() {
    log "${C_BOLD}OCCAS env builder${C_RESET} → ${CONF_FILE}"
    [ -f "$CONF_FILE" ] && warn "${CONF_FILE} exists — its values are offered as defaults; you'll confirm overwrite at the end."
    log "  Shared-filesystem note: 'install' runs ONCE (every node mounts the same"
    log "  ORACLE_HOME); the domain is created once; only NodeManager runs per host."
    log ""

    local oracle_home installer inv_loc inv_grp inst_type
    log "Step 1 — silent product install:"
    defask oracle_home "ORACLE_HOME (shared install dir)"        "/opt/oracle/occas/8.3"                       "oracle.home"
    defask installer   "Installer jar (occas_generic.jar path)"  "/home/oracle/install/occas-8.3/occas_generic.jar" "installer.jar"
    defask inv_loc     "Oracle inventory location"               "/home/oracle/oraInventory"                   "inventory.loc"
    defask inv_grp     "Inventory group"                         "oinstall"                                    "inventory.group"
    defask inst_type   "Install type"                            "Complete with Examples"                      "install.type"

    local domain_name start_mode admin_user srv_prefix match_expr dyn_count max_size
    log ""
    log "Step 2 — dynamic-cluster domain:"
    defask domain_name "Domain name (OverwriteDomain=true CLOBBERS an existing one)" "replicated" "domain.name"
    defask start_mode  "Server start mode (prod|dev)"            "prod"     "server.start.mode"
    defask admin_user  "Admin username"                          "weblogic" "admin.username"
    defask srv_prefix  "Dynamic server name prefix"              "engine"   "server.name.prefix"
    defask match_expr  "Machine name match expression"           "engine*"  "machine.match.expression"
    defask dyn_count   "Dynamic server count (now)"              "2"        "dynamic.server.count"
    defask max_size    "Max dynamic cluster size (ceiling)"      "8"        "max.dynamic.cluster.size"

    log ""
    log "Machines (NodeManager hosts) — enter a blank name to finish:"
    local machines=() n=1 mname maddr mport mtype
    while true; do
        ask mname "machine ${n} name (blank = done)" ""
        [ -z "$mname" ] && break
        ask maddr "${mname} NM listen address" ""
        ask mport "${mname} NM listen port" "5556"
        ask mtype "${mname} NM type (ssl|plain)" "ssl"
        machines+=("${mname}:${maddr}:${mport}:${mtype}")
        n=$((n + 1))
    done
    [ ${#machines[@]} -ge 1 ] || die "At least one machine is required."

    local yn static_line="" s_def
    log ""
    ask yn "Add a static test engine on the admin box? (y/N)" "N"
    if [[ "$yn" =~ ^[Yy] ]]; then
        s_def="$(read_prop "$CONF_FILE" "static.server")"; [ -z "$s_def" ] && s_def="engine0:admin:8001:5060:5061"
        ask static_line "static.server (name:machine:listen:sip:sips)" "$s_def"
    fi

    log ""
    if [ -f "$CONF_FILE" ]; then
        ask yn "Overwrite ${CONF_FILE}? (y/N)" "N"
        [[ "$yn" =~ ^[Yy] ]] || die "Aborted — conf not written."
    fi
    mkdir -p "$(dirname "$CONF_FILE")"
    {
        echo "# OCCAS silent install + dynamic-cluster domain — built by 'install-occas.sh ${ENV_NAME} init'."
        echo "# Admin password lives in $(basename "$SECRET_FILE") (gitignored), not here."
        echo "# NOTE: keep comments on their OWN lines — trailing '# ...' becomes part of the value."
        echo ""
        echo "# --- Step 1: silent product install (runs once; ORACLE_HOME is shared) ---"
        echo "oracle.home=${oracle_home}"
        echo "installer.jar=${installer}"
        echo "inventory.loc=${inv_loc}"
        echo "inventory.group=${inv_grp}"
        echo "install.type=${inst_type}"
        echo ""
        echo "# --- Step 2: dynamic-cluster domain ---"
        echo "domain.name=${domain_name}"
        echo "server.start.mode=${start_mode}"
        echo "admin.username=${admin_user}"
        echo "server.name.prefix=${srv_prefix}"
        echo "machine.match.expression=${match_expr}"
        echo "dynamic.server.count=${dyn_count}"
        echo "max.dynamic.cluster.size=${max_size}"
        echo ""
        echo "# --- Machines + NodeManagers (name:nmAddr:nmPort:nmType) ---"
        local idx=1 m
        for m in "${machines[@]}"; do echo "machine.${idx}=${m}"; idx=$((idx + 1)); done
        if [ -n "$static_line" ]; then
            echo ""
            echo "# --- Optional static test engine on the admin box (never SBC-targeted) ---"
            echo "static.server=${static_line}"
        fi
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

    log ""
    ok "Done. Preview the run:  ./install-occas.sh ${ENV_NAME} all --dry-run"
}

if [ "$STEP" = "init" ]; then do_init; exit 0; fi

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

# --- Password (configure only): env > secret > prompt ---
get_admin_pw() {
    local v="${BLADE_WLS_PASSWORD:-}"
    [ -z "$v" ] && [ -f "$SECRET_FILE" ] && v=$(read_prop "$SECRET_FILE" "admin.password")
    if [ -z "$v" ] && [ "$DRY_RUN" = false ]; then
        read -rs -p "Admin (${ADMIN_USER}) password for the new domain: " v; echo
        [ -n "$v" ] || die "No password provided."
    fi
    printf '%s' "$v"
}

# --- Keystore password (secure only): env > secret > prompt — certs.sh's convention ---
get_store_pw() {
    local v="${BLADE_STORE_PASSWORD:-}"
    [ -z "$v" ] && [ -f "$SECRET_FILE" ] && v=$(read_prop "$SECRET_FILE" "store.password")
    if [ -z "$v" ] && [ "$DRY_RUN" = false ]; then
        read -rs -p "Keystore password (stores + keys, from certs.sh): " v; echo
        [ -n "$v" ] || die "No password provided."
    fi
    printf '%s' "$v"
}

# ----------------------------------------------------------------------------
# Step -1 — prep: one-time root setup (user, group, directories)
# ----------------------------------------------------------------------------
# Oracle-convention layout: install.user ('oracle') owns the product dirs;
# inventory.group ('oinstall') is shared with the admin user (you) so every
# later step runs WITHOUT sudo. Idempotent — safe to re-run.
do_prep() {
    local admin_user="${SUDO_USER:-}" d
    local dirs=("$ORACLE_HOME" "$INV_LOC" "$JAVA_DIR")
    [ -n "$INSTALLER_JAR" ] && dirs+=("$(dirname "$INSTALLER_JAR")")
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] group ${INV_GROUP}; user ${INSTALL_USER} (-m -g ${INV_GROUP})${C_RESET}"
        for d in "${dirs[@]}"; do
            log "${C_DIM}  [dry-run] mkdir -p ${d}; chown ${INSTALL_USER}:${INV_GROUP}; chmod 2775${C_RESET}"
        done
        [ -n "$admin_user" ] && log "${C_DIM}  [dry-run] usermod -aG ${INV_GROUP} ${admin_user}${C_RESET}"
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
        chown "${INSTALL_USER}:${INV_GROUP}" "$d"
        chmod 2775 "$d"
        ok "${d}  → ${INSTALL_USER}:${INV_GROUP}, group-writable (setgid)"
    done
    if [ -n "$admin_user" ] && [ "$admin_user" != "$INSTALL_USER" ]; then
        usermod -aG "$INV_GROUP" "$admin_user"
        ok "${admin_user} added to ${INV_GROUP}"
        warn "Group membership needs a fresh login: log out/in (or 'newgrp ${INV_GROUP}'), then run  ./install-occas.sh ${ENV_NAME}"
    fi
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
do_download() {
    if [ -f "$INSTALLER_JAR" ]; then
        ok "Installer already present: ${INSTALLER_JAR} — skipping download."
        return 0
    fi
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
    else
        local found; found="$(find "$DL_DIR" -maxdepth 3 -name occas_generic.jar 2>/dev/null | head -1)"
        if [ -n "$found" ]; then
            INSTALLER_JAR="$found"
            ok "Installer ready: ${INSTALLER_JAR}"
            log "  (not where the conf's installer.jar points — this run uses the downloaded one)"
        else
            warn "No occas_generic.jar in the downloaded media — check what ${URLS_FILE} points at."
        fi
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
    local ver="$1" label="$2" existing os_arch tarball url want got
    [ -n "$ver" ] || return 0
    existing="$(jdk_home_of "$ver")"
    if [ -n "$existing" ]; then
        ok "JDK ${ver} (${label}): ${existing}"
        return 0
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
            || die "No write access to ${d} — run:  sudo ./install-occas.sh ${ENV_NAME} prep  (then log out/in for the group)"
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
    local pw; pw="$(get_admin_pw)"

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

    # Run Oracle's offline WLST from the workdir.
    (
        cd "$work"
        # shellcheck disable=SC1090
        . "$setwls" >/dev/null
        export BEA_HOME="$ORACLE_HOME"          # the .py uses BEA_HOME for the domain dir
        java weblogic.WLST occas-replicated-dynamiccluster.py
    )
    ok "Domain '${DOMAIN_NAME}' written under ${ORACLE_HOME}/user_projects/domains/"
    warn "Start the NodeManager on each machine, then start the AdminServer (see misc/start-admin-nm.sh)."
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
    local certs_dir admin_name admin_ssl engine_ssl tmpl_name

    certs_dir="$(read_prop "$CONF_FILE" "certs.dir")"; certs_dir="${certs_dir/#\~/$HOME}"
    [ -z "$certs_dir" ] && certs_dir="${HOME}/.blade/certs/${ENV_NAME}"
    admin_name="$(read_prop "$CONF_FILE" "admin.server.name")";    admin_name="${admin_name:-AdminServer}"
    admin_ssl="$(read_prop "$CONF_FILE" "admin.ssl.port")";        admin_ssl="${admin_ssl:-7002}"
    engine_ssl="$(read_prop "$CONF_FILE" "ssl.listen.port")";      engine_ssl="${engine_ssl:-8002}"
    tmpl_name="$(read_prop "$CONF_FILE" "engine.template.name")";  tmpl_name="${tmpl_name:-BEA_ENGINE_TIER_CLUST-template}"
    TLS_ONLY="$(read_prop "$CONF_FILE" "tls.only")";               TLS_ONLY="${TLS_ONLY:-false}"
    ID_STORE="$(read_prop "$CONF_FILE" "identity.keystore")";      ID_STORE="${ID_STORE:-${certs_dir}/identity.p12}"; ID_STORE="${ID_STORE/#\~/$HOME}"
    ID_TYPE="$(read_prop "$CONF_FILE" "identity.keystore.type")";  ID_TYPE="${ID_TYPE:-PKCS12}"
    ID_ALIAS="$(read_prop "$CONF_FILE" "identity.alias")";         ID_ALIAS="${ID_ALIAS:-server}"
    TRUST_STORE="$(read_prop "$CONF_FILE" "trust.keystore")";      TRUST_STORE="${TRUST_STORE:-${certs_dir}/trust.p12}"; TRUST_STORE="${TRUST_STORE/#\~/$HOME}"
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
        return 0
    fi

    [ -d "$domain_dir" ]   || die "Domain not found: ${domain_dir} — run the configure step first."
    [ -f "$ID_STORE" ]     || die "Identity keystore not found: ${ID_STORE} — run './certs.sh ${ENV_NAME} generate' (or import) first."
    [ -f "$TRUST_STORE" ]  || die "Trust keystore not found: ${TRUST_STORE}"
    local setwls="${ORACLE_HOME}/wlserver/server/bin/setWLSEnv.sh"
    [ -f "$setwls" ] || die "setWLSEnv.sh not found: ${setwls}"

    local pw; pw="$(get_store_pw)"
    local work; work="$(mktemp -d /tmp/occas-secure.XXXXXX)"
    trap 'rm -rf "$work"' RETURN
    printf '%s\n' "${py//${STORE_PW_TOKEN}/$pw}" > "${work}/secure-domain.py"
    chmod 600 "${work}/secure-domain.py"

    (
        cd "$work"
        # shellcheck disable=SC1090
        . "$setwls" >/dev/null
        java weblogic.WLST secure-domain.py
    )
    ok "Domain '${DOMAIN_NAME}' secured — restart NodeManagers + servers."
    if [ "$TLS_ONLY" = "true" ]; then
        warn "tls.only: plaintext HTTP and sip are OFF. Use https://…:${admin_ssl} for the console, t3s in deploy confs, and SIPS/TLS toward the SBC."
    fi
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
    all)       do_install; log ""; do_configure ;;
esac

log ""
ok "${STEP}: done"
