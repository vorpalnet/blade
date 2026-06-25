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
#   ./install-occas.sh <env> <step> [--dry-run]
#     <env>   name → build-profiles/occas/<name>.conf (+ <name>.secret), or a path
#     <step>  install | configure | all
#
# Examples:
#   ./install-occas.sh oci all --dry-run     # preview both steps
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
        -h|--help) sed -n '2,55p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --dry-run) DRY_RUN=true ;;
        -*) die "Unknown option: $1" ;;
        *)  POSITIONAL+=("$1") ;;
    esac
    shift
done
[ ${#POSITIONAL[@]} -ge 1 ] || die "Usage: ./install-occas.sh <env> <install|configure|all> [--dry-run]"
ENV_ARG="${POSITIONAL[0]}"
STEP="${POSITIONAL[1]:-}"
case "$STEP" in install|configure|all) ;; "") die "Missing step (install|configure|all)." ;; *) die "Unknown step: ${STEP}" ;; esac

# --- Resolve conf + secret ---
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"; ENV_NAME="$(basename "${ENV_ARG%.conf}")"; SECRET_FILE="${ENV_ARG%.conf}.secret"
else
    ENV_NAME="$ENV_ARG"; CONF_FILE="${OCCAS_DIR}/${ENV_NAME}.conf"; SECRET_FILE="${OCCAS_DIR}/${ENV_NAME}.secret"
fi
[ -f "$CONF_FILE" ] || die "Conf not found: ${CONF_FILE}"

read_prop() {
    local file="$1" key="$2"
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

ORACLE_HOME=$(read_prop  "$CONF_FILE" "oracle.home")
INSTALLER_JAR=$(read_prop "$CONF_FILE" "installer.jar")
# The installer location varies per box, so allow an env override without editing
# the conf:  BLADE_OCCAS_INSTALLER=/path/to/occas_generic.jar ./install-occas.sh ...
INSTALLER_JAR="${BLADE_OCCAS_INSTALLER:-$INSTALLER_JAR}"
INV_LOC=$(read_prop      "$CONF_FILE" "inventory.loc")
INV_GROUP=$(read_prop    "$CONF_FILE" "inventory.group"); INV_GROUP="${INV_GROUP:-oinstall}"
INSTALL_TYPE=$(read_prop "$CONF_FILE" "install.type");    INSTALL_TYPE="${INSTALL_TYPE:-Complete with Examples}"

DOMAIN_NAME=$(read_prop  "$CONF_FILE" "domain.name")
START_MODE=$(read_prop   "$CONF_FILE" "server.start.mode"); START_MODE="${START_MODE:-prod}"
ADMIN_USER=$(read_prop   "$CONF_FILE" "admin.username");    ADMIN_USER="${ADMIN_USER:-weblogic}"
SRV_PREFIX=$(read_prop   "$CONF_FILE" "server.name.prefix")
MATCH_EXPR=$(read_prop   "$CONF_FILE" "machine.match.expression")
DYN_COUNT=$(read_prop    "$CONF_FILE" "dynamic.server.count")
MAX_SIZE=$(read_prop     "$CONF_FILE" "max.dynamic.cluster.size")
STATIC_SRV=$(read_prop   "$CONF_FILE" "static.server")

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

# ----------------------------------------------------------------------------
# Step 1 — silent product install
# ----------------------------------------------------------------------------
do_install() {
    [ -n "$INSTALLER_JAR" ] || die "${CONF_FILE}: missing installer.jar"
    [ -n "$INV_LOC" ]       || die "${CONF_FILE}: missing inventory.loc"
    info "Silent install → ${ORACLE_HOME}  (installer: ${INSTALLER_JAR})"

    local rsp inv
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
        log "${C_DIM}  [dry-run] java -jar ${INSTALLER_JAR} -silent -responseFile <rsp> -invPtrLoc <oraInst.loc> -ignoreSysPrereqs${C_RESET}"
        rm -f "$rsp" "$inv"; return 0
    fi
    [ -f "$INSTALLER_JAR" ] || { rm -f "$rsp" "$inv"; die "installer.jar not found: ${INSTALLER_JAR}"; }
    java -jar "$INSTALLER_JAR" -silent -responseFile "$rsp" -invPtrLoc "$inv" -ignoreSysPrereqs
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

# --- Header + dispatch ---
log "${C_BOLD}OCCAS install${C_RESET}"
log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
log "  oracle.home:  ${ORACLE_HOME}"
log "  step:         ${STEP}"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

case "$STEP" in
    install)   do_install ;;
    configure) do_configure ;;
    all)       do_install; log ""; do_configure ;;
esac

log ""
ok "${STEP}: done"
