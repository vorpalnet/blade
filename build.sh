#!/usr/bin/env bash
# ============================================================================
# build.sh - Profile-driven build wrapper for BLADE
#
# Usage:
#   ./build.sh [profile] [platform] [maven-args...]
#
# Examples:
#   ./build.sh                              # full build, OCCAS 8.1
#   ./build.sh production                   # production services, OCCAS 8.1
#   ./build.sh production occas-8.2         # production services, OCCAS 8.2
#   ./build.sh minimal occas-8.3            # core routing, OCCAS 8.3
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

# --- Parse arguments ---
PROFILE=""
PLATFORM=""
MAVEN_ARGS=()

for arg in "$@"; do
    if [ "$arg" = "--" ]; then
        continue
    elif [[ "$arg" == -* ]]; then
        MAVEN_ARGS+=("$arg")
    elif [ -z "$PROFILE" ] && [ -f "${PROFILES_DIR}/${arg}.conf" ]; then
        PROFILE="$arg"
    elif [ -z "$PLATFORM" ] && [ -f "${PLATFORMS_DIR}/${arg}.conf" ]; then
        PLATFORM="$arg"
    else
        MAVEN_ARGS+=("$arg")
    fi
done

PROFILE="${PROFILE:-$DEFAULT_PROFILE}"
PLATFORM="${PLATFORM:-$DEFAULT_PLATFORM}"
CONF_FILE="${PROFILES_DIR}/${PROFILE}.conf"
PLATFORM_FILE="${PLATFORMS_DIR}/${PLATFORM}.conf"

if [ ! -f "$CONF_FILE" ]; then
    echo "Error: Build profile '${PROFILE}' not found."
    echo "Available profiles:"
    for f in "${PROFILES_DIR}"/*.conf; do
        echo "  $(basename "${f%.conf}")"
    done
    exit 1
fi

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

# --- Calculate skip flags for modules NOT in the profile ---
ALL_MODULES=$(discover_modules)
INCLUDED_MODULES=$(read_modules "$CONF_FILE")

SKIP_FLAGS=()
while IFS= read -r dir; do
    if ! echo "$INCLUDED_MODULES" | grep -qx "$dir"; then
        prop=$(dir_to_skip_prop "$dir")
        [ -n "$prop" ] && SKIP_FLAGS+=("-D${prop}")
    fi
done <<< "$ALL_MODULES"

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

# --- Default to 'install' if no Maven goals specified ---
# Uses 'install' so the framework JAR is installed to the local .m2 repository.
# All other modules have maven-install-plugin skipped; only the framework JAR is installed.
# Check for actual goals (non-flag args) since flags like -Pjavadocs don't count.
HAS_GOALS=false
for arg in "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}"; do
    [[ "$arg" != -* ]] && HAS_GOALS=true
done
if [ "$HAS_GOALS" = false ]; then
    MAVEN_ARGS=("install" "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}")
fi

# --- Show what we're doing ---
echo "Build profile: ${PROFILE}"
echo "Platform: ${PLATFORM}"
echo "Build number: ${BUILD_NUM}"
echo "Java version: ${JAVA_VERSION:-11 (default)}"
INCLUDED_COUNT=$(echo "$INCLUDED_MODULES" | wc -l | tr -d ' ')
TOTAL_COUNT=$(echo "$ALL_MODULES" | wc -l | tr -d ' ')
echo "Modules: ${INCLUDED_COUNT} of ${TOTAL_COUNT}"

if [ "${#SKIP_FLAGS[@]}" -gt 0 ]; then
    EXCLUDED=$(printf '%s\n' "${SKIP_FLAGS[@]}" | sed 's/-Dskip\.//' | tr '\n' ' ')
    echo "Excluding: ${EXCLUDED}"
fi
echo ""

# --- Run Maven ---
exec "${SCRIPT_DIR}/mvnw" "${MAVEN_ARGS[@]}" "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" "${JAVA_FLAG[@]+"${JAVA_FLAG[@]}"}" "-Dbuild.number=${BUILD_NUM}"
