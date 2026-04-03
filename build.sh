#!/usr/bin/env bash
# ============================================================================
# build.sh - Profile-driven build wrapper for BLADE
#
# Usage:
#   ./build.sh [profile...] [platform] [maven-args...]
#
# Examples:
#   ./build.sh                              # full build, OCCAS 8.1
#   ./build.sh production                   # production services, OCCAS 8.1
#   ./build.sh production occas-8.2         # production services, OCCAS 8.2
#   ./build.sh minimal occas-8.3            # core routing, OCCAS 8.3
#   ./build.sh production minimal           # two EARs: production + minimal
#   ./build.sh production clean package     # with explicit Maven goals
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
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
PLATFORMS_DIR="${PROFILES_DIR}/platforms"
DEFAULT_PROFILE="full"
DEFAULT_PLATFORM="occas-8.1"

# --- Parse project version from pom.xml ---
REVISION=$(grep '<revision>' "${SCRIPT_DIR}/pom.xml" | head -1 | sed 's/.*<revision>\(.*\)<\/revision>.*/\1/')

# --- Discover all available service/test modules from directory names ---
discover_modules() {
    for subdir in services test; do
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

for arg in "$@"; do
    if [ "$arg" = "--" ]; then
        continue
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
JAVA_FLAG=()
if [ -n "$JAVA_VERSION" ]; then
    JAVA_FLAG=("-Dblade.java.version=${JAVA_VERSION}")
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

# --- Default to 'install' if no Maven goals specified ---
# Uses 'install' so the framework JAR is installed to the local .m2 repository.
# All other modules have maven-install-plugin skipped; only the framework JAR is installed.
# Check for actual goals (non-flag args) since flags like -Pjavadocs don't count.
HAS_GOALS=false
MAVEN_GOALS=()
MAVEN_FLAGS=()
for arg in "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}"; do
    if [[ "$arg" == -* ]]; then
        MAVEN_FLAGS+=("$arg")
    else
        MAVEN_GOALS+=("$arg")
        HAS_GOALS=true
    fi
done
if [ "$HAS_GOALS" = false ]; then
    MAVEN_GOALS=("install")
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
        "${JAVA_FLAG[@]+"${JAVA_FLAG[@]}"}" \
        "-Dbuild.number=${BUILD_NUM}" \
        "-Dear.profile=${PROFILE}" \
        "-Dbuild.platform=${PLATFORM}"
    MVN_EXIT=$?
    set -e

    if [ $MVN_EXIT -ne 0 ]; then
        cleanup_failed_dist
        exit $MVN_EXIT
    fi

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
        "${JAVA_FLAG[@]+"${JAVA_FLAG[@]}"}" \
        "-Dbuild.number=${BUILD_NUM}" \
        "-Dblade.skip.install=false"
    MVN_EXIT=$?
    set -e

    if [ $MVN_EXIT -ne 0 ]; then
        cleanup_failed_dist
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
            "${JAVA_FLAG[@]+"${JAVA_FLAG[@]}"}" \
            "-Dbuild.number=${BUILD_NUM}" \
            "-Dear.profile=${profile}" \
            "-Dbuild.platform=${PLATFORM}"
        MVN_EXIT=$?
        set -e

        if [ $MVN_EXIT -ne 0 ]; then
            cleanup_failed_dist
            exit $MVN_EXIT
        fi
    done

    echo ""
    echo "Built ${#PROFILES[@]} EAR files:"
    for profile in "${PROFILES[@]}"; do
        echo "  vorpal-blade-services-${profile}.ear"
    done

    # zip_previous_dist
fi
