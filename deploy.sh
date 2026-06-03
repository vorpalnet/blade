#!/usr/bin/env bash
# ============================================================================
# deploy.sh - Profile-driven deployment wrapper for BLADE
#
# Usage:
#   ./deploy.sh <env> <subdir> <target> [action] [--build VER] [--dry-run]
#   ./deploy.sh <env> shared                     [action] [--build VER] [--dry-run]
#   ./deploy.sh <env> fsmar                      [action] [--build VER] [--dry-run]
#
# Subdirs map directly to dist/<ver>/<subdir>/:
#   admin        Admin apps + javadoc — typically deploy to AdminServer
#   services     SIP services + test apps — typically deploy to your cluster
#   shared       Special: the WebLogic shared library (targets read from conf
#                wls.targets.both — needs both AdminServer + cluster)
#   fsmar        Special: copies FSMAR jars to <approuter.dir> on disk
#
# Target is the WebLogic deployment target name (server or cluster):
#   AdminServer                  (admin tier in most setups)
#   BEA_ENGINE_TIER_CLUST        (your SIP cluster — name varies per install)
# Required for `admin` and `services`; ignored for `shared` and `fsmar`.
#
# Environments:
#   build-profiles/deploy/<env>.conf        — connection + paths (committed)
#   build-profiles/deploy/<env>.secret      — wls.password only (gitignored)
#
# Actions:
#   deploy       (default) push artifacts to the target
#   undeploy     tear down the matching apps from the target
#   status       query WebLogic for deployment state
#
# Options:
#   --build VER  Pin to dist/<VER>/ instead of the newest build directory
#   --dry-run    Print what would happen; run nothing
#
# Password priority (highest wins):
#   1. BLADE_WLS_PASSWORD environment variable
#   2. <env>.secret file (wls.password=...)
#   3. Interactive prompt (read -s), with offer to save
#
# Examples:
#   ./deploy.sh prod admin AdminServer
#   ./deploy.sh prod services BEA_ENGINE_TIER_CLUST
#   ./deploy.sh prod admin AdminServer undeploy
#   ./deploy.sh prod services BEA_ENGINE_TIER_CLUST --build 2.9.5-320
#   ./deploy.sh prod shared
#   ./deploy.sh prod fsmar
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
DEPLOY_DIR="${PROFILES_DIR}/deploy"
WLS_PLUGIN="com.oracle.weblogic:weblogic-maven-plugin:14.1.1"

# --- Colors (disabled if NO_COLOR set or not a tty) ---
if [ -z "${NO_COLOR:-}" ] && [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
    C_BLUE=$'\033[34m'; C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
    C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_DIM=""; C_BOLD=""; C_RESET=""
fi

log()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s✓%s %s\n'   "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s⚠%s %s\n'   "$C_YELLOW" "$C_RESET" "$*"; }
err()  { printf '%s✗%s %s\n'   "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }

show_usage() {
    sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

# --- Parse args ---
# Positional: <env> <subdir> [target] [action]
# Special subdirs (shared, fsmar) skip <target>; admin/services require it.
ENV_NAME=""
SUBDIR=""
TARGET=""
ACTION="deploy"
BUILD_VER=""
DRY_RUN=false

POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) show_usage 0 ;;
        --build)   shift; BUILD_VER="${1:-}"; [ -n "$BUILD_VER" ] || die "--build requires a version argument" ;;
        --build=*) BUILD_VER="${1#--build=}" ;;
        --dry-run) DRY_RUN=true ;;
        -*)        die "Unknown option: $1" ;;
        *)         POSITIONAL+=("$1") ;;
    esac
    shift
done

[ ${#POSITIONAL[@]} -ge 1 ] || { err "Environment name required."; show_usage 1; }
ENV_NAME="${POSITIONAL[0]}"
SUBDIR="${POSITIONAL[1]:-}"

# undeploy/status can show up in different positions depending on subdir:
#   admin AdminServer undeploy   → POSITIONAL = (env, admin, AdminServer, undeploy)
#   shared undeploy              → POSITIONAL = (env, shared, undeploy)
# Detect the action token wherever it lands and remove it from the positional list.
remaining=()
for i in "${!POSITIONAL[@]}"; do
    [ "$i" -eq 0 ] && continue
    case "${POSITIONAL[$i]}" in
        deploy|undeploy|status) ACTION="${POSITIONAL[$i]}" ;;
        *) remaining+=("${POSITIONAL[$i]}") ;;
    esac
done

SUBDIR="${remaining[0]:-}"
TARGET="${remaining[1]:-}"

[ -n "$SUBDIR" ] || { err "Subdir required: admin | services | shared | fsmar"; show_usage 1; }

case "$SUBDIR" in
    admin|services)
        [ -n "$TARGET" ] || die "Target required for '${SUBDIR}'. e.g. ./deploy.sh ${ENV_NAME} ${SUBDIR} AdminServer"
        ;;
    shared|fsmar)
        [ -z "$TARGET" ] || die "'${SUBDIR}' does not take a target argument (got: ${TARGET})"
        ;;
    *)
        die "Unknown subdir: ${SUBDIR}. Use admin | services | shared | fsmar"
        ;;
esac

CONF_FILE="${DEPLOY_DIR}/${ENV_NAME}.conf"
SECRET_FILE="${DEPLOY_DIR}/${ENV_NAME}.secret"

if [ ! -f "$CONF_FILE" ]; then
    err "Deploy profile not found: ${CONF_FILE}"
    if [ -d "$DEPLOY_DIR" ]; then
        log ""
        log "Available environments:"
        for f in "$DEPLOY_DIR"/*.conf; do
            [ -f "$f" ] && log "  $(basename "${f%.conf}")"
        done
    fi
    exit 1
fi

# --- Load non-secret properties ---
read_prop() {
    local file="$1" key="$2"
    grep "^${key}=" "$file" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

BUILD_PROFILE=$(read_prop  "$CONF_FILE" "build.profile")
WLS_ADMINURL=$(read_prop   "$CONF_FILE" "wls.adminurl")
WLS_USER=$(read_prop       "$CONF_FILE" "wls.user")
WLS_TGT_BOTH=$(read_prop   "$CONF_FILE" "wls.targets.both")
SSH_HOST=$(read_prop       "$CONF_FILE" "ssh.host")
SSH_USER=$(read_prop       "$CONF_FILE" "ssh.user")
APPROUTER_DIR=$(read_prop  "$CONF_FILE" "approuter.dir")

[ -n "$BUILD_PROFILE" ] || die "${CONF_FILE}: missing build.profile"
[ -n "$WLS_ADMINURL" ]  || die "${CONF_FILE}: missing wls.adminurl"
[ -n "$WLS_USER" ]      || die "${CONF_FILE}: missing wls.user"

# wls.targets.both is only needed when deploying the shared library.
if [ "$SUBDIR" = "shared" ]; then
    [ -n "$WLS_TGT_BOTH" ] || die "${CONF_FILE}: missing wls.targets.both (required for 'shared')"
fi
# approuter.dir is only needed for the fsmar subdir.
if [ "$SUBDIR" = "fsmar" ]; then
    [ -n "$APPROUTER_DIR" ] || die "${CONF_FILE}: missing approuter.dir (required for 'fsmar')"
fi

# --- Secret safeguards ---
check_secret_safety() {
    local f="$1"
    if ! git -C "$SCRIPT_DIR" check-ignore -q "$f" 2>/dev/null; then
        err "REFUSING: ${f} is not gitignored. This file contains passwords."
        err "Fix .gitignore or build-profiles/deploy/.gitignore before proceeding."
        exit 1
    fi
    if [ -f "$f" ]; then
        local mode
        mode=$(stat -f '%Lp' "$f" 2>/dev/null || stat -c '%a' "$f" 2>/dev/null || echo "")
        if [ -n "$mode" ] && [ "$mode" != "600" ]; then
            warn "${f} is mode ${mode}; recommended: chmod 600 ${f}"
        fi
    fi
}
check_secret_safety "$SECRET_FILE"

# --- Resolve password (env var > secret file > interactive prompt) ---
# fsmar doesn't talk to WebLogic, so it skips password resolution entirely.
WLS_PASSWORD=""
if [ "$SUBDIR" != "fsmar" ]; then
    if [ -n "${BLADE_WLS_PASSWORD:-}" ]; then
        WLS_PASSWORD="$BLADE_WLS_PASSWORD"
    elif [ -f "$SECRET_FILE" ]; then
        WLS_PASSWORD=$(read_prop "$SECRET_FILE" "wls.password")
    fi

    if [ -z "$WLS_PASSWORD" ] && [ "$DRY_RUN" = false ] && [ "$ACTION" != "status" ]; then
        printf 'WebLogic password for %s@%s: ' "$WLS_USER" "$WLS_ADMINURL"
        read -rs WLS_PASSWORD
        printf '\n'
        [ -n "$WLS_PASSWORD" ] || die "No password provided."
        printf 'Save to %s? [y/N] ' "$SECRET_FILE"
        read -r save_choice
        if [[ "$save_choice" =~ ^[Yy]$ ]]; then
            printf 'wls.password=%s\n' "$WLS_PASSWORD" > "$SECRET_FILE"
            chmod 600 "$SECRET_FILE"
            ok "Saved password to ${SECRET_FILE} (mode 600)"
            check_secret_safety "$SECRET_FILE"
        fi
    fi
fi

# --- Locate dist directory ---
DIST_ROOT="${SCRIPT_DIR}/dist"
[ -d "$DIST_ROOT" ] || die "No dist/ directory. Run ./build.sh ${BUILD_PROFILE} first."

if [ -n "$BUILD_VER" ]; then
    DIST_DIR="${DIST_ROOT}/${BUILD_VER}"
    [ -d "$DIST_DIR" ] || die "dist/${BUILD_VER}/ not found."
else
    # Newest dist by mtime (matches build.sh convention).
    DIST_DIR=$(ls -1t "$DIST_ROOT" 2>/dev/null | while read -r d; do
        [ -d "$DIST_ROOT/$d" ] && echo "$DIST_ROOT/$d" && break
    done)
    [ -n "$DIST_DIR" ] && [ -d "$DIST_DIR" ] || die "No build directories found under dist/. Run ./build.sh first."
fi
DIST_NAME=$(basename "$DIST_DIR")

SHARED_LIB_WAR="${DIST_DIR}/vorpal-blade-library-shared.war"
FSMAR_JAR="${DIST_DIR}/vorpal-blade-library-fsmar.jar"
FSMAR3_JAR="${DIST_DIR}/vorpal-blade-library-fsmar3.jar"
SHARED_LIB_NAME="vorpal-blade"  # Extension-Name from libs/shared/pom.xml

# --- Header ---
log "${C_BOLD}BLADE deploy${C_RESET}"
log "  environment:  ${ENV_NAME}"
log "  build:        ${DIST_NAME} (${BUILD_PROFILE})"
case "$SUBDIR" in
    admin|services) log "  subdir:       ${SUBDIR}/ → ${TARGET}" ;;
    shared)         log "  subdir:       ${SUBDIR} → ${WLS_TGT_BOTH} (shared library)" ;;
    fsmar)          log "  subdir:       ${SUBDIR} → ${SSH_HOST:+${SSH_USER}@${SSH_HOST}:}${APPROUTER_DIR}" ;;
esac
if [ "$SUBDIR" != "fsmar" ]; then
    log "  WebLogic:     ${WLS_USER}@${WLS_ADMINURL}"
fi
log "  action:       ${ACTION}"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

MVNW="${SCRIPT_DIR}/mvnw"

run_mvn() {
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] mvnw $*${C_RESET}" | sed 's/-Dpassword=[^ ]*/-Dpassword=***/'
        return 0
    fi
    "$MVNW" -q "$@"
}

# Deploy / undeploy every deployable in dist/<ver>/<subdir>/ to <target>.
# App name = artifact basename without extension (e.g. configurator.war →
# "configurator", blade-admin.ear → "blade-admin"). The admin tier ships as a
# single blade-admin.ear; services ship as individual WARs.
deploy_subdir() {
    local sub="$1" target="$2" action="$3"
    local src_dir="${DIST_DIR}/${sub}"
    [ -d "$src_dir" ] || die "Source dir not found: ${src_dir}. Run ./build.sh ${BUILD_PROFILE} first."

    info "Subdir: ${sub}/ → ${target}"
    shopt -s nullglob
    # If the tier ships an EAR (admin → blade-admin.ear), that EAR is the deploy
    # unit. Loose WARs may also sit alongside it for individual redeploys during
    # testing, but deploying them here too would register the same context-roots
    # twice — so prefer the EAR(s) when present, else deploy the WARs.
    local ears=("$src_dir"/*.ear)
    local wars
    if [ ${#ears[@]} -gt 0 ]; then
        wars=("${ears[@]}")
    else
        wars=("$src_dir"/*.war)
    fi
    shopt -u nullglob

    if [ ${#wars[@]} -eq 0 ]; then
        warn "No deployables found in ${src_dir}. Nothing to ${action}."
        return 0
    fi

    local rc=0 war app
    for war in "${wars[@]}"; do
        app=$(basename "$war"); app="${app%.*}"
        log "  ${C_DIM}→ ${app}${C_RESET}"
        case "$action" in
            deploy)
                run_mvn "${WLS_PLUGIN}:deploy" \
                    -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                    -Dtargets="$target" -Dsource="$war" -Dname="$app" -Dupload=true || rc=$?
                ;;
            undeploy)
                run_mvn "${WLS_PLUGIN}:undeploy" \
                    -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                    -Dname="$app" || rc=$?
                ;;
            status)
                # Single list-apps covers the whole server; called once outside the loop below.
                :
                ;;
        esac
    done

    if [ "$action" = "status" ]; then
        run_mvn "${WLS_PLUGIN}:list-apps" \
            -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD"
    fi
    return $rc
}

deploy_shared() {
    local action="$1"
    info "Subdir: shared → ${WLS_TGT_BOTH}"
    [ -f "$SHARED_LIB_WAR" ] || die "Missing: ${SHARED_LIB_WAR}"

    case "$action" in
        deploy)
            run_mvn "${WLS_PLUGIN}:deploy" \
                -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                -Dtargets="$WLS_TGT_BOTH" -Dsource="$SHARED_LIB_WAR" \
                -Dname="$SHARED_LIB_NAME" -DlibraryModule=true -Dupload=true
            ;;
        undeploy)
            run_mvn "${WLS_PLUGIN}:undeploy" \
                -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                -Dname="$SHARED_LIB_NAME" -DlibraryModule=true
            ;;
        status)
            run_mvn "${WLS_PLUGIN}:list-apps" \
                -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD"
            ;;
    esac
}

deploy_fsmar() {
    local action="$1"
    info "Subdir: fsmar → ${SSH_HOST:+${SSH_USER}@${SSH_HOST}:}${APPROUTER_DIR}"

    # FSMAR 2 (legacy) and FSMAR 3 are two distinct fat JARs that both live in
    # OCCAS's approuter/. Only one is activated at a time in the admin console.
    # Deploy whichever are present so operators can switch without a rebuild.
    local jars=()
    [ -f "$FSMAR_JAR" ]  && jars+=("$FSMAR_JAR")
    [ -f "$FSMAR3_JAR" ] && jars+=("$FSMAR3_JAR")
    if [ ${#jars[@]} -eq 0 ]; then
        err "Missing: no FSMAR jars found in ${DIST_DIR}"
        return 1
    fi

    local src dest_file
    for src in "${jars[@]}"; do
        dest_file="${APPROUTER_DIR}/$(basename "$src")"
        log "  ${C_DIM}→ $(basename "$src")${C_RESET}"
        case "$action" in
            deploy)
                if [ -n "$SSH_HOST" ]; then
                    [ -n "$SSH_USER" ] || die "ssh.host set but ssh.user missing in ${CONF_FILE}"
                    if [ "$DRY_RUN" = true ]; then
                        log "${C_DIM}  [dry-run] scp ${src} ${SSH_USER}@${SSH_HOST}:${dest_file}${C_RESET}"
                    else
                        scp "$src" "${SSH_USER}@${SSH_HOST}:${dest_file}"
                    fi
                else
                    if [ "$DRY_RUN" = true ]; then
                        log "${C_DIM}  [dry-run] cp ${src} ${dest_file}${C_RESET}"
                    else
                        [ -d "$APPROUTER_DIR" ] || die "approuter.dir does not exist: ${APPROUTER_DIR}"
                        cp "$src" "$dest_file"
                    fi
                fi
                ;;
            undeploy)
                if [ -n "$SSH_HOST" ]; then
                    if [ "$DRY_RUN" = true ]; then
                        log "${C_DIM}  [dry-run] ssh ${SSH_USER}@${SSH_HOST} rm -f ${dest_file}${C_RESET}"
                    else
                        ssh "${SSH_USER}@${SSH_HOST}" rm -f "$dest_file"
                    fi
                else
                    if [ "$DRY_RUN" = true ]; then
                        log "${C_DIM}  [dry-run] rm -f ${dest_file}${C_RESET}"
                    else
                        rm -f "$dest_file"
                    fi
                fi
                ;;
            status)
                if [ -n "$SSH_HOST" ]; then
                    ssh "${SSH_USER}@${SSH_HOST}" ls -l "$dest_file" 2>&1 || warn "$(basename "$src") not present on ${SSH_HOST}"
                else
                    ls -l "$dest_file" 2>&1 || warn "$(basename "$src") not present at ${dest_file}"
                fi
                ;;
        esac
    done

    case "$action" in
        deploy)   warn "Engine tier reboot required for FSMAR changes to take effect." ;;
        undeploy) warn "Engine tier reboot required for FSMAR removal to take effect." ;;
    esac
}

# --- Dispatch ---
set +e
case "$SUBDIR" in
    admin|services) deploy_subdir "$SUBDIR" "$TARGET" "$ACTION" ;;
    shared)         deploy_shared "$ACTION" ;;
    fsmar)          deploy_fsmar  "$ACTION" ;;
esac
RC=$?
set -e

log ""
if [ $RC -eq 0 ]; then
    ok "${SUBDIR} ${ACTION}: done"
else
    err "${SUBDIR} ${ACTION}: failed (exit ${RC})"
    exit $RC
fi
