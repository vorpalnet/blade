#!/usr/bin/env bash
# ============================================================================
# deploy.sh - One env file drives the whole BLADE deploy
#
# Usage:
#   ./deploy.sh <env>                      Deploy the WHOLE environment, in order
#   ./deploy.sh <env> <tier> [action]      Deploy/undeploy/status a single tier
#   ./deploy.sh <env> [action]             Whole environment, given action
#   ... [--build VER] [--dry-run]
#
# <env> is either:
#   a NAME    → build-profiles/deploy/<name>.conf  (+ paired <name>.secret)
#   a PATH    → that file directly (secret = same path with .secret)
# The conf is the single source of truth: connection, targets, engine nodes,
# approuter path, and which apps to deploy. Env confs are gitignored (per-site
# hostnames/IPs); copy production.conf.example to <env>.conf and edit.
#
# Tiers (omit the tier to do them ALL, in dependency-safe order):
#   shared       WebLogic shared library → wls.targets.both
#   fsmar        FSMAR fat JAR → each engine node's approuter/ (engine.nodes)
#   admin        blade-admin.ear → wls.targets.admin
#   services     service + test WARs → wls.targets.cluster
#                (narrow with deploy.services in the conf; default = all built)
#
# The WebLogic target is read from the conf per tier — you no longer pass it.
# Whole-env order:  deploy = shared→fsmar→admin→services
#                   undeploy = services→admin→fsmar→shared (library last)
#
# Actions:
#   deploy       (default) push artifacts
#   undeploy     tear down the matching apps
#   status       query deployment state
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
#   ./deploy.sh production                          # the whole environment
#   ./deploy.sh production services                 # just the service WARs
#   ./deploy.sh production admin undeploy
#   ./deploy.sh production --dry-run
#   ./deploy.sh production services --build 2.9.5-320
#   ./deploy.sh ./build-profiles/deploy/work.conf   # env given as a file path
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
DEPLOY_DIR="${PROFILES_DIR}/deploy"

# The WebLogic Maven plugin coordinate. The VERSION must match the running
# server's WebLogic version (OCCAS 8.1 → 14.1.1, OCCAS 8.2/8.3 → 14.1.2); a
# mismatched plugin won't resolve from ~/.m2, since bootstrap.sh installs only
# the server's own version. Resolved once DIST_DIR is known, in this order:
#   1. wls.plugin.version in the deploy conf      (explicit override)
#   2. weblogic.version from the platform conf build.sh copied into dist/
#   3. WLS_PLUGIN_VERSION_DEFAULT below           (last-resort fallback + warn)
WLS_PLUGIN_ARTIFACT="com.oracle.weblogic:weblogic-maven-plugin"
WLS_PLUGIN_VERSION_DEFAULT="14.1.1"
WLS_PLUGIN=""   # set once DIST_DIR is known (see resolution block below)

# Whole-environment tier order. Undeploy walks it in reverse (library last).
ALL_TIERS=(shared fsmar admin services)

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
    sed -n '2,49p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

# --- Parse args ---
# Positional: <env> [<tier>] [<action>] in any order after <env>.
# tier and action are closed sets, so we classify each remaining token by value
# rather than by position. No <target> anymore — it's derived from the conf.
ENV_ARG=""
TIER=""             # empty ⇒ whole environment
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

[ ${#POSITIONAL[@]} -ge 1 ] || { err "Environment name or file required."; show_usage 1; }
ENV_ARG="${POSITIONAL[0]}"

for i in "${!POSITIONAL[@]}"; do
    [ "$i" -eq 0 ] && continue
    case "${POSITIONAL[$i]}" in
        deploy|undeploy|status)         ACTION="${POSITIONAL[$i]}" ;;
        shared|fsmar|admin|services)    TIER="${POSITIONAL[$i]}" ;;
        *) die "Unknown argument: '${POSITIONAL[$i]}'. Expected a tier (shared|fsmar|admin|services) or action (deploy|undeploy|status)." ;;
    esac
done

# --- Resolve <env> to a conf file (path or name) ---
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"
    ENV_NAME="$(basename "${ENV_ARG%.conf}")"
    SECRET_FILE="${ENV_ARG%.conf}.secret"
else
    ENV_NAME="$ENV_ARG"
    CONF_FILE="${DEPLOY_DIR}/${ENV_NAME}.conf"
    SECRET_FILE="${DEPLOY_DIR}/${ENV_NAME}.secret"
fi

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
    # `|| true`: a missing key is normal (optional props) — don't let grep's
    # non-zero exit trip `set -o pipefail` + `set -e` and abort the script.
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

BUILD_PROFILE=$(read_prop  "$CONF_FILE" "build.profile")
WLS_ADMINURL=$(read_prop   "$CONF_FILE" "wls.adminurl")
WLS_TRUSTSTORE=$(read_prop "$CONF_FILE" "wls.truststore"); WLS_TRUSTSTORE="${WLS_TRUSTSTORE/#\~/$HOME}"
WLS_TRUSTSTORE_TYPE=$(read_prop "$CONF_FILE" "wls.truststore.type"); WLS_TRUSTSTORE_TYPE="${WLS_TRUSTSTORE_TYPE:-PKCS12}"
WLS_USER=$(read_prop       "$CONF_FILE" "wls.user")
WLS_TGT_ADMIN=$(read_prop  "$CONF_FILE" "wls.targets.admin")
WLS_TGT_CLUSTER=$(read_prop "$CONF_FILE" "wls.targets.cluster")
WLS_TGT_BOTH=$(read_prop   "$CONF_FILE" "wls.targets.both")
SSH_HOST=$(read_prop       "$CONF_FILE" "ssh.host")
SSH_USER=$(read_prop       "$CONF_FILE" "ssh.user")
APPROUTER_DIR=$(read_prop  "$CONF_FILE" "approuter.dir")
ENGINE_NODES_RAW=$(read_prop "$CONF_FILE" "engine.nodes")
DEPLOY_SERVICES=$(read_prop "$CONF_FILE" "deploy.services")
WLS_PLUGIN_VERSION=$(read_prop "$CONF_FILE" "wls.plugin.version")
SHARED_FS=$(read_prop      "$CONF_FILE" "shared.filesystem")

[ -n "$BUILD_PROFILE" ] || die "${CONF_FILE}: missing build.profile"

# --- Which tiers are we acting on? (single tier, or all) ---
if [ -n "$TIER" ]; then
    TIERS=("$TIER")
else
    TIERS=("${ALL_TIERS[@]}")
    # Undeploy tears down in reverse so the shared library (which everything
    # references) goes last.
    if [ "$ACTION" = "undeploy" ]; then
        TIERS=(services admin fsmar shared)
    fi
fi

# Does any selected tier talk to WebLogic? (fsmar does not.)
NEEDS_WLS=false
NEEDS_FSMAR=false
for t in "${TIERS[@]}"; do
    case "$t" in
        shared|admin|services) NEEDS_WLS=true ;;
        fsmar)                 NEEDS_FSMAR=true ;;
    esac
done

# Resolve & validate the WebLogic target for a tier.
target_for_tier() {
    case "$1" in
        shared)   echo "$WLS_TGT_BOTH" ;;
        admin)    echo "$WLS_TGT_ADMIN" ;;
        services) echo "$WLS_TGT_CLUSTER" ;;
    esac
}

# --- Per-tier config validation (only for the tiers we'll touch) ---
if [ "$NEEDS_WLS" = true ]; then
    [ -n "$WLS_ADMINURL" ] || die "${CONF_FILE}: missing wls.adminurl"
    [ -n "$WLS_USER" ]     || die "${CONF_FILE}: missing wls.user"
fi
for t in "${TIERS[@]}"; do
    case "$t" in
        shared)   [ -n "$WLS_TGT_BOTH" ]    || die "${CONF_FILE}: missing wls.targets.both (required for 'shared')" ;;
        admin)    [ -n "$WLS_TGT_ADMIN" ]   || die "${CONF_FILE}: missing wls.targets.admin (required for 'admin')" ;;
        services) [ -n "$WLS_TGT_CLUSTER" ] || die "${CONF_FILE}: missing wls.targets.cluster (required for 'services')" ;;
        fsmar)    [ -n "$ENGINE_NODES_RAW" ] || [ -n "$SSH_HOST" ] || [ -n "$APPROUTER_DIR" ] \
                      || die "${CONF_FILE}: 'fsmar' needs engine.nodes (preferred), or ssh.host, or approuter.dir" ;;
    esac
done

# --- Engine node list for the fsmar tier (CSV → array; fallback to ssh.host) ---
ENGINE_NODES=()
if [ -n "$ENGINE_NODES_RAW" ]; then
    IFS=', ' read -r -a ENGINE_NODES <<< "$ENGINE_NODES_RAW"
elif [ -n "$SSH_HOST" ]; then
    ENGINE_NODES=("$SSH_HOST")
fi

# --- Service allowlist (CSV; empty or '*' = all) ---
SERVICE_ALLOW=()
SERVICE_ALLOW_ALL=true
if [ -n "$DEPLOY_SERVICES" ] && [ "$DEPLOY_SERVICES" != "*" ]; then
    SERVICE_ALLOW_ALL=false
    IFS=', ' read -r -a SERVICE_ALLOW <<< "$DEPLOY_SERVICES"
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
# Only needed when a WebLogic tier is involved; fsmar-only runs skip it.
WLS_PASSWORD=""
if [ "$NEEDS_WLS" = true ]; then
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

# --- t3s: SSL trust for the WebLogic Maven plugin's Deployer JVM ---
# A t3s:// admin URL needs the AdminServer's CA trusted. Two ways:
#   1. wls.truststore=<trust.p12 from certs.sh>  (+ wls.truststore.password in
#      the secret, else $BLADE_STORE_PASSWORD) — explicit CustomTrust.
#   2. No wls.truststore — rely on the JVM default truststore (works when the
#      CA was imported via `keytool -importcert -cacerts`, certs.sh step 2).
case "$WLS_ADMINURL" in
    t3s://*)
        if [ -n "$WLS_TRUSTSTORE" ]; then
            [ -f "$WLS_TRUSTSTORE" ] || die "wls.truststore not found: ${WLS_TRUSTSTORE}"
            TRUST_PW="${BLADE_STORE_PASSWORD:-}"
            [ -z "$TRUST_PW" ] && [ -f "$SECRET_FILE" ] && TRUST_PW=$(read_prop "$SECRET_FILE" "wls.truststore.password")
            export MAVEN_OPTS="${MAVEN_OPTS:-} -Dweblogic.security.TrustKeyStore=CustomTrust \
-Dweblogic.security.CustomTrustKeyStoreFileName=${WLS_TRUSTSTORE} \
-Dweblogic.security.CustomTrustKeyStoreType=${WLS_TRUSTSTORE_TYPE}${TRUST_PW:+ -Dweblogic.security.CustomTrustKeyStorePassPhrase=${TRUST_PW}}"
        else
            warn "t3s admin URL with no wls.truststore — relying on the JVM default truststore (CA must be in cacerts)."
        fi
        ;;
esac

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

# --- Resolve the WebLogic Maven plugin version (see WLS_PLUGIN_ARTIFACT above) ---
# Priority: conf override > platform conf stamped into dist/ > default fallback.
WLS_PLUGIN_VERSION_SOURCE=""
if [ -n "$WLS_PLUGIN_VERSION" ]; then
    WLS_PLUGIN_VERSION_SOURCE="wls.plugin.version in ${ENV_NAME}.conf"
else
    # build.sh copies the active platform conf (occas-X.Y.conf) into the dist
    # dir for traceability; its weblogic.version is what this build targets.
    plat_conf=$(ls -1 "$DIST_DIR"/occas-*.conf 2>/dev/null | head -1)
    if [ -n "$plat_conf" ]; then
        WLS_PLUGIN_VERSION=$(read_prop "$plat_conf" "weblogic.version")
        [ -n "$WLS_PLUGIN_VERSION" ] && WLS_PLUGIN_VERSION_SOURCE="$(basename "$plat_conf")"
    fi
fi
if [ -z "$WLS_PLUGIN_VERSION" ]; then
    WLS_PLUGIN_VERSION="$WLS_PLUGIN_VERSION_DEFAULT"
    WLS_PLUGIN_VERSION_SOURCE="fallback default"
    warn "Could not determine WebLogic version from dist/ or conf; defaulting"
    warn "weblogic-maven-plugin to ${WLS_PLUGIN_VERSION}. If deploys fail to resolve"
    warn "the plugin, set wls.plugin.version in ${CONF_FILE}."
fi
WLS_PLUGIN="${WLS_PLUGIN_ARTIFACT}:${WLS_PLUGIN_VERSION}"

SHARED_LIB_WAR="${DIST_DIR}/blade-shared.war"
FSMAR_JAR="${DIST_DIR}/blade-fsmar.jar"
SHARED_LIB_NAME="blade-shared"  # Extension-Name from libs/shared/pom.xml

# --- Header ---
log "${C_BOLD}BLADE deploy${C_RESET}"
log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
log "  build:        ${DIST_NAME} (${BUILD_PROFILE})"
if [ -n "$TIER" ]; then
    log "  tier:         ${TIER}"
else
    log "  tiers:        ${TIERS[*]}  (whole environment)"
fi
[ "$NEEDS_WLS" = true ]   && log "  WebLogic:     ${WLS_USER}@${WLS_ADMINURL}"
[ "$NEEDS_WLS" = true ]   && log "  WLS plugin:   ${WLS_PLUGIN_VERSION} (${WLS_PLUGIN_VERSION_SOURCE})"
if [ "$NEEDS_FSMAR" = true ]; then
    if [ ${#ENGINE_NODES[@]} -gt 0 ]; then
        log "  engine nodes: ${ENGINE_NODES[*]} → ${APPROUTER_DIR}"
    else
        log "  approuter:    (local) ${APPROUTER_DIR}"
    fi
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

# Deploy / undeploy every deployable for a WebLogic tier (admin|services) to its
# conf-derived target. App name = artifact basename without extension
# (blade-configurator.war → "configurator", blade-admin.ear → "blade-admin").
# The admin tier's deploy unit is its EAR (blade-admin.ear, at the dist ROOT);
# the services tier has no EAR (OCCAS 8.3 can't show EAR contents), so it deploys
# the loose WARs from dist/services/, optionally narrowed by deploy.services.
deploy_subdir() {
    local sub="$1" action="$2"
    local target; target=$(target_for_tier "$sub")

    info "Tier: ${sub} → ${target}"
    local tier_ear="${DIST_DIR}/blade-${sub}.ear"
    local wars
    if [ -f "$tier_ear" ]; then
        wars=("$tier_ear")
    else
        local src_dir="${DIST_DIR}/${sub}"
        [ -d "$src_dir" ] || die "Source dir not found: ${src_dir}. Run ./build.sh ${BUILD_PROFILE} first."
        shopt -s nullglob
        wars=("$src_dir"/*.war)
        shopt -u nullglob

        # Apply the service allowlist (services tier only — admin is one EAR).
        if [ "$sub" = "services" ] && [ "$SERVICE_ALLOW_ALL" = false ]; then
            local present=() filtered=() w app a kept
            for w in "${wars[@]}"; do present+=("$(basename "${w%.war}")"); done
            for w in "${wars[@]}"; do
                app=$(basename "${w%.war}")
                for a in "${SERVICE_ALLOW[@]}"; do
                    [ "$app" = "$a" ] && { filtered+=("$w"); break; }
                done
            done
            # Warn about any requested app that isn't in this dist.
            for a in "${SERVICE_ALLOW[@]}"; do
                kept=false
                for app in "${present[@]}"; do [ "$app" = "$a" ] && { kept=true; break; }; done
                [ "$kept" = false ] && warn "deploy.services lists '${a}', not present in ${src_dir}"
            done
            wars=("${filtered[@]}")
            info "Allowlist: ${SERVICE_ALLOW[*]}"
        fi
    fi

    if [ ${#wars[@]} -eq 0 ]; then
        warn "No deployables found for ${sub}. Nothing to ${action}."
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
                :  # single list-apps below covers the whole server
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
    info "Tier: shared → ${WLS_TGT_BOTH}"
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

# Copy / remove the FSMAR fat JAR to each engine node's approuter/ directory.
# The jar is loaded by the WLSS Application Router at boot (not a WebLogic
# deployment). engine.nodes lists every engine host; with no nodes — or when
# shared.filesystem=true (approuter.dir is on a filesystem every node mounts) —
# we do a single local cp into approuter.dir instead of an scp per node.
deploy_fsmar() {
    local action="$1"
    [ -f "$FSMAR_JAR" ] || die "Missing: ${FSMAR_JAR}"
    [ -n "$APPROUTER_DIR" ] || die "${CONF_FILE}: missing approuter.dir (required for 'fsmar')"
    local jar_name; jar_name=$(basename "$FSMAR_JAR")
    local dest_file="${APPROUTER_DIR}/${jar_name}"
    local rc=0

    if [ "$SHARED_FS" = "true" ] || [ ${#ENGINE_NODES[@]} -eq 0 ]; then
        # Shared filesystem (or no ssh targets): one local cp reaches every node.
        [ "$SHARED_FS" = "true" ] && info "Tier: fsmar → (shared filesystem, single copy) ${dest_file}" \
                                  || info "Tier: fsmar → (local) ${dest_file}"
        case "$action" in
            deploy)
                if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] cp ${FSMAR_JAR} ${dest_file}${C_RESET}"
                else [ -d "$APPROUTER_DIR" ] || die "approuter.dir does not exist: ${APPROUTER_DIR}"; cp "$FSMAR_JAR" "$dest_file"; fi ;;
            undeploy)
                if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] rm -f ${dest_file}${C_RESET}"
                else rm -f "$dest_file"; fi ;;
            status)
                if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] ls -l ${dest_file}${C_RESET}"
                else ls -l "$dest_file" 2>&1 || warn "${jar_name} not present at ${dest_file}"; fi ;;
        esac
    else
        [ -n "$SSH_USER" ] || die "${CONF_FILE}: engine.nodes set but ssh.user missing"
        local node sshdest
        for node in "${ENGINE_NODES[@]}"; do
            sshdest="${SSH_USER}@${node}"
            info "Tier: fsmar → ${sshdest}:${dest_file}"
            case "$action" in
                deploy)
                    if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] scp ${FSMAR_JAR} ${sshdest}:${dest_file}${C_RESET}"
                    else scp "$FSMAR_JAR" "${sshdest}:${dest_file}" || rc=$?; fi ;;
                undeploy)
                    if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] ssh ${sshdest} rm -f ${dest_file}${C_RESET}"
                    else ssh "$sshdest" rm -f "$dest_file" || rc=$?; fi ;;
                status)
                    if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] ssh ${sshdest} ls -l ${dest_file}${C_RESET}"
                    else ssh "$sshdest" ls -l "$dest_file" 2>&1 || warn "${jar_name} not present on ${node}"; fi ;;
            esac
        done
    fi

    case "$action" in
        deploy)   warn "Engine tier reboot required for FSMAR changes to take effect." ;;
        undeploy) warn "Engine tier reboot required for FSMAR removal to take effect." ;;
    esac
    return $rc
}

run_tier() {
    case "$1" in
        admin|services) deploy_subdir "$1" "$ACTION" ;;
        shared)         deploy_shared "$ACTION" ;;
        fsmar)          deploy_fsmar  "$ACTION" ;;
    esac
}

# --- Dispatch: run each selected tier in order. On deploy, abort the rest if a
#     tier fails (don't deploy WARs whose shared library failed). ---
set +e
RC=0
for t in "${TIERS[@]}"; do
    run_tier "$t"
    tier_rc=$?
    if [ $tier_rc -ne 0 ]; then
        RC=$tier_rc
        if [ "$ACTION" = "deploy" ] && [ ${#TIERS[@]} -gt 1 ]; then
            err "Tier '${t}' failed (exit ${tier_rc}); aborting remaining tiers."
            break
        fi
    fi
    [ ${#TIERS[@]} -gt 1 ] && log ""
done
set -e

log ""
if [ $RC -eq 0 ]; then
    ok "${TIER:-environment} ${ACTION}: done"
else
    err "${TIER:-environment} ${ACTION}: failed (exit ${RC})"
    exit $RC
fi
