#!/usr/bin/env bash
# ============================================================================
# build.sh - Profile-driven build wrapper for BLADE
#
# Usage:
#   ./build.sh [profile...] [platform] [--no-dist] [maven-args...]
#
# Examples:
#   ./build.sh                              # full build, auto-detected platform
#   ./build.sh production                   # production services, auto-detected
#   ./build.sh production occas-8.2         # production services, OCCAS 8.2
#   ./build.sh minimal occas-8.3            # core routing, OCCAS 8.3
#   ./build.sh production minimal           # two EARs: production + minimal
#   ./build.sh production clean package     # with explicit Maven goals
#   ./build.sh --no-dist                    # full build, skip dist/ copy
#   ./build.sh -- -Pjavadocs                # full build with extra Maven flags
#
# Module profiles:   build-profiles/*.conf
# Platform profiles: build-profiles/platforms/*.conf
#
# Default platform resolution (when none given on the command line):
#   1. $OCCAS env var → parse inventory/registry.xml for the active install
#   2. Exactly one OCCAS version bootstrapped in ~/.m2
#   3. Hardcoded fallback: occas-8.1
# The chosen source is shown in parentheses next to "Platform:" in the build
# header (e.g. "Platform: occas-8.3 ($OCCAS)").
#
# EAR naming:
#   Each profile produces its own EAR named vorpal-blade-services-<profile>.ear
#   e.g. vorpal-blade-services-production.ear, vorpal-blade-services-minimal.ear
#
# Dist management:
#   Every WAR/JAR built during the run is copied to dist/<ver>-<build>/
#   (plus the active build profile and platform conf files for traceability).
#   On failure: the current build's dist directory is deleted.
#
#   To skip the copy entirely (useful in local dev loops where you don't need
#   the dist/ folder rewritten on every build):
#     ./build.sh --no-dist                  # one-off
#     export BLADE_SKIP_DIST=1              # sticky for the current shell
#
#   --no-dist on the CLI overrides BLADE_SKIP_DIST=0 from the environment.
#
# EAR (currently disabled):
#   The services EAR is intentionally offline — see services/pom.xml. While
#   disabled, services are deployed individually as WARs (the dist/ folder
#   contains every WAR built). Re-enable via the TODO(EAR) markers in
#   services/pom.xml and this script.
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
PLATFORMS_DIR="${PROFILES_DIR}/platforms"
DEFAULT_PROFILE="default"

# --- Default platform resolution, in order:
#       1. $OCCAS env var → parse inventory/registry.xml for the active install
#          (same convention bootstrap.sh uses).
#       2. Exactly one OCCAS version bootstrapped in the local Maven repo.
#       3. Hardcoded fallback: occas-8.1.
#     User can always override on the command line: ./build.sh occas-8.3
#     If $OCCAS is unset and the user didn't pass a platform on the CLI, we
#     emit a warning further down (after argument parsing) so the user knows
#     we're guessing.
DEFAULT_PLATFORM="occas-8.1"
DEFAULT_PLATFORM_SOURCE="fallback"
OCCAS_WARNING=""

if [ -n "${OCCAS:-}" ]; then
    if [ -f "${OCCAS}/inventory/registry.xml" ]; then
        occas_v=$(grep -oE 'name="Converged Application Server" version="[0-9]+\.[0-9]+' \
                  "${OCCAS}/inventory/registry.xml" \
                  | grep -oE '[0-9]+\.[0-9]+$' | head -1)
        if [ -n "$occas_v" ] && [ -f "${PLATFORMS_DIR}/occas-${occas_v}.conf" ]; then
            DEFAULT_PLATFORM="occas-${occas_v}"
            DEFAULT_PLATFORM_SOURCE="\$OCCAS"
        else
            OCCAS_WARNING="\$OCCAS=${OCCAS} → registry.xml present but version '${occas_v:-?}' has no matching build-profiles/platforms/occas-*.conf"
        fi
    else
        OCCAS_WARNING="\$OCCAS=${OCCAS} → ${OCCAS}/inventory/registry.xml not found (is this a valid OCCAS install?)"
    fi
else
    OCCAS_WARNING="\$OCCAS environment variable is not set"
fi

if [ "$DEFAULT_PLATFORM_SOURCE" = "fallback" ]; then
    WLSS_DIR="${HOME}/.m2/repository/com/oracle/occas/wlss"
    if [ -d "$WLSS_DIR" ]; then
        bootstrapped=()
        for vdir in "$WLSS_DIR"/*/; do
            [ -d "$vdir" ] || continue
            v=$(basename "$vdir")
            [ -f "${vdir}wlss-${v}.jar" ] || continue
            [ -f "${PLATFORMS_DIR}/occas-${v}.conf" ] || continue
            bootstrapped+=("occas-${v}")
        done
        if [ ${#bootstrapped[@]} -eq 1 ]; then
            DEFAULT_PLATFORM="${bootstrapped[0]}"
            DEFAULT_PLATFORM_SOURCE="bootstrapped"
        fi
    fi
fi

# --- Parse project version from pom.xml ---
REVISION=$(grep '<revision>' "${SCRIPT_DIR}/pom.xml" | head -1 | sed 's/.*<revision>\(.*\)<\/revision>.*/\1/')

# --- Discover all available libs/admin/service/test modules from directory names ---
# All four categories are profile-activated in the parent pom.xml via !skip.<name>,
# so a module that's discovered here but not listed in the active build-profiles/*.conf
# will be excluded with -Dskip.<name>.
discover_modules() {
    for subdir in libs admin services test; do
        for dir in "${SCRIPT_DIR}/${subdir}"/*/; do
            local name=$(basename "$dir")
            # Skip always-built modules
            case "$name" in
                applications) continue ;;
            esac
            [ -f "$dir/pom.xml" ] && echo "$name"
        done
    done
}

# --- Read included modules from a profile conf file (skip comments, blanks, properties) ---
read_modules() {
    grep -v '^\s*#' "$1" | grep -v '^\s*$' | grep -v '=' | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//'
}

# --- Convert directory name to skip property name ---
# acl -> skip.acl
# test-b2bua -> skip.test-b2bua
dir_to_skip_prop() {
    echo "skip.$1"
}

# --- Compute skip flags for modules NOT in a given conf file ---
compute_skip_flags() {
    local conf="$1"
    local all_modules="$2"
    local included
    included=$(read_modules "$conf")
    while IFS= read -r dir; do
        if ! echo "$included" | grep -qx "$dir"; then
            local prop
            prop=$(dir_to_skip_prop "$dir")
            [ -n "$prop" ] && echo "-D${prop}"
        fi
    done <<< "$all_modules"
}

# --- Remove the current build's dist directory on failure ---
cleanup_failed_dist() {
    if [ -d "$DISTDIR" ]; then
        rm -rf "$DISTDIR"
        echo "Build failed — removed dist/${REVISION}-${BUILD_NUM}/"
    fi
}

# --- Locate a module's source directory (libs/<X>, admin/<X>, ...) ---
# Echoes the path relative to SCRIPT_DIR; empty string if not found.
module_dir() {
    local name="$1"
    for d in libs admin services test; do
        if [ -d "${SCRIPT_DIR}/${d}/${name}" ]; then
            echo "${d}/${name}"
            return
        fi
    done
}

# --- Copy every built WAR/JAR to dist/<ver>-<build>/ ---
# Iterates the active profile's INCLUDED_MODULES list (not a blind glob), so
# stale artifacts from previous builds in unrelated target/ directories don't
# leak into this build's dist. Excludes Maven side-artifacts (sources, javadoc,
# tests, the war-plugin's intermediate -classes.jar). Also copies the active
# build profile and platform conf files for traceability.
copy_all_to_dist() {
    [ "$SKIP_DIST" = true ] && return 0
    mkdir -p "$DISTDIR"
    local copied=0 missing=0
    local mod mdir target f produced
    while IFS= read -r mod; do
        [ -n "$mod" ] || continue
        mdir=$(module_dir "$mod")
        if [ -z "$mdir" ]; then
            echo "  warn: module '$mod' listed in ${PROFILE}.conf has no source directory"
            continue
        fi
        target="${SCRIPT_DIR}/${mdir}/target"
        [ -d "$target" ] || { missing=$((missing + 1)); continue; }
        produced=0
        for f in "$target"/*.war "$target"/*.jar; do
            [ -f "$f" ] || continue
            case "$(basename "$f")" in
                *-sources.jar|*-javadoc.jar|*-tests.jar|*-classes.jar) continue ;;
                original-*.jar) continue ;;
            esac
            cp -f "$f" "$DISTDIR/"
            copied=$((copied + 1))
            produced=$((produced + 1))
        done
        [ $produced -eq 0 ] && missing=$((missing + 1))
    done <<< "$INCLUDED_MODULES"

    cp -f "${PROFILES_DIR}/${PROFILE}.conf"   "$DISTDIR/" 2>/dev/null && copied=$((copied + 1))
    cp -f "${PLATFORMS_DIR}/${PLATFORM}.conf" "$DISTDIR/" 2>/dev/null && copied=$((copied + 1))

    echo "Copied ${copied} artifacts to dist/${REVISION}-${BUILD_NUM}/"
    if [ $missing -gt 0 ]; then
        echo "  (${missing} modules in ${PROFILE}.conf produced no artifact — first build, or build failure)"
    fi
}

# --- Write dist/<ver>-<build>/DEPLOYMENT.txt after a successful build ---
# Emits a four-column table (Artifact, Tier, Target, Purpose) describing every
# artifact actually present in DISTDIR. Used by operators and by ./deploy.sh.
write_deployment_manifest() {
    [ -d "$DISTDIR" ] || return 0
    local manifest="${DISTDIR}/DEPLOYMENT.txt"

    # Classify an artifact by filename. Echoes: "<tier>|<target>|<purpose>"
    # Specific names take precedence; broader glob patterns catch the rest.
    classify_artifact() {
        case "$1" in
            vorpal-blade-library-fsmar.jar)
                echo "fsmar|approuter/|SIP application router — v2 legacy (reboot engine tier)" ;;
            vorpal-blade-library-fsmar3.jar)
                echo "fsmar|approuter/|SIP application router — v3 (reboot engine tier)" ;;
            vorpal-blade-library-shared.war)
                echo "shared-lib|admin+cluster|WebLogic shared library (3rd-party JARs)" ;;
            vorpal-blade-library-framework.jar)
                echo "framework|bundled in WARs|BLADE framework library (not deployed directly)" ;;
            vorpal-blade-admin-console.war)
                echo "admin|AdminServer|Admin dashboard (context: /blade)" ;;
            vorpal-blade-admin-configurator.war)
                echo "admin|AdminServer|Config editor (context: /configurator)" ;;
            vorpal-blade-admin-flow.war)
                echo "admin|AdminServer|FSMAR diagram editor (context: /flow)" ;;
            vorpal-blade-admin-tuning.war)
                echo "admin|AdminServer|OCCAS/WebLogic tuning (context: /tuning)" ;;
            vorpal-blade-admin-file-manager.war)
                echo "admin|AdminServer|Config file manager (context: /files)" ;;
            vorpal-blade-admin-explorer.war)
                echo "admin|AdminServer|Experimental UI (context: /explorer)" ;;
            vorpal-blade-admin-watcher.war)
                echo "admin|AdminServer|Log/event monitor (context: /watcher)" ;;
            vorpal-blade-admin-logs.war)
                echo "admin|AdminServer|Log viewer (context: /logs)" ;;
            vorpal-blade-javadoc.war)
                echo "admin|AdminServer|Javadoc site (context: /javadoc)" ;;
            vorpal-blade-admin-*.war)
                echo "admin|AdminServer|Admin app: ${1#vorpal-blade-admin-}" ;;
            vorpal-blade-services-*.war)
                local svc="${1#vorpal-blade-services-}"; svc="${svc%.war}"
                echo "service|cluster|SIP service (deploy as standalone WAR while EAR is disabled): ${svc}" ;;
            test-*.war)
                echo "test|cluster|SIP test app: ${1%.war}" ;;
            *.conf)
                echo "metadata|n/a|Build profile / platform used for this build" ;;
            *)
                echo "unknown|?|${1}" ;;
        esac
    }

    {
        echo "BLADE ${REVISION}-${BUILD_NUM} deployment manifest"
        echo "See DEPLOYMENT.md for the four-tier deployment model."
        echo ""
        printf '%-42s  %-11s  %-15s  %s\n' "Artifact" "Tier" "Target" "Purpose"
        printf '%-42s  %-11s  %-15s  %s\n' "------------------------------------------" \
            "-----------" "---------------" "-------"
        local files=()
        while IFS= read -r f; do
            files+=("$f")
        done < <(cd "$DISTDIR" && ls -1 2>/dev/null | grep -v '^DEPLOYMENT\.txt$' | sort)
        for f in "${files[@]}"; do
            local line tier target purpose
            line=$(classify_artifact "$f")
            tier="${line%%|*}";       line="${line#*|}"
            target="${line%%|*}";     line="${line#*|}"
            purpose="$line"
            printf '%-42s  %-11s  %-15s  %s\n' "$f" "$tier" "$target" "$purpose"
        done
    } > "$manifest"

    echo "Wrote ${manifest#${SCRIPT_DIR}/}"
}

# --- Zip previous dist directories (not the current build) ---
# zip_previous_dist() {
#     local dist_parent="${SCRIPT_DIR}/dist"
#     [ -d "$dist_parent" ] || return 0
#     for dir in "$dist_parent"/*/; do
#         [ -d "$dir" ] || continue
#         local base=$(basename "$dir")
#         # Skip the current build's directory
#         [ "$base" = "${REVISION}-${BUILD_NUM}" ] && continue
#         (cd "$dist_parent" && zip -qr "${base}.zip" "$base" && rm -rf "$base")
#         echo "Zipped dist/${base}.zip"
#     done
# }

# --- Parse arguments: collect multiple profiles, one platform, and Maven args ---
PROFILES=()
PLATFORM=""
MAVEN_ARGS=()

# Sticky dev-mode switch: BLADE_SKIP_DIST=1 in the env disables dist copying.
# The CLI flag --no-dist always wins (for one-off explicit override).
SKIP_DIST=false
case "${BLADE_SKIP_DIST:-}" in
    1|true|yes|on) SKIP_DIST=true ;;
esac

for arg in "$@"; do
    if [ "$arg" = "--" ]; then
        continue
    elif [ "$arg" = "--no-dist" ]; then
        SKIP_DIST=true
    elif [[ "$arg" == -* ]]; then
        MAVEN_ARGS+=("$arg")
    elif [ -f "${PROFILES_DIR}/${arg}.conf" ]; then
        PROFILES+=("$arg")
    elif [ -z "$PLATFORM" ] && [ -f "${PLATFORMS_DIR}/${arg}.conf" ]; then
        PLATFORM="$arg"
    else
        MAVEN_ARGS+=("$arg")
    fi
done

# Note: the old -Dblade.skip.dist flag (read by services/pom.xml's copy-dist
# exec step) is no longer passed — that exec step is commented out along with
# the EAR. The dist copy is now done entirely from build.sh, gated by SKIP_DIST.

if [ ${#PROFILES[@]} -eq 0 ]; then
    PROFILES=("$DEFAULT_PROFILE")
fi
if [ -z "$PLATFORM" ]; then
    PLATFORM="$DEFAULT_PLATFORM"
    PLATFORM_SOURCE="$DEFAULT_PLATFORM_SOURCE"
else
    PLATFORM_SOURCE="cli"
fi

# --- $OCCAS warning ---
# Print only when we fell back to autodetection and $OCCAS didn't resolve.
# If the user passed a platform on the CLI they made an explicit choice — stay quiet.
# If $OCCAS resolved cleanly there's nothing to warn about either.
if [ "$PLATFORM_SOURCE" != "cli" ] && [ "$DEFAULT_PLATFORM_SOURCE" != "\$OCCAS" ] && [ -n "$OCCAS_WARNING" ]; then
    echo "WARNING: ${OCCAS_WARNING}"
    echo "         Falling back to ${PLATFORM} (${PLATFORM_SOURCE})."
    echo "         To silence this and pin the platform automatically, add to your shell rc:"
    echo "             export OCCAS=/path/to/your/occas/install"
    echo "         build.sh will then read inventory/registry.xml to pick the matching platform."
    echo ""
fi

# --- Validate platform ---
PLATFORM_FILE="${PLATFORMS_DIR}/${PLATFORM}.conf"
if [ ! -f "$PLATFORM_FILE" ]; then
    echo "Error: Platform '${PLATFORM}' not found."
    echo "Available platforms:"
    for f in "${PLATFORMS_DIR}"/*.conf; do
        echo "  $(basename "${f%.conf}")"
    done
    exit 1
fi

# --- Read properties from the platform ---
JAVA_VERSION=$(grep '^java\.version=' "$PLATFORM_FILE" | head -1 | cut -d= -f2 | tr -d '[:space:]')
WL_VERSION=$(grep '^weblogic\.version=' "$PLATFORM_FILE" | head -1 | cut -d= -f2 | tr -d '[:space:]')
OCCAS_VERSION=$(grep '^occas\.version=' "$PLATFORM_FILE" | head -1 | cut -d= -f2 | tr -d '[:space:]')

PLATFORM_FLAGS=()
if [ -n "$JAVA_VERSION" ]; then
    PLATFORM_FLAGS+=("-Dblade.java.version=${JAVA_VERSION}")
fi
if [ -n "$WL_VERSION" ]; then
    PLATFORM_FLAGS+=("-Dblade.weblogic.version=${WL_VERSION}")
fi
if [ -n "$OCCAS_VERSION" ]; then
    PLATFORM_FLAGS+=("-Dblade.occas.version=${OCCAS_VERSION}")
fi

# --- Verify OCCAS/WebLogic libraries are installed in the local Maven repo ---
M2_REPO="${HOME}/.m2/repository"
WLS_JAR="${M2_REPO}/com/oracle/weblogic/weblogic-server/${WL_VERSION}/weblogic-server-${WL_VERSION}.jar"
WLSS_JAR="${M2_REPO}/com/oracle/occas/wlss/${OCCAS_VERSION}/wlss-${OCCAS_VERSION}.jar"
missing_libs=()
[ -f "$WLS_JAR" ]  || missing_libs+=("$WLS_JAR")
[ -f "$WLSS_JAR" ] || missing_libs+=("$WLSS_JAR")
if [ ${#missing_libs[@]} -gt 0 ]; then
    echo "Error: OCCAS/WebLogic libraries not found in local Maven repo for platform ${PLATFORM}:"
    printf '  %s\n' "${missing_libs[@]}"
    echo ""
    echo "Run ./bootstrap.sh /path/to/occas first to install them."
    exit 1
fi

# --- Auto-increment build number ---
BUILD_NUMBER_FILE="${SCRIPT_DIR}/build.number"
if [ -f "$BUILD_NUMBER_FILE" ]; then
    BUILD_NUM=$(grep '^build.number=' "$BUILD_NUMBER_FILE" | cut -d= -f2 | tr -d '[:space:]')
else
    BUILD_NUM=0
fi
BUILD_NUM=$((BUILD_NUM + 1))
cat > "$BUILD_NUMBER_FILE" <<EOB
#Build Number for Maven. Do not edit!
build.number=${BUILD_NUM}
EOB

# --- Dist directory for this build ---
DISTDIR="${SCRIPT_DIR}/dist/${REVISION}-${BUILD_NUM}"

# --- Always install ---
# Downstream repos (optum, connect) resolve BLADE artifacts via Maven version
# ranges (e.g. [1.0.0,)) against the local .m2 repository. If we don't install
# here, those builds silently resolve to whatever older BLADE was installed
# previously. So even when the user passes explicit goals like `clean package`,
# we append `install` so the framework JAR (and other BLADE artifacts) land in
# .m2. All non-framework modules have maven-install-plugin skipped, so this is
# cheap.
HAS_GOALS=false
HAS_INSTALL=false
MAVEN_GOALS=()
MAVEN_FLAGS=()
for arg in "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}"; do
    if [[ "$arg" == -* ]]; then
        MAVEN_FLAGS+=("$arg")
    else
        MAVEN_GOALS+=("$arg")
        HAS_GOALS=true
        case "$arg" in install|deploy) HAS_INSTALL=true ;; esac
    fi
done
if [ "$HAS_GOALS" = false ]; then
    MAVEN_GOALS=("install")
elif [ "$HAS_INSTALL" = false ]; then
    MAVEN_GOALS+=("install")
fi

ALL_MODULES=$(discover_modules)
TOTAL_COUNT=$(echo "$ALL_MODULES" | wc -l | tr -d ' ')

# --- Multi-profile builds were only meaningful for per-profile EARs.
#     With the EAR disabled, refuse them up front. ---
if [ ${#PROFILES[@]} -gt 1 ]; then
    echo "Error: multiple profiles (${PROFILES[*]}) only made sense when each one"
    echo "       produced its own EAR. The EAR logic is currently disabled — see"
    echo "       services/pom.xml. Pick a single profile and run again."
    exit 1
fi

PROFILE="${PROFILES[0]}"
CONF_FILE="${PROFILES_DIR}/${PROFILE}.conf"
INCLUDED_MODULES=$(read_modules "$CONF_FILE")

SKIP_FLAGS=()
while IFS= read -r flag; do
    [ -n "$flag" ] && SKIP_FLAGS+=("$flag")
done < <(compute_skip_flags "$CONF_FILE" "$ALL_MODULES")

INCLUDED_COUNT=$(echo "$INCLUDED_MODULES" | wc -l | tr -d ' ')

echo "Build profile: ${PROFILE}"
echo "Platform: ${PLATFORM} (${PLATFORM_SOURCE})"
echo "Build number: ${BUILD_NUM}"
echo "Java version: ${JAVA_VERSION:-11 (default)}"
echo "WebLogic:     ${WL_VERSION:-14.1.1 (default)}"
echo "OCCAS:        ${OCCAS_VERSION:-8.1 (default)}"
echo "Modules: ${INCLUDED_COUNT} of ${TOTAL_COUNT}"
if [ "$SKIP_DIST" = true ]; then
    echo "Dist:    SKIPPED (--no-dist or BLADE_SKIP_DIST set)"
else
    echo "Dist:    dist/${REVISION}-${BUILD_NUM}/"
fi

if [ "${#SKIP_FLAGS[@]}" -gt 0 ]; then
    EXCLUDED=$(printf '%s\n' "${SKIP_FLAGS[@]}" | sed 's/-Dskip\.//' | tr '\n' ' ')
    echo "Excluding: ${EXCLUDED}"
fi
echo ""

set +e
"${SCRIPT_DIR}/mvnw" \
    "${MAVEN_GOALS[@]}" \
    "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}" \
    "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" \
    "${PLATFORM_FLAGS[@]+"${PLATFORM_FLAGS[@]}"}" \
    "-Dbuild.number=${BUILD_NUM}" \
    "-Dblade.skip.install=false"
MVN_EXIT=$?
set -e

if [ $MVN_EXIT -ne 0 ]; then
    [ "$SKIP_DIST" = true ] || cleanup_failed_dist
    exit $MVN_EXIT
fi

if [ "$SKIP_DIST" != true ]; then
    copy_all_to_dist
    write_deployment_manifest
fi

# =============================================================================
# TODO(EAR): multi-profile / per-profile-EAR branch removed. The original logic
# ran two Maven phases (build modules, then build one EAR per profile) and was
# the only consumer of -Dear.profile and -Dbuild.platform. Recover from git
# history when re-enabling the EAR. Look for the block under
# `if [ ${#PROFILES[@]} -eq 1 ]; then ... else ... fi` in the previous version
# of this file.
# =============================================================================
