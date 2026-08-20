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
DEPLOY_ALL=false

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
        --all)       DEPLOY_ALL=true ;;
        -*)          die "Unknown option: $1" ;;
        *)           POSITIONAL+=("$1") ;;
    esac
    shift
done

[ ${#POSITIONAL[@]} -ge 1 ] || { err "Environment required."; show_usage 1; }
ENV_ARG="${POSITIONAL[0]}"
# The file is required for a deploy, but the check is DEFERRED: a missing profile
# is built first, and 'deploy.sh <env>' with no file just builds the profile.
FILE_ARG="${POSITIONAL[1]:-}"

# Remaining positionals: an action keyword sets ACTION, anything else is target.
for i in "${!POSITIONAL[@]}"; do
    [ "$i" -le 1 ] && continue
    case "${POSITIONAL[$i]}" in
        deploy|undeploy|status) ACTION="${POSITIONAL[$i]}" ;;
        *) [ -z "$TARGET_ARG" ] && TARGET_ARG="${POSITIONAL[$i]}" \
             || die "Unexpected argument: '${POSITIONAL[$i]}'" ;;
    esac
done
# With --all there is no <file>, so a lone action keyword lands in FILE_ARG (the
# second positional). Reassign it to ACTION so 'deploy.sh <env> --all undeploy' works.
if [ "$DEPLOY_ALL" = true ]; then
    case "$FILE_ARG" in deploy|undeploy|status) ACTION="$FILE_ARG"; FILE_ARG="" ;; esac
fi

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

# --- Load non-secret properties ---
read_prop() {
    local file="$1" key="$2" v
    v="$({ grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$v" in ENC\(*\)) v="${v#ENC(}"; v="${v%)}" ;; esac   # secret: strip ENC() wrapper
    printf '%s' "$v"
}

# Build ~/.blade/<env>.conf interactively when the named env has no profile yet,
# collecting only what a deploy needs: admin URL, user, optional target + password,
# and the t3s trust. Mirrors install.sh's "name it and fill it in" step so a fresh
# server isn't a dead end -- and it writes the SAME keys install.sh does, so the
# installer and deploy share one profile. Sets BUILT_PROFILE=true on success.
build_profile() {
    # A new profile is always a bare NAME landing in ~/.blade, never a path (and
    # never inside the repo, where a secret must not go).
    case "$ENV_NAME" in */*) die "To create a profile, give a NAME (e.g. 'production'), not a path." ;; esac
    mkdir -p "$BLADE_HOME"; chmod 700 "$BLADE_HOME" 2>/dev/null || true
    CONF_FILE="${BLADE_HOME}/${ENV_NAME}.conf"; SECRET_FILE="$CONF_FILE"

    local existing=() f
    if [ -d "$BLADE_HOME" ]; then
        for f in "$BLADE_HOME"/*.conf; do [ -f "$f" ] && existing+=("$(basename "${f%.conf}")"); done
    fi
    log ""
    warn "No deploy profile '${ENV_NAME}'."
    [ "${#existing[@]}" -gt 0 ] && log "  existing profiles: ${existing[*]}"
    printf 'Create it now? [Y/n] '; local yn; read -r yn
    case "$yn" in [Nn]*) die "Aborted — no profile created." ;; esac

    # Admin URL, assembled from host + TLS choice + port so the user never has to
    # remember the t3s scheme or the default ports.
    local host tls scheme port p
    printf '  AdminServer host (name or IP): '; read -r host
    [ -n "$host" ] || die "host is required."
    printf '  Use TLS (t3s)? [Y/n] '; read -r tls
    case "$tls" in [Nn]*) scheme=t3; port=7001 ;; *) scheme=t3s; port=7002 ;; esac
    printf '  Admin port [%s]: ' "$port"; read -r p; [ -n "$p" ] && port="$p"
    local url="${scheme}://${host}:${port}"

    local user target
    printf '  Admin user [weblogic]: '; read -r user; user="${user:-weblogic}"
    printf '  Default target (server/cluster; blank = choose per deploy): '; read -r target

    ( umask 077; {
        printf '# BLADE deploy profile for %s (mode 600). Secrets are key=ENC(...).\n' "$ENV_NAME"
        printf 'wls.adminurl=%s\n' "$url"
        printf 'wls.user=%s\n' "$user"
        [ -n "$target" ] && printf 'wls.target=%s\n' "$target"
    } > "$CONF_FILE" )
    chmod 600 "$CONF_FILE"

    # Password now is optional — deploy.sh will prompt (and offer to save) at
    # deploy time if it isn't here.
    local sp pw
    printf '  Save the admin password now? [y/N] '; read -r sp
    if [[ "$sp" =~ ^[Yy]$ ]]; then
        printf '  WebLogic password for %s: ' "$user"; read -rs pw; printf '\n'
        [ -n "$pw" ] && { printf 'admin.password=ENC(%s)\n' "$pw" >> "$CONF_FILE"; ok "saved admin.password"; }
    fi

    # t3s needs a trust store for the BLADE CA. deploy.sh auto-uses
    # ~/.blade/<env>/blade-trust.p12 when present; otherwise let them point at one.
    if [ "$scheme" = t3s ]; then
        local tp="${BLADE_HOME}/${ENV_NAME}/blade-trust.p12" ts
        if [ -f "$tp" ]; then
            ok "t3s trust: ${tp} found (used automatically)."
        else
            warn "t3s trust: ${tp} not found."
            printf '  Path to a PKCS12 trust store (blank to skip): '; read -r ts; ts="${ts/#\~/$HOME}"
            if [ -n "$ts" ] && [ -f "$ts" ]; then
                printf 'wls.truststore=%s\n' "$ts" >> "$CONF_FILE"
            elif [ -n "$ts" ]; then
                warn "  ${ts} not found — left unset; a t3s deploy will fail until trust is configured."
            fi
        fi
    fi

    # Record the OCCAS home so the self-contained wlst deploy engine works from
    # this profile (auto-detected; without it deploy falls back to the Maven plugin).
    local oh="${MW_HOME:-}"
    [ -z "$oh" ] && [ -d /opt/oracle/occas/current ] && oh=/opt/oracle/occas/current
    [ -n "$oh" ] && printf 'oracle.home=%s\n' "$oh" >> "$CONF_FILE"

    ok "Wrote ${CONF_FILE}"
    BUILT_PROFILE=true
}

# No profile for this env yet -> build one instead of dying. deploy.sh is the
# app's deploy tool; a first run against a new server shouldn't dead-end.
BUILT_PROFILE=false
[ -f "$CONF_FILE" ] || build_profile

# 'deploy.sh <env>' with no file: after (or without) building, there's nothing to
# deploy — point the way instead of erroring blankly.
if [ -z "$FILE_ARG" ] && [ "$DEPLOY_ALL" = false ]; then
    if [ "$BUILT_PROFILE" = true ]; then
        log ""; ok "Profile '${ENV_NAME}' ready."
        log "Deploy with:  ./deploy.sh ${ENV_NAME} <file> [target] [action]   (or --all)"
        exit 0
    fi
    err "A file to deploy is required (or pass --all)."; show_usage 1
fi

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
        if [ -z "$WLS_TRUSTSTORE" ]; then
            # Default cert store is ~/.blade/<env> (beside the config); certs.dir overrides.
            _cd="$(read_prop "$CONF_FILE" "certs.dir")"; _cd="${_cd/#\~/$HOME}"
            [ -z "$_cd" ] && _cd="${BLADE_HOME}/${ENV_NAME}"
            [ -f "${_cd}/blade-trust.p12" ] && { WLS_TRUSTSTORE="${_cd}/blade-trust.p12"; WLS_TRUSTSTORE_TYPE=PKCS12; }
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
    # A development build writes flat into dist/ (EARs/WARs at the root); a
    # production build nests each release in dist/<rev>-<build>/. Prefer the flat
    # dev tree when present, otherwise take the newest versioned release directory.
    if ls "$DIST_ROOT"/*.ear "$DIST_ROOT"/*.war >/dev/null 2>&1; then
        DIST_DIR="$DIST_ROOT"
    else
        DIST_DIR=$(ls -1t "$DIST_ROOT" 2>/dev/null | while read -r d; do
            [ -d "$DIST_ROOT/$d" ] && echo "$DIST_ROOT/$d" && break
        done)
    fi
fi

# Tier-aware artifact search within the resolved dist tree. On the flat dev tree
# (DIST_DIR == dist/) search only the root + tier folders, so a bare filename can
# never match an artifact inside a prod dist/<rev>-<build>/ release; a versioned
# tree has no nested releases, so maxdepth 2 (root + tier subdirs) is right there.
find_in_dist() {   # $1 = -name pattern
    if [ "$DIST_DIR" = "$DIST_ROOT" ]; then
        find "$DIST_DIR" -maxdepth 1 -type f -name "$1" 2>/dev/null
        local t
        for t in lib admin services test proto; do
            [ -d "$DIST_DIR/$t" ] && find "$DIST_DIR/$t" -maxdepth 1 -type f -name "$1" 2>/dev/null
        done
    else
        find "$DIST_DIR" -maxdepth 2 -type f -name "$1" 2>/dev/null
    fi
}

# --- Deploy engine ---------------------------------------------------------
# wlst.sh (self-contained; runs on the admin box, which has no Maven) when an
# OCCAS home is resolvable; otherwise the WebLogic Maven plugin. The wlst engine
# is misc/deploy-wls.sh — the SAME one install.sh uses, so there is one deploy
# code path. Override with BLADE_DEPLOY_ENGINE=wlst|maven.
OCCAS_HOME="$(read_prop "$CONF_FILE" oracle.home)"
[ -z "$OCCAS_HOME" ] && [ -n "${MW_HOME:-}" ] && OCCAS_HOME="$MW_HOME"
[ -z "$OCCAS_HOME" ] && [ -d /opt/oracle/occas/current ] && OCCAS_HOME=/opt/oracle/occas/current
ENGINE="${BLADE_DEPLOY_ENGINE:-}"
if [ -z "$ENGINE" ]; then
    if [ -n "$OCCAS_HOME" ] && [ -x "${OCCAS_HOME}/oracle_common/common/bin/wlst.sh" ]; then ENGINE=wlst; else ENGINE=maven; fi
fi
DEPLOY_JAVA_HOME="$(read_prop "$CONF_FILE" java.home)"
[ -z "$DEPLOY_JAVA_HOME" ] && [ -d /opt/oracle/java/current ] && DEPLOY_JAVA_HOME=/opt/oracle/java/current
[ -z "$DEPLOY_JAVA_HOME" ] && DEPLOY_JAVA_HOME="${JAVA_HOME:-}"

# The Maven plugin version only matters to the maven engine.
WLS_PLUGIN_VERSION_SOURCE=""
if [ "$NEEDS_WLS" = true ] && [ "$ENGINE" = maven ]; then
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
        warn "Could not determine WebLogic version; defaulting weblogic-maven-plugin to ${WLS_PLUGIN_VERSION}."
        warn "Set wls.plugin.version in ${CONF_FILE} if deploys fail to resolve it."
    fi
    WLS_PLUGIN="${WLS_PLUGIN_ARTIFACT}:${WLS_PLUGIN_VERSION}"
fi

MVNW="${SCRIPT_DIR}/mvnw"
run_mvn() {
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] mvnw $*${C_RESET}" | sed 's/-Dpassword=[^ ]*/-Dpassword=***/'
        return 0
    fi
    "$MVNW" -q "$@"
}

# One WebLogic deploy/undeploy/status via the selected engine. Returns 0 on
# success, 3 = could not connect (only the wlst engine distinguishes this, so a
# batch can abort), else non-zero.
wls_deploy_one() {
    local action="$1" name="$2" source="$3" targets="$4" islib="${5:-false}" drc=0
    if [ "$ENGINE" = wlst ]; then
        if [ "$DRY_RUN" = true ]; then
            log "${C_DIM}  [dry-run] wlst ${action} ${name}${source:+ src=$(basename "$source")}${targets:+ -> ${targets}}$([ "$islib" = true ] && echo ' (library)')${C_RESET}"
            return 0
        fi
        MW_HOME="$OCCAS_HOME" JAVA_HOME="$DEPLOY_JAVA_HOME" JAVA_VENDOR=Oracle \
            WLS_ADMINURL="$WLS_ADMINURL" WLS_USER="$WLS_USER" WLS_PASSWORD="$WLS_PASSWORD" \
            WLS_TRUSTSTORE="${WLS_TRUSTSTORE:-}" WLS_TRUSTSTORE_TYPE="${WLS_TRUSTSTORE_TYPE:-PKCS12}" WLS_TRUSTSTORE_PASSWORD="${TRUST_PW:-}" \
            WLS_ACTION="$action" WLS_NAME="$name" WLS_SOURCE="$source" WLS_TARGETS="$targets" WLS_LIBRARY="$islib" \
            bash "${SCRIPT_DIR}/misc/deploy-wls.sh" || drc=$?
        return "$drc"
    fi
    local libflag=(); [ "$islib" = true ] && libflag=(-Dlibrary=true)
    case "$action" in
        deploy)   run_mvn "${WLS_PLUGIN}:deploy" -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" -Dtargets="$targets" -Dsource="$source" -Dname="$name" -Dupload=true "${libflag[@]}" || drc=$? ;;
        undeploy) run_mvn "${WLS_PLUGIN}:undeploy" -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" -Dname="$name" "${libflag[@]}" || drc=$? ;;
        status)   run_mvn "${WLS_PLUGIN}:list-apps" -Dadminurl="$WLS_ADMINURL" -Duser="$WLS_USER" -Dpassword="$WLS_PASSWORD" || drc=$? ;;
    esac
    return "$drc"
}

# Deploy (or undeploy) the whole build in dependency order: shared library, admin
# EAR, service WARs, test EAR. Targets come from the profile (install.sh writes
# wls.targets.*). A connect failure (engine rc 3) aborts the batch at once.
do_all() {
    local action="$1" rc=0
    # status is a single listing of what's deployed — no dist, no per-tier loop.
    if [ "$action" = status ]; then
        wls_deploy_one status "" "" "" || rc=$?
        return "$rc"
    fi
    [ -n "$DIST_DIR" ] && [ -d "$DIST_DIR" ] || die "No dist/ build — run ./build.sh first (or pass --build VER)."
    local t_admin t_cluster t_both t_test
    t_admin="$(read_prop "$CONF_FILE" wls.targets.admin)";     t_admin="${t_admin:-AdminServer}"
    t_cluster="$(read_prop "$CONF_FILE" wls.targets.cluster)"; t_cluster="${t_cluster:-BEA_ENGINE_TIER_CLUST}"
    t_both="$(read_prop "$CONF_FILE" wls.targets.both)";       t_both="${t_both:-${t_admin},${t_cluster}}"
    t_test="$(read_prop "$CONF_FILE" wls.targets.test)";       t_test="${t_test:-$t_cluster}"

    _one() {  # <name> <file> <target> <islib> ; sets rc / returns 3 to abort
        local n="$1" f="$2" tg="$3" lib="$4" trc=0
        [ -f "$f" ] || { [ "$action" = deploy ] && warn "skip ${n}: not in this build (${f})."; return 0; }
        wls_deploy_one "$action" "$n" "$f" "$tg" "$lib" || trc=$?
        [ "$trc" -eq 3 ] && { warn "aborting — the AdminServer is unreachable (is it RUNNING? is the t3s trust in place?)."; return 3; }
        [ "$trc" -ne 0 ] && rc=1
        [ "$lib" = true ] && [ "$trc" -eq 0 ] && [ "$action" = deploy ] && warn "shared-library deploy needs a server restart before dependents resolve it."
        return 0
    }
    _services() {  # every WAR in dist/services/
        local war
        [ -d "${DIST_DIR}/services" ] || return 0
        shopt -s nullglob
        for war in "${DIST_DIR}/services"/*.war; do
            _one "$(basename "${war%.war}")" "$war" "$t_cluster" false || { shopt -u nullglob; return 3; }
        done
        shopt -u nullglob
        return 0
    }

    if [ "$action" = undeploy ]; then
        _one blade-test  "${DIST_DIR}/blade-test.ear"  "$t_test"  false || return 1
        _services || return 1
        _one blade-admin "${DIST_DIR}/blade-admin.ear" "$t_admin" false || return 1
        _one blade-shared "${DIST_DIR}/lib/blade-shared.war" "$t_both" true || return 1
    else
        _one blade-shared "${DIST_DIR}/lib/blade-shared.war" "$t_both" true || return 1
        _one blade-admin "${DIST_DIR}/blade-admin.ear" "$t_admin" false || return 1
        _services || return 1
        _one blade-test  "${DIST_DIR}/blade-test.ear"  "$t_test"  false || return 1
    fi
    return "$rc"
}

if [ "$DEPLOY_ALL" = true ]; then
    log "${C_BOLD}BLADE deploy — all${C_RESET}"
    log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
    log "  build:        ${DIST_DIR:-<none>}"
    [ "$NEEDS_WLS" = true ] && log "  WebLogic:     ${WLS_USER}@${WLS_ADMINURL}  (engine: ${ENGINE})"
    log "  action:       ${ACTION}"
    [ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
    log ""
    arc=0; do_all "$ACTION" || arc=$?
    log ""
    [ "$arc" -eq 0 ] || die "deploy --all (${ACTION}): finished with errors (exit ${arc})"
    ok "deploy --all (${ACTION}): done"
    exit 0
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
    # Tier-aware search (see find_in_dist): the flat dev tree stays out of prod releases.
    ART=$(find_in_dist "$FILE_ARG" | head -1)
    [ -n "$ART" ] || die "No artifact named '${FILE_ARG}' under ${DIST_DIR}. Available:
$(find_in_dist '*' | while read -r f; do basename "$f"; done | grep -E '\.(war|ear|jar)$' | sort | sed 's/^/  /')"
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
[ "$NEEDS_WLS" = true ] && log "  WebLogic:     ${WLS_USER}@${WLS_ADMINURL}  (engine: ${ENGINE})"
[ "$NEEDS_WLS" = true ] && [ "$ENGINE" = maven ] && log "  WLS plugin:   ${WLS_PLUGIN_VERSION} (${WLS_PLUGIN_VERSION_SOURCE})"
log "  action:       ${ACTION}"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

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
        islib=false; [ "$MODE" = "library" ] && islib=true
        wls_deploy_one "$ACTION" "$APP_NAME" "$ART" "$TARGET" "$islib" || rc=$?
        [ "$rc" = 3 ] && err "Could not connect to ${WLS_ADMINURL} — is the AdminServer running and the t3s trust in place?"
        [ "$MODE" = "library" ] && [ "$ACTION" = "deploy" ] && [ "$rc" = 0 ] && warn "Shared-library deploy needs a server restart to complete before dependents resolve it." ;;
esac

log ""
if [ $rc -eq 0 ]; then
    ok "${APP_NAME} ${ACTION}: done"
else
    die "${APP_NAME} ${ACTION}: failed (exit ${rc})"
fi
