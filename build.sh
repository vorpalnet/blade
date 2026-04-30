#!/usr/bin/env bash
# ============================================================================
# build.sh - Profile-driven build wrapper for BLADE
#
# Usage:
#   ./build.sh [profile...] [platform] [--no-dist] [maven-args...]
#
# Examples:
#   ./build.sh                              # full build, OCCAS 8.1
#   ./build.sh production                   # production services, OCCAS 8.1
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
# EAR naming:
#   Each profile produces its own EAR named vorpal-blade-services-<profile>.ear
#   e.g. vorpal-blade-services-production.ear, vorpal-blade-services-minimal.ear
#
# Dist management:
#   On failure: the current build's dist directory is deleted.
#   --no-dist: skip copying artifacts to dist/<ver>-<build>/ entirely
#              (also skips DEPLOYMENT.txt). Useful for local dev loops.
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
PLATFORMS_DIR="${PROFILES_DIR}/platforms"
DEFAULT_PROFILE="full"

# --- Default platform: if exactly one OCCAS version is bootstrapped in the
#     local Maven repo, use it. Otherwise (zero or multiple) fall back to 8.1. ---
DEFAULT_PLATFORM="occas-8.1"
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
    fi
fi

# --- Parse project version from pom.xml ---
REVISION=$(grep '<revision>' "${SCRIPT_DIR}/pom.xml" | head -1 | sed 's/.*<revision>\(.*\)<\/revision>.*/\1/')

# --- Discover all available admin/service/test modules from directory names ---
discover_modules() {
    for subdir in admin services test; do
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

# --- Write dist/<ver>-<build>/DEPLOYMENT.txt after a successful build ---
# Emits a four-column table (Artifact, Tier, Target, Purpose) describing every
# artifact actually present in DISTDIR. Used by operators and by ./deploy.sh.
write_deployment_manifest() {
    [ -d "$DISTDIR" ] || return 0
    local manifest="${DISTDIR}/DEPLOYMENT.txt"

    # Classify an artifact by filename. Echoes: "<tier>|<target>|<purpose>"
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
            vorpal-blade-admin-dev-console.war)
                echo "admin|AdminServer|(legacy dev-console — removed in 2.9.5)" ;;
            vorpal-blade-javadoc.war)
                echo "admin|AdminServer|Javadoc site (context: /javadoc)" ;;
            vorpal-blade-services-*.ear)
                local profile="${1#vorpal-blade-services-}"
                profile="${profile%.ear}"
                echo "services|cluster|Services EAR (profile: ${profile})" ;;
            *.conf)
                echo "metadata|n/a|Build profile used for this build" ;;
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
SKIP_DIST=false

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

# --- Dist flag: passed to Maven so services/pom.xml skips its copy-dist
# exec step, and also gates the DEPLOYMENT.txt writer below.
DIST_FLAGS=()
if [ "$SKIP_DIST" = true ]; then
    DIST_FLAGS+=("-Dblade.skip.dist=true")
fi

if [ ${#PROFILES[@]} -eq 0 ]; then
    PROFILES=("$DEFAULT_PROFILE")
fi
PLATFORM="${PLATFORM:-$DEFAULT_PLATFORM}"

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

if [ ${#PROFILES[@]} -eq 1 ]; then
    # =====================================================================
    # Single profile: run Maven once (same as before, plus ear.profile)
    # =====================================================================
    PROFILE="${PROFILES[0]}"
    CONF_FILE="${PROFILES_DIR}/${PROFILE}.conf"
    INCLUDED_MODULES=$(read_modules "$CONF_FILE")

    SKIP_FLAGS=()
    while IFS= read -r flag; do
        [ -n "$flag" ] && SKIP_FLAGS+=("$flag")
    done < <(compute_skip_flags "$CONF_FILE" "$ALL_MODULES")

    INCLUDED_COUNT=$(echo "$INCLUDED_MODULES" | wc -l | tr -d ' ')

    echo "Build profile: ${PROFILE}"
    echo "Platform: ${PLATFORM}"
    echo "Build number: ${BUILD_NUM}"
    echo "Java version: ${JAVA_VERSION:-11 (default)}"
    echo "WebLogic:     ${WL_VERSION:-14.1.1 (default)}"
    echo "OCCAS:        ${OCCAS_VERSION:-8.1 (default)}"
    echo "Modules: ${INCLUDED_COUNT} of ${TOTAL_COUNT}"
    echo "EAR: vorpal-blade-services-${PROFILE}.ear"

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
        "${DIST_FLAGS[@]+"${DIST_FLAGS[@]}"}" \
        "-Dbuild.number=${BUILD_NUM}" \
        "-Dear.profile=${PROFILE}" \
        "-Dbuild.platform=${PLATFORM}" \
        "-Dblade.skip.install=false"
    MVN_EXIT=$?
    set -e

    if [ $MVN_EXIT -ne 0 ]; then
        [ "$SKIP_DIST" = true ] || cleanup_failed_dist
        exit $MVN_EXIT
    fi

    [ "$SKIP_DIST" = true ] || write_deployment_manifest
    # zip_previous_dist
else
    # =====================================================================
    # Multiple profiles: build modules first, then EAR per profile
    # =====================================================================

    # Compute union of all included modules across all profiles
    UNION_MODULES=""
    for profile in "${PROFILES[@]}"; do
        conf="${PROFILES_DIR}/${profile}.conf"
        modules=$(read_modules "$conf")
        UNION_MODULES=$(printf '%s\n%s' "$UNION_MODULES" "$modules" | sort -u | grep -v '^$' || true)
    done

    # Skip flags for union (exclude modules not in ANY profile)
    UNION_SKIP_FLAGS=()
    while IFS= read -r dir; do
        if ! echo "$UNION_MODULES" | grep -qx "$dir"; then
            prop=$(dir_to_skip_prop "$dir")
            [ -n "$prop" ] && UNION_SKIP_FLAGS+=("-D${prop}")
        fi
    done <<< "$ALL_MODULES"

    UNION_COUNT=$(echo "$UNION_MODULES" | wc -l | tr -d ' ')

    echo "Build profiles: ${PROFILES[*]}"
    echo "Platform: ${PLATFORM}"
    echo "Build number: ${BUILD_NUM}"
    echo "Java version: ${JAVA_VERSION:-11 (default)}"
    echo "WebLogic:     ${WL_VERSION:-14.1.1 (default)}"
    echo "OCCAS:        ${OCCAS_VERSION:-8.1 (default)}"
    echo "Total modules: ${UNION_COUNT} of ${TOTAL_COUNT}"
    echo "EARs: $(printf 'vorpal-blade-services-%s.ear ' "${PROFILES[@]}")"
    echo ""

    # --- Phase 1: Build all modules except EAR ---
    # Install all artifacts to .m2 so the EAR module can find WARs in phase 2.
    echo "=== Phase 1: Building modules ==="
    set +e
    "${SCRIPT_DIR}/mvnw" \
        "${MAVEN_GOALS[@]}" \
        "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}" \
        -pl '!services' \
        "${UNION_SKIP_FLAGS[@]+"${UNION_SKIP_FLAGS[@]}"}" \
        "${PLATFORM_FLAGS[@]+"${PLATFORM_FLAGS[@]}"}" \
        "${DIST_FLAGS[@]+"${DIST_FLAGS[@]}"}" \
        "-Dbuild.number=${BUILD_NUM}" \
        "-Dblade.skip.install=false"
    MVN_EXIT=$?
    set -e

    if [ $MVN_EXIT -ne 0 ]; then
        [ "$SKIP_DIST" = true ] || cleanup_failed_dist
        exit $MVN_EXIT
    fi

    # --- Phase 2: Build EAR for each profile ---
    for profile in "${PROFILES[@]}"; do
        echo ""
        echo "=== Phase 2: Packaging EAR for '${profile}' ==="
        PROFILE_SKIP_FLAGS=()
        while IFS= read -r flag; do
            [ -n "$flag" ] && PROFILE_SKIP_FLAGS+=("$flag")
        done < <(compute_skip_flags "${PROFILES_DIR}/${profile}.conf" "$ALL_MODULES")

        set +e
        "${SCRIPT_DIR}/mvnw" \
            verify \
            "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}" \
            -pl services \
            "${PROFILE_SKIP_FLAGS[@]+"${PROFILE_SKIP_FLAGS[@]}"}" \
            "${PLATFORM_FLAGS[@]+"${PLATFORM_FLAGS[@]}"}" \
            "${DIST_FLAGS[@]+"${DIST_FLAGS[@]}"}" \
            "-Dbuild.number=${BUILD_NUM}" \
            "-Dear.profile=${profile}" \
            "-Dbuild.platform=${PLATFORM}"
        MVN_EXIT=$?
        set -e

        if [ $MVN_EXIT -ne 0 ]; then
            [ "$SKIP_DIST" = true ] || cleanup_failed_dist
            exit $MVN_EXIT
        fi
    done

    echo ""
    echo "Built ${#PROFILES[@]} EAR files:"
    for profile in "${PROFILES[@]}"; do
        echo "  vorpal-blade-services-${profile}.ear"
    done

    [ "$SKIP_DIST" = true ] || write_deployment_manifest
    # zip_previous_dist
fi
