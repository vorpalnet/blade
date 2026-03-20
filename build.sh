#!/usr/bin/env bash
# ============================================================================
# build.sh - Profile-driven build wrapper for Vorpal:BLADE
#
# Usage:
#   ./build.sh [profile...] [platform] [maven-args...]
#
# Examples:
#   ./build.sh                              # full build, OCCAS 8.1
#   ./build.sh production                   # production services, OCCAS 8.1
#   ./build.sh production minimal           # two EARs in one build
#   ./build.sh production occas-8.2         # production services, OCCAS 8.2
#   ./build.sh production minimal occas-8.2 # two EARs, OCCAS 8.2
#   ./build.sh production clean package     # with explicit Maven goals
#   ./build.sh -- -Pjavadocs                # full build with extra Maven flags
#
# Module profiles:   build-profiles/*.conf
# Platform profiles: build-profiles/platforms/*.conf
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
PLATFORMS_DIR="${PROFILES_DIR}/platforms"
DEFAULT_PROFILE="full"
DEFAULT_PLATFORM="occas-8.1"

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

# --- Calculate skip flags for a given profile ---
calc_skip_flags() {
    local conf_file="$1"
    local all_modules="$2"
    local included_modules
    included_modules=$(read_modules "$conf_file")

    local flags=()
    while IFS= read -r dir; do
        if ! echo "$included_modules" | grep -qx "$dir"; then
            local prop
            prop=$(dir_to_skip_prop "$dir")
            [ -n "$prop" ] && flags+=("-D${prop}")
        fi
    done <<< "$all_modules"
    echo "${flags[@]+"${flags[@]}"}"
}

# --- Parse arguments ---
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

# Defaults
if [ ${#PROFILES[@]} -eq 0 ]; then
    PROFILES=("$DEFAULT_PROFILE")
fi
PLATFORM="${PLATFORM:-$DEFAULT_PLATFORM}"
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

# --- Java version flag ---
JAVA_FLAG=()
if [ -n "$JAVA_VERSION" ]; then
    JAVA_FLAG=("-Djava.version=${JAVA_VERSION}")
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

# --- Discover all modules ---
ALL_MODULES=$(discover_modules)

# --- Show what we're doing ---
echo "Build profiles: ${PROFILES[*]}"
echo "Platform: ${PLATFORM}"
echo "Build number: ${BUILD_NUM}"
echo "Java version: ${JAVA_VERSION:-11 (default)}"

TOTAL_COUNT=$(echo "$ALL_MODULES" | wc -l | tr -d ' ')
echo "Total available modules: ${TOTAL_COUNT}"
echo ""

# --- Determine if user specified explicit Maven goals ---
HAS_GOALS=false
for arg in "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}"; do
    [[ "$arg" != -* ]] && HAS_GOALS=true
done

# --- Common Maven flags ---
COMMON_FLAGS=("${JAVA_FLAG[@]+"${JAVA_FLAG[@]}"}" "-Dbuild.number=${BUILD_NUM}")

if [ ${#PROFILES[@]} -eq 1 ]; then
    # --- Single profile: original behavior (one Maven run) ---
    PROFILE="${PROFILES[0]}"
    CONF_FILE="${PROFILES_DIR}/${PROFILE}.conf"
    INCLUDED_MODULES=$(read_modules "$CONF_FILE")
    SKIP_FLAGS_STR=$(calc_skip_flags "$CONF_FILE" "$ALL_MODULES")
    read -ra SKIP_FLAGS <<< "$SKIP_FLAGS_STR"

    INCLUDED_COUNT=$(echo "$INCLUDED_MODULES" | wc -l | tr -d ' ')
    echo "Profile '${PROFILE}': ${INCLUDED_COUNT} modules"
    if [ "${#SKIP_FLAGS[@]}" -gt 0 ]; then
        EXCLUDED=$(printf '%s\n' "${SKIP_FLAGS[@]}" | sed 's/-Dskip\.//' | tr '\n' ' ')
        echo "Excluding: ${EXCLUDED}"
    fi
    echo ""

    GOALS=("${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}")
    if [ "$HAS_GOALS" = false ]; then
        GOALS=("verify" "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}")
    fi

    "${SCRIPT_DIR}/mvnw" "${GOALS[@]}" "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" "${COMMON_FLAGS[@]}" "-Dbuild.profile=${PROFILE}"

else
    # --- Multiple profiles: optimized two-phase build ---

    # Phase 1: Compile all modules (full profile, package phase only)
    echo "=== Phase 1: Compiling all modules ==="
    echo ""

    PHASE1_GOALS=("${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}")
    if [ "$HAS_GOALS" = false ]; then
        PHASE1_GOALS=("package" "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}")
    fi

    "${SCRIPT_DIR}/mvnw" "${PHASE1_GOALS[@]}" "${COMMON_FLAGS[@]}" "-Dbuild.profile=full"

    echo ""
    echo "=== Phase 2: Assembling EARs per profile ==="

    # Phase 2: Re-assemble EAR for each profile (services module only, verify phase)
    for PROFILE in "${PROFILES[@]}"; do
        CONF_FILE="${PROFILES_DIR}/${PROFILE}.conf"
        INCLUDED_MODULES=$(read_modules "$CONF_FILE")
        SKIP_FLAGS_STR=$(calc_skip_flags "$CONF_FILE" "$ALL_MODULES")
        read -ra SKIP_FLAGS <<< "$SKIP_FLAGS_STR"

        INCLUDED_COUNT=$(echo "$INCLUDED_MODULES" | wc -l | tr -d ' ')
        echo ""
        echo "--- Profile '${PROFILE}': ${INCLUDED_COUNT} modules ---"
        if [ "${#SKIP_FLAGS[@]}" -gt 0 ]; then
            EXCLUDED=$(printf '%s\n' "${SKIP_FLAGS[@]}" | sed 's/-Dskip\.//' | tr '\n' ' ')
            echo "Excluding: ${EXCLUDED}"
        fi

        "${SCRIPT_DIR}/mvnw" verify -pl services "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" "${COMMON_FLAGS[@]}" "-Dbuild.profile=${PROFILE}"
    done
fi

# --- Copy common artifacts to dist ---
# Read revision from pom.xml
REVISION=$(grep '<revision>' "${SCRIPT_DIR}/pom.xml" | head -1 | sed 's/.*<revision>\(.*\)<\/revision>.*/\1/' | tr -d '[:space:]')
DISTDIR="${SCRIPT_DIR}/dist/${REVISION}-${BUILD_NUM}"
mkdir -p "$DISTDIR"

cp -f "${SCRIPT_DIR}/libs/framework/target/vorpal-blade-library-framework.jar" "$DISTDIR/" 2>/dev/null || true
cp -f "${SCRIPT_DIR}/libs/shared/target/vorpal-blade-library-shared.war" "$DISTDIR/" 2>/dev/null || true
cp -f "${SCRIPT_DIR}/libs/fsmar/target/vorpal-blade-library-fsmar.jar" "$DISTDIR/" 2>/dev/null || true
cp -f "${SCRIPT_DIR}/admin/console/target/vorpal-blade-admin-console.war" "$DISTDIR/" 2>/dev/null || true
cp -f "${SCRIPT_DIR}/admin/configurator/target/vorpal-blade-admin-configurator.war" "$DISTDIR/" 2>/dev/null || true

echo ""
echo "=== Dist: dist/${REVISION}-${BUILD_NUM}/ ==="
ls -1 "$DISTDIR"
