#!/usr/bin/env bash
# ============================================================================
# deploy.sh - Deploy ONE named artifact to a WebLogic target. No tier magic.
#
# You name the exact file and where it goes; deploy.sh does exactly that and
# nothing else. There is no built-in notion of tiers, ordering, or which app
# belongs where — that is yours to decide, per deploy.
#
# Usage:
#   ./deploy.sh <env> <file> [target] [action] [options]
#
# <env>   ~/.blade/<env>.conf (or a path to a conf). Connection + secrets:
#         wls.adminurl, wls.user, admin.password (ENC), optional wls.target.
# <file>  the exact artifact to deploy: a path, or a bare filename found in the
#         newest dist/<ver>/ tree (searched across lib/ admin/ services/ test/
#         proto/ and the root). e.g. blade-admin.ear, gateway.war, blade-services.ear
# target  the WebLogic target (server or cluster name). If omitted, wls.target
#         from the conf is used; with neither, deploy.sh asks for one.
# action  deploy (default) | undeploy | status
#
# Options:
#   --library        deploy/undeploy <file> as a WebLogic shared library
#   --approuter      copy <file> into approuter.dir on THIS host (the FSMAR
#                    mechanic) instead of a WebLogic deploy — no target, no WLS
#   --name NAME      deployment name (default: filename without extension)
#   --build VER      take <file> from dist/<VER>/ instead of the newest build
#   --dry-run        print what would run; change nothing
#
# Password priority (highest wins):
#   1. BLADE_WLS_PASSWORD environment variable
#   2. admin.password in the conf (ENC(...) wrapped)
#   3. Interactive prompt (read -s), with offer to save
#
# Examples:
#   ./deploy.sh production blade-admin.ear AdminServer
#   ./deploy.sh production gateway.war cluster1
#   ./deploy.sh production blade-services.ear cluster1
#   ./deploy.sh production blade-shared.war cluster1 --library
#   ./deploy.sh production blade-fsmar.jar --approuter
#   ./deploy.sh production gateway.war cluster1 undeploy
#   ./deploy.sh production ./dist/3.0.6-908/admin/blade-portal.war AdminServer --dry-run
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
DEPLOY_DIR="${PROFILES_DIR}/deploy"

# The WebLogic Maven plugin coordinate. The VERSION must match the running
# server's WebLogic version (OCCAS 8.1 -> 14.1.1, OCCAS 8.2/8.3 -> 14.1.2); a
# mismatched plugin won't resolve from ~/.m2, since bootstrap.sh installs only
# the server's own version. Resolved once DIST_DIR is known:
#   1. wls.plugin.version in the deploy conf      (explicit override)
#   2. weblogic.version from the platform conf build.sh copied into dist/
#   3. WLS_PLUGIN_VERSION_DEFAULT below           (last-resort fallback + warn)
WLS_PLUGIN_ARTIFACT="com.oracle.weblogic:weblogic-maven-plugin"
WLS_PLUGIN_VERSION_DEFAULT="14.1.1"
WLS_PLUGIN=""

# --- Colors (disabled if NO_COLOR set or not a tty) ---
if [ -z "${NO_COLOR:-}" ] && [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
    C_BLUE=$'\033[34m'; C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
    C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_DIM=""; C_BOLD=""; C_RESET=""
fi

log()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s\xe2\x9c\x93%s %s\n'   "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s\xe2\x9a\xa0%s %s\n'   "$C_YELLOW" "$C_RESET" "$*"; }
err()  { printf '%s\xe2\x9c\x97%s %s\n'   "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }

show_usage() {
    sed -n '2,45p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

# --- Parse args ---
# Positional after <env>: <file>, then an optional <target>, then an optional
# <action>. action is a closed set; target is "the remaining positional".
ENV_ARG=""
FILE_ARG=""
TARGET_ARG=""
ACTION="deploy"
MODE="app"          # app | library | approuter
NAME_OVERRIDE=""
BUILD_VER=""
DRY_RUN=false

POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help)   show_usage 0 ;;
        --library)   MODE="library" ;;
        --approuter) MODE="approuter" ;;
        --name)      shift; NAME_OVERRIDE="${1:-}"; [ -n "$NAME_OVERRIDE" ] || die "--name requires an argument" ;;
        --name=*)    NAME_OVERRIDE="${1#--name=}" ;;
        --build)     shift; BUILD_VER="${1:-}"; [ -n "$BUILD_VER" ] || die "--build requires a version argument" ;;
        --build=*)   BUILD_VER="${1#--build=}" ;;
        --dry-run)   DRY_RUN=true ;;
        -*)          die "Unknown option: $1" ;;
        *)           POSITIONAL+=("$1") ;;
    esac
    shift
done

[ ${#POSITIONAL[@]} -ge 1 ] || { err "Environment required."; show_usage 1; }
[ ${#POSITIONAL[@]} -ge 2 ] || { err "A file to deploy is required."; show_usage 1; }
ENV_ARG="${POSITIONAL[0]}"
FILE_ARG="${POSITIONAL[1]}"

# Remaining positionals: an action keyword sets ACTION, anything else is target.
for i in "${!POSITIONAL[@]}"; do
    [ "$i" -le 1 ] && continue
    case "${POSITIONAL[$i]}" in
        deploy|undeploy|status) ACTION="${POSITIONAL[$i]}" ;;
        *) [ -z "$TARGET_ARG" ] && TARGET_ARG="${POSITIONAL[$i]}" \
             || die "Unexpected argument: '${POSITIONAL[$i]}'" ;;
    esac
done

# --- Resolve <env> to its ONE config file (path or name). Config + secrets live
# together in ~/.blade/<env>.conf; the build-profiles/deploy path is a fallback. ---
BLADE_HOME="${BLADE_HOME:-$HOME/.blade}"
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"
    ENV_NAME="$(basename "${ENV_ARG%.conf}")"
else
    ENV_NAME="$ENV_ARG"
    CONF_FILE="${BLADE_HOME}/${ENV_NAME}.conf"
    [ -f "$CONF_FILE" ] || CONF_FILE="${DEPLOY_DIR}/${ENV_NAME}.conf"
fi
SECRET_FILE="$CONF_FILE"

if [ ! -f "$CONF_FILE" ]; then
    err "Deploy profile not found: ${CONF_FILE}"
    if [ -d "$DEPLOY_DIR" ]; then
        log ""; log "Available environments:"
        for f in "$DEPLOY_DIR"/*.conf; do [ -f "$f" ] && log "  $(basename "${f%.conf}")"; done
    fi
    exit 1
fi

# --- Load non-secret properties ---
read_prop() {
    local file="$1" key="$2" v
    v="$({ grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$v" in ENC\(*\)) v="${v#ENC(}"; v="${v%)}" ;; esac   # secret: strip ENC() wrapper
    printf '%s' "$v"
}

WLS_ADMINURL=$(read_prop   "$CONF_FILE" "wls.adminurl")
WLS_TRUSTSTORE=$(read_prop "$CONF_FILE" "wls.truststore"); WLS_TRUSTSTORE="${WLS_TRUSTSTORE/#\~/$HOME}"
WLS_TRUSTSTORE_TYPE=$(read_prop "$CONF_FILE" "wls.truststore.type"); WLS_TRUSTSTORE_TYPE="${WLS_TRUSTSTORE_TYPE:-PKCS12}"
WLS_USER=$(read_prop       "$CONF_FILE" "wls.user")
WLS_TARGET_DEFAULT=$(read_prop "$CONF_FILE" "wls.target")
APPROUTER_DIR=$(read_prop  "$CONF_FILE" "approuter.dir")
WLS_PLUGIN_VERSION=$(read_prop "$CONF_FILE" "wls.plugin.version")

# Does this run talk to WebLogic? (approuter is a plain file copy — it does not.)
NEEDS_WLS=true
[ "$MODE" = "approuter" ] && NEEDS_WLS=false

if [ "$NEEDS_WLS" = true ]; then
    [ -n "$WLS_ADMINURL" ] || die "${CONF_FILE}: missing wls.adminurl"
    [ -n "$WLS_USER" ]     || die "${CONF_FILE}: missing wls.user"
fi

# --- Secret safeguards ---
check_secret_safety() {
    local f="$1"
    if ! git -C "$SCRIPT_DIR" check-ignore -q "$f" 2>/dev/null; then
        # Only a concern for files under the repo; a conf in ~/.blade is fine.
        case "$f" in "$SCRIPT_DIR"/*)
            err "REFUSING: ${f} is not gitignored. This file contains passwords."
            err "Fix .gitignore or build-profiles/deploy/.gitignore before proceeding."
            exit 1 ;;
        esac
    fi
}
check_secret_safety "$SECRET_FILE"

# --- Resolve password (env var > conf > interactive prompt) ---
WLS_PASSWORD=""
if [ "$NEEDS_WLS" = true ]; then
    if [ -n "${BLADE_WLS_PASSWORD:-}" ]; then
        WLS_PASSWORD="$BLADE_WLS_PASSWORD"
    elif [ -f "$SECRET_FILE" ]; then
        WLS_PASSWORD=$(read_prop "$SECRET_FILE" "admin.password")
    fi
    if [ -z "$WLS_PASSWORD" ] && [ "$DRY_RUN" = false ] && [ "$ACTION" != "status" ]; then
        printf 'WebLogic password for %s@%s: ' "$WLS_USER" "$WLS_ADMINURL"
        read -rs WLS_PASSWORD; printf '\n'
        [ -n "$WLS_PASSWORD" ] || die "No password provided."
        printf 'Save to %s? [y/N] ' "$SECRET_FILE"
        read -r save_choice
        if [[ "$save_choice" =~ ^[Yy]$ ]]; then
            printf 'admin.password=ENC(%s)\n' "$WLS_PASSWORD" >> "$SECRET_FILE"
            chmod 600 "$SECRET_FILE"
            ok "Saved admin.password to ${SECRET_FILE} (mode 600)"
            check_secret_safety "$SECRET_FILE"
        fi
    fi
fi

# --- t3s: SSL trust for the WebLogic Maven plugin's Deployer JVM ---
case "$WLS_ADMINURL" in
    t3s://*)
        if [ -z "$WLS_TRUSTSTORE" ] && [ -f "${SCRIPT_DIR}/tls/out/${ENV_NAME}/blade-trust.p12" ]; then
            WLS_TRUSTSTORE="${SCRIPT_DIR}/tls/out/${ENV_NAME}/blade-trust.p12"
            WLS_TRUSTSTORE_TYPE=PKCS12
        fi
        if [ -n "$WLS_TRUSTSTORE" ]; then
            [ -f "$WLS_TRUSTSTORE" ] || die "wls.truststore not found: ${WLS_TRUSTSTORE}"
            TRUST_PW="${BLADE_TRUST_PASSWORD:-${BLADE_STORE_PASSWORD:-}}"
            [ -z "$TRUST_PW" ] && [ -f "$SECRET_FILE" ] && TRUST_PW=$(read_prop "$SECRET_FILE" "tls.trust.passphrase")
            [ -z "$TRUST_PW" ] && [ -f "$SECRET_FILE" ] && TRUST_PW=$(read_prop "$SECRET_FILE" "store.password")
            export MAVEN_OPTS="${MAVEN_OPTS:-} -Dweblogic.security.TrustKeyStore=CustomTrust \
-Dweblogic.security.CustomTrustKeyStoreFileName=${WLS_TRUSTSTORE} \
-Dweblogic.security.CustomTrustKeyStoreType=${WLS_TRUSTSTORE_TYPE}${TRUST_PW:+ -Dweblogic.security.CustomTrustKeyStorePassPhrase=${TRUST_PW}}"
        else
            warn "t3s admin URL with no trust store — relying on the JVM default truststore (CA must be in cacerts)."
        fi
        ;;
esac

# --- Locate the dist directory (only needed when <file> is a bare name) ---
DIST_ROOT="${SCRIPT_DIR}/dist"
DIST_DIR=""
if [ -n "$BUILD_VER" ]; then
    DIST_DIR="${DIST_ROOT}/${BUILD_VER}"
    [ -d "$DIST_DIR" ] || die "dist/${BUILD_VER}/ not found."
elif [ -d "$DIST_ROOT" ]; then
    DIST_DIR=$(ls -1t "$DIST_ROOT" 2>/dev/null | while read -r d; do
        [ -d "$DIST_ROOT/$d" ] && echo "$DIST_ROOT/$d" && break
    done)
fi

# --- Resolve <file> to an actual artifact path ---
# A path (contains a slash or exists as given) is used verbatim; otherwise the
# newest dist tree is searched for a file of that name.
ART=""
if [ -f "$FILE_ARG" ]; then
    ART="$FILE_ARG"
elif [[ "$FILE_ARG" == */* ]]; then
    die "File not found: ${FILE_ARG}"
else
    [ -n "$DIST_DIR" ] && [ -d "$DIST_DIR" ] || die "No dist/ to search for '${FILE_ARG}'. Run ./build.sh first, give a path, or pass --build VER."
    # Search root + tier subdirs for an exact filename match.
    ART=$(find "$DIST_DIR" -maxdepth 2 -type f -name "$FILE_ARG" 2>/dev/null | head -1)
    [ -n "$ART" ] || die "No artifact named '${FILE_ARG}' under ${DIST_DIR}. Available:
$(find "$DIST_DIR" -maxdepth 2 -type f \( -name '*.war' -o -name '*.ear' -o -name '*.jar' \) -exec basename {} \; 2>/dev/null | sort | sed 's/^/  /')"
fi
ART_BASE=$(basename "$ART")

# Deployment name: filename without extension, unless overridden.
APP_NAME="${NAME_OVERRIDE:-${ART_BASE%.*}}"

# --- Resolve the target (WebLogic modes only) ---
TARGET=""
if [ "$NEEDS_WLS" = true ]; then
    TARGET="${TARGET_ARG:-$WLS_TARGET_DEFAULT}"
    if [ -z "$TARGET" ] && [ "$ACTION" = "deploy" ]; then
        die "No target given and no wls.target in ${CONF_FILE}. Name the WebLogic target (server or cluster), e.g. ./deploy.sh ${ENV_NAME} ${ART_BASE} <target>."
    fi
fi

# --- Resolve the WebLogic Maven plugin version (WLS modes only) ---
if [ "$NEEDS_WLS" = true ]; then
    WLS_PLUGIN_VERSION_SOURCE=""
    if [ -n "$WLS_PLUGIN_VERSION" ]; then
        WLS_PLUGIN_VERSION_SOURCE="wls.plugin.version in ${ENV_NAME}.conf"
    elif [ -n "$DIST_DIR" ]; then
        plat_conf=$(ls -1 "$DIST_DIR"/occas-*.conf 2>/dev/null | head -1)
        if [ -n "$plat_conf" ]; then
            WLS_PLUGIN_VERSION=$(read_prop "$plat_conf" "weblogic.version")
            [ -n "$WLS_PLUGIN_VERSION" ] && WLS_PLUGIN_VERSION_SOURCE="$(basename "$plat_conf")"
        fi
    fi
    if [ -z "$WLS_PLUGIN_VERSION" ]; then
        WLS_PLUGIN_VERSION="$WLS_PLUGIN_VERSION_DEFAULT"
        WLS_PLUGIN_VERSION_SOURCE="fallback default"
        warn "Could not determine WebLogic version; defaulting weblogic-maven-plugin to"
        warn "${WLS_PLUGIN_VERSION}. Set wls.plugin.version in ${CONF_FILE} if deploys fail to resolve it."
    fi
    WLS_PLUGIN="${WLS_PLUGIN_ARTIFACT}:${WLS_PLUGIN_VERSION}"
fi

# --- Header ---
log "${C_BOLD}BLADE deploy${C_RESET}"
log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
log "  artifact:     ${ART}"
log "  name:         ${APP_NAME}"
case "$MODE" in
    app)       log "  mode:         WebLogic application"; log "  target:       ${TARGET:-<none>}" ;;
    library)   log "  mode:         WebLogic shared library"; log "  target:       ${TARGET:-<none>}" ;;
    approuter) log "  mode:         approuter copy (FSMAR)"; log "  approuter:    ${APPROUTER_DIR:-<unset>}" ;;
esac
[ "$NEEDS_WLS" = true ] && log "  WebLogic:     ${WLS_USER}@${WLS_ADMINURL}"
[ "$NEEDS_WLS" = true ] && log "  WLS plugin:   ${WLS_PLUGIN_VERSION} (${WLS_PLUGIN_VERSION_SOURCE})"
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

# --- Do it ---
rc=0
case "$MODE" in
    approuter)
        [ -n "$APPROUTER_DIR" ] || die "${CONF_FILE}: missing approuter.dir (required for --approuter)"
        dest="${APPROUTER_DIR}/${ART_BASE}"
        info "approuter ${ACTION}: ${dest}"
        case "$ACTION" in
            deploy)
                if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] cp ${ART} ${dest}${C_RESET}"
                else [ -d "$APPROUTER_DIR" ] || die "approuter.dir does not exist: ${APPROUTER_DIR}"; cp "$ART" "$dest" || rc=$?; fi
                warn "Restart the engine tier so each engine re-fetches the App Router from the admin." ;;
            undeploy)
                if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] rm -f ${dest}${C_RESET}"; else rm -f "$dest" || rc=$?; fi
                warn "Restart the engine tier so the removed App Router is cleared." ;;
            status)
                if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] ls -l ${dest}${C_RESET}"; else ls -l "$dest" 2>&1 || warn "${ART_BASE} not present at ${dest}"; fi ;;
        esac ;;

    library|app)
        libflag=(); [ "$MODE" = "library" ] && libflag=(-Dlibrary=true)
        case "$ACTION" in
            deploy)
                run_mvn "${WLS_PLUGIN}:deploy" \
                    -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                    -Dtargets="$TARGET" -Dsource="$ART" -Dname="$APP_NAME" -Dupload=true "${libflag[@]}" || rc=$?
                [ "$MODE" = "library" ] && warn "Shared-library deploy needs a server restart to complete before dependents resolve it." ;;
            undeploy)
                run_mvn "${WLS_PLUGIN}:undeploy" \
                    -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" \
                    -Dname="$APP_NAME" "${libflag[@]}" || rc=$? ;;
            status)
                run_mvn "${WLS_PLUGIN}:list-apps" \
                    -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" || rc=$? ;;
        esac ;;
esac

log ""
if [ $rc -eq 0 ]; then
    ok "${APP_NAME} ${ACTION}: done"
else
    die "${APP_NAME} ${ACTION}: failed (exit ${rc})"
fi
