#!/usr/bin/env bash
# ============================================================================
# deploy.sh - Profile-driven deployment wrapper for BLADE
#
# Usage:
#   ./deploy.sh <env> [tier|action] [--build VERSION] [--dry-run]
#
# Environments:
#   build-profiles/deploy/<env>.conf        — connection, targets, paths (committed)
#   build-profiles/deploy/<env>.secret      — wls.password only (gitignored)
#
# Tiers:
#   shared-lib   Shared library → AdminServer + cluster (WebLogic library)
#   admin        All admin WARs → AdminServer only
#   services     Services EAR  → cluster only
#   fsmar        fsmar.jar     → $DOMAIN/approuter/ (cp or scp); reboot engine tier
#   (omitted)    All four tiers in order: shared-lib → admin → services → fsmar
#
# Actions:
#   undeploy     Tear down the selected tier(s)
#   status       Query WebLogic for deployment state of each tier
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
#   ./deploy.sh production
#   ./deploy.sh production admin
#   ./deploy.sh production services --build 2.9.5-320
#   ./deploy.sh production undeploy
#   ./deploy.sh production status
#   ./deploy.sh production --dry-run
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

# --- Parse args ---
ENV_NAME=""
TIER=""          # shared-lib | admin | services | fsmar | "" (all)
ACTION="deploy"  # deploy | undeploy | status
BUILD_VER=""
DRY_RUN=false

show_usage() {
    sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) show_usage 0 ;;
        --build)   shift; BUILD_VER="${1:-}"; [ -n "$BUILD_VER" ] || die "--build requires a version argument" ;;
        --build=*) BUILD_VER="${1#--build=}" ;;
        --dry-run) DRY_RUN=true ;;
        undeploy|status) ACTION="$1" ;;
        shared-lib|admin|services|fsmar) TIER="$1" ;;
        -*) die "Unknown option: $1" ;;
        *)  if [ -z "$ENV_NAME" ]; then ENV_NAME="$1"; else die "Unexpected argument: $1"; fi ;;
    esac
    shift
done

[ -n "$ENV_NAME" ] || { err "Environment name required."; show_usage 1; }

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
# Same pattern build.sh uses: grep '^key=' | cut -d= -f2-
read_prop() {
    local file="$1" key="$2"
    grep "^${key}=" "$file" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

BUILD_PROFILE=$(read_prop "$CONF_FILE" "build.profile")
WLS_ADMINURL=$(read_prop   "$CONF_FILE" "wls.adminurl")
WLS_USER=$(read_prop       "$CONF_FILE" "wls.user")
WLS_TGT_ADMIN=$(read_prop  "$CONF_FILE" "wls.targets.admin")
WLS_TGT_CLUSTER=$(read_prop "$CONF_FILE" "wls.targets.cluster")
WLS_TGT_BOTH=$(read_prop   "$CONF_FILE" "wls.targets.both")
SSH_HOST=$(read_prop       "$CONF_FILE" "ssh.host")
SSH_USER=$(read_prop       "$CONF_FILE" "ssh.user")
APPROUTER_DIR=$(read_prop  "$CONF_FILE" "approuter.dir")

[ -n "$BUILD_PROFILE" ]   || die "${CONF_FILE}: missing build.profile"
[ -n "$WLS_ADMINURL" ]    || die "${CONF_FILE}: missing wls.adminurl"
[ -n "$WLS_USER" ]        || die "${CONF_FILE}: missing wls.user"
[ -n "$WLS_TGT_ADMIN" ]   || die "${CONF_FILE}: missing wls.targets.admin"
[ -n "$WLS_TGT_CLUSTER" ] || die "${CONF_FILE}: missing wls.targets.cluster"
[ -n "$WLS_TGT_BOTH" ]    || die "${CONF_FILE}: missing wls.targets.both"
[ -n "$APPROUTER_DIR" ]   || die "${CONF_FILE}: missing approuter.dir"

# --- Secret safeguards ---
check_secret_safety() {
    local f="$1"
    # 1) Must be gitignored. `git check-ignore -q` returns 0 iff the file is ignored.
    if ! git -C "$SCRIPT_DIR" check-ignore -q "$f" 2>/dev/null; then
        err "REFUSING: ${f} is not gitignored. This file contains passwords."
        err "Fix .gitignore or build-profiles/deploy/.gitignore before proceeding."
        exit 1
    fi
    # 2) Must be mode 600 (warn only; deploy still proceeds).
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
WLS_PASSWORD=""
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

# --- Artifact paths ---
SHARED_LIB_WAR="${DIST_DIR}/vorpal-blade-library-shared.war"
SERVICES_EAR="${DIST_DIR}/vorpal-blade-services-${BUILD_PROFILE}.ear"
FSMAR_JAR="${DIST_DIR}/vorpal-blade-library-fsmar.jar"
SHARED_LIB_NAME="vorpal-blade"  # Extension-Name from libs/shared/pom.xml:239

# --- Header ---
log "${C_BOLD}BLADE deploy${C_RESET}"
log "  environment:  ${ENV_NAME}"
log "  build:        ${DIST_NAME} (${BUILD_PROFILE})"
log "  WebLogic:     ${WLS_USER}@${WLS_ADMINURL}"
log "  action:       ${ACTION}${TIER:+ (${TIER} only)}"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

# --- mvnw wrapper ---
MVNW="${SCRIPT_DIR}/mvnw"

run_mvn() {
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] mvnw $*${C_RESET}" | sed 's/-Dpassword=[^ ]*/-Dpassword=***/'
        return 0
    fi
    "$MVNW" -q "$@"
}

# Common weblogic-maven-plugin properties (password passed only at exec time).
mvn_wls_base() {
    printf -- '-Dadminurl=%s -Duser=%s -Dpassword=%s -Dupload=true' \
        "$WLS_ADMINURL" "$WLS_USER" "$WLS_PASSWORD"
}

# --- Tier implementations ---
# Each tier function takes one arg: the action (deploy|undeploy|status).

tier_shared_lib() {
    local action="$1"
    info "Tier: shared-lib → ${WLS_TGT_BOTH}"
    [ -f "$SHARED_LIB_WAR" ] || { err "Missing: ${SHARED_LIB_WAR}"; return 1; }

    case "$action" in
        deploy)
            # shellcheck disable=SC2046
            run_mvn "${WLS_PLUGIN}:deploy" \
                -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                -Dtargets="$WLS_TGT_BOTH" \
                -Dsource="$SHARED_LIB_WAR" \
                -Dname="$SHARED_LIB_NAME" \
                -DlibraryModule=true \
                -Dupload=true
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

tier_admin() {
    local action="$1"
    local any_found=false

    info "Tier: admin → ${WLS_TGT_ADMIN}"
    shopt -s nullglob
    local wars=("$DIST_DIR"/vorpal-blade-admin-*.war "$DIST_DIR"/vorpal-blade-javadoc.war)
    shopt -u nullglob

    for war in "${wars[@]}"; do
        [ -f "$war" ] || continue
        any_found=true
        local app
        app=$(basename "$war" .war)
        log "  ${C_DIM}→ ${app}${C_RESET}"
        case "$action" in
            deploy)
                run_mvn "${WLS_PLUGIN}:deploy" \
                    -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                    -Dtargets="$WLS_TGT_ADMIN" \
                    -Dsource="$war" \
                    -Dname="$app" \
                    -Dupload=true
                ;;
            undeploy)
                run_mvn "${WLS_PLUGIN}:undeploy" \
                    -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                    -Dname="$app"
                ;;
            status) : ;;  # covered by shared_lib's list-apps
        esac
    done

    if [ "$any_found" = false ]; then
        warn "No admin WARs found in ${DIST_DIR}. Nothing to ${action}."
    fi
}

tier_services() {
    local action="$1"
    local name="vorpal-blade-services"
    info "Tier: services → ${WLS_TGT_CLUSTER}"
    if [ ! -f "$SERVICES_EAR" ]; then
        err "Missing: $(basename "$SERVICES_EAR")"
        err "  in ${DIST_DIR}"
        err "  Run: ./build.sh ${BUILD_PROFILE}"
        return 1
    fi

    case "$action" in
        deploy)
            run_mvn "${WLS_PLUGIN}:deploy" \
                -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                -Dtargets="$WLS_TGT_CLUSTER" \
                -Dsource="$SERVICES_EAR" \
                -Dname="$name" \
                -Dupload=true
            ;;
        undeploy)
            run_mvn "${WLS_PLUGIN}:undeploy" \
                -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                -Dname="$name"
            ;;
        status) : ;;
    esac
}

tier_fsmar() {
    local action="$1"
    info "Tier: fsmar → ${SSH_HOST:+${SSH_USER}@${SSH_HOST}:}${APPROUTER_DIR}"
    [ -f "$FSMAR_JAR" ] || { err "Missing: ${FSMAR_JAR}"; return 1; }

    local dest_file="${APPROUTER_DIR}/$(basename "$FSMAR_JAR")"

    case "$action" in
        deploy)
            if [ -n "$SSH_HOST" ]; then
                [ -n "$SSH_USER" ] || die "ssh.host set but ssh.user missing in ${CONF_FILE}"
                if [ "$DRY_RUN" = true ]; then
                    log "${C_DIM}  [dry-run] scp ${FSMAR_JAR} ${SSH_USER}@${SSH_HOST}:${dest_file}${C_RESET}"
                else
                    scp "$FSMAR_JAR" "${SSH_USER}@${SSH_HOST}:${dest_file}"
                fi
            else
                if [ "$DRY_RUN" = true ]; then
                    log "${C_DIM}  [dry-run] cp ${FSMAR_JAR} ${dest_file}${C_RESET}"
                else
                    [ -d "$APPROUTER_DIR" ] || die "approuter.dir does not exist: ${APPROUTER_DIR}"
                    cp "$FSMAR_JAR" "$dest_file"
                fi
            fi
            warn "Engine tier reboot required for FSMAR changes to take effect."
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
            warn "Engine tier reboot required for FSMAR removal to take effect."
            ;;
        status)
            if [ -n "$SSH_HOST" ]; then
                ssh "${SSH_USER}@${SSH_HOST}" ls -l "$dest_file" 2>&1 || warn "fsmar.jar not present on ${SSH_HOST}"
            else
                ls -l "$dest_file" 2>&1 || warn "fsmar.jar not present at ${dest_file}"
            fi
            ;;
    esac
}

# --- Tier selection & execution order ---
# Full deploy order: shared-lib first (needed by admin/services), then admin, then services, then fsmar last (reboot).
# Full undeploy order: reverse — fsmar → services → admin → shared-lib.
declare -a TIERS_TO_RUN
if [ -z "$TIER" ]; then
    if [ "$ACTION" = "undeploy" ]; then
        TIERS_TO_RUN=(fsmar services admin shared-lib)
    else
        TIERS_TO_RUN=(shared-lib admin services fsmar)
    fi
else
    TIERS_TO_RUN=("$TIER")
fi

# Bash 3.2 compatible: track per-tier results in parallel arrays.
RESULT_TIERS=()
RESULT_STATUS=()
for t in "${TIERS_TO_RUN[@]}"; do
    fn_name="tier_${t//-/_}"
    set +e
    "$fn_name" "$ACTION"
    rc=$?
    set -e
    RESULT_TIERS+=("$t")
    if [ $rc -eq 0 ]; then
        RESULT_STATUS+=("ok")
    else
        RESULT_STATUS+=("fail")
    fi
    log ""
done

# --- Summary ---
log "${C_BOLD}Summary:${C_RESET}"
any_failed=false
for i in "${!RESULT_TIERS[@]}"; do
    t="${RESULT_TIERS[$i]}"
    s="${RESULT_STATUS[$i]}"
    case "$s" in
        ok)   ok   "$t" ;;
        fail) err  "$t"; any_failed=true ;;
    esac
done

[ "$any_failed" = false ] || exit 1
