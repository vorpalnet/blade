#!/usr/bin/env bash
# ============================================================================
# build.sh - Profile-driven build wrapper for BLADE
#
# Usage:
#   ./build.sh [profile...] [platform] [--dev|--prod] [--no-dist] [--no-javadoc] [maven-args...]
#
# Examples:
#   ./build.sh                              # full build, auto-detected platform
#   ./build.sh production                   # production services, auto-detected
#   ./build.sh production occas-8.2         # production services, OCCAS 8.2
#   ./build.sh minimal occas-8.3            # core routing, OCCAS 8.3
#   ./build.sh production clean package     # with explicit Maven goals
#   ./build.sh clean                        # also purges org.vorpal.blade from ~/.m2
#   ./build.sh cleanAll                      # clean + delete the entire dist/ tree
#   ./build.sh --no-dist                    # full build, skip dist/ copy
#   ./build.sh --no-javadoc                 # full build, skip javadoc generation
#   ./build.sh                              # dev versioning (default): app version 3.0.4
#   ./build.sh --prod                       # release versioning: app version 3.0.4-<build>
#
# Dev vs prod versioning: WebLogic side-by-side versioning keys off
# WebLogic-Application-Version, so a build number that moves every build mints a NEW
# application version -- the previous one stays registered and blocks an in-place
# redeploy until undeployed by name. dev keeps the version stable (3.0.4) so OCCAS
# replaces the app on redeploy; prod appends the build number for traceable releases.
#   ./build.sh -- -Pfoo                     # full build with extra Maven flags
#
# Module profiles:   build-profiles/*.conf
# Platform profiles: build-profiles/platforms/*.conf
#
# Default platform resolution (when none given on the command line):
#   1. $MW_HOME env var → parse inventory/registry.xml for the active install
#      ($MW_HOME is the Oracle "Middleware Home" convention shared with OPatch
#      and other Oracle tooling.)
#   2. Exactly one OCCAS version bootstrapped in ~/.m2
#   3. Hardcoded fallback: occas-8.1
# The chosen source is shown in parentheses next to "Platform:" in the build
# header (e.g. "Platform: occas-8.3 ($MW_HOME)").
#
# Admin EAR (profile-driven contents):
#   When the active build-profiles/*.conf lists it, the admin tier EAR is built:
#     ear → admin/ear → blade-admin.ear   (admin tier, AdminServer)
#   Each admin WAR is contributed by an ear-<name> Maven profile activated by
#   !skip.<name> — the same flags this script derives from the conf. The javadoc
#   WAR rides the `javadocs` profile id: the admin EAR carries blade-javadoc.war
#   when docs are generated, and assembles without it otherwise.
#   The SERVICES tier has NO EAR — its WARs deploy individually (OCCAS 8.3 can't
#   show an EAR's contents; deploy.sh loops the per-service WARs).
#
# Dist management:
#   Every WAR/JAR built during the run is copied to dist/<ver>-<build>/:
#     dist/<ver>-<build>/            blade-admin.ear + libraries + conf files
#                                    (the deploy units: admin EAR, fsmar.jar,
#                                    shared.war). No admin/ subdir — the EAR is
#                                    the admin tier's only deployable.
#     dist/<ver>-<build>/services/   Service WARs (the services deploy units)
#   Plus the active build profile + platform conf files at the root for
#   traceability, and build.log — the full build console (Maven output +
#   summary) so administrators can see how the build went after the fact.
#   On failure: the current build's dist directory is deleted (so build.log is
#   retained only for builds that produced a dist — the terminal still shows a
#   failed run's output).
#
#   To skip the copy entirely (useful in local dev loops where you don't need
#   the dist/ folder rewritten on every build):
#     ./build.sh --no-dist                  # one-off
#     export BLADE_SKIP_DIST=1              # sticky for the current shell
#
#   --no-dist on the CLI overrides BLADE_SKIP_DIST=0 from the environment.
#
# Javadoc generation:
#   BLADE's source uses Java 23+ Markdown '///' doc comments (JEP 467), so docs
#   are generated automatically (via the -Pjavadocs profile) whenever the build
#   JDK is >= 23 — even though bytecode still targets Java 11 (--release). The
#   resulting blade-javadoc.war is bundled into the admin EAR (blade-admin.ear). On an older
#   build JDK the docs are skipped with a warning; the build itself is fine.
#   To skip generation deliberately (e.g. fast dev loops):
#     ./build.sh --no-javadoc               # one-off
#     export BLADE_SKIP_JAVADOC=1           # sticky for the current shell
#
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILES_DIR="${SCRIPT_DIR}/build-profiles"
PLATFORMS_DIR="${PROFILES_DIR}/platforms"
DEFAULT_PROFILE="default"

# --- Capture the full build console into dist/<ver>-<build>/build.log ---
# Everything printed from here on (this header, Maven's reactor output, the
# post-build summary — stdout and stderr merged) is teed to a temp file so
# administrators can see after the fact how the build went: what profile and
# platform, which modules, and Maven's PASS/FAIL. On a successful build the
# log is copied into that build's dist directory alongside DEPLOYMENT.txt.
# A trap on EXIT restores the terminal, flushes tee, and does the copy — so it
# runs no matter how the script leaves (normal end, mvn failure, early error).
BUILD_LOG="$(mktemp -t blade-build.XXXXXX)"
exec 3>&1 4>&2
exec > >(tee "$BUILD_LOG") 2>&1
finalize_build_log() {
    # Restore the real stdout/stderr, which closes the pipe feeding tee; then
    # wait for tee to flush and exit before we read the log file.
    exec 1>&3 2>&4 || true
    wait 2>/dev/null || true
    # Only land the log when this build produced a dist directory. Config
    # errors (bad platform, etc.) exit before DISTDIR is defined; clean-only
    # and --no-dist runs set SKIP_DIST=true and have no dist to copy into; a
    # failed Maven run has already had its DISTDIR removed by cleanup.
    if [ "${SKIP_DIST:-true}" != true ] && [ -n "${DISTDIR:-}" ] && [ -d "${DISTDIR:-}" ]; then
        cp -f "$BUILD_LOG" "${DISTDIR}/build.log" 2>/dev/null \
            && echo "Wrote dist/${REVISION}-${BUILD_NUM}/build.log" >&3
    fi
    rm -f "$BUILD_LOG" 2>/dev/null || true
}
trap finalize_build_log EXIT

# --- Default platform resolution, in order:
#       1. $MW_HOME env var → parse inventory/registry.xml for the active install
#          (same convention bootstrap.sh uses; this is the Oracle Middleware
#          Home variable required by OPatch and other Oracle tooling).
#       2. Exactly one OCCAS version bootstrapped in the local Maven repo.
#       3. Hardcoded fallback: occas-8.1.
#     User can always override on the command line: ./build.sh occas-8.3
#     If $MW_HOME is unset and the user didn't pass a platform on the CLI, we
#     emit a warning further down (after argument parsing) so the user knows
#     we're guessing.
DEFAULT_PLATFORM="occas-8.1"
DEFAULT_PLATFORM_SOURCE="fallback"
MW_HOME_WARNING=""

if [ -n "${MW_HOME:-}" ]; then
    if [ -f "${MW_HOME}/inventory/registry.xml" ]; then
        occas_v=$(grep -oE 'name="Converged Application Server" version="[0-9]+\.[0-9]+' \
                  "${MW_HOME}/inventory/registry.xml" \
                  | grep -oE '[0-9]+\.[0-9]+$' | head -1)
        if [ -n "$occas_v" ] && [ -f "${PLATFORMS_DIR}/occas-${occas_v}.conf" ]; then
            DEFAULT_PLATFORM="occas-${occas_v}"
            DEFAULT_PLATFORM_SOURCE="\$MW_HOME"
        else
            MW_HOME_WARNING="\$MW_HOME=${MW_HOME} → registry.xml present but version '${occas_v:-?}' has no matching build-profiles/platforms/occas-*.conf"
        fi
    else
        MW_HOME_WARNING="\$MW_HOME=${MW_HOME} → ${MW_HOME}/inventory/registry.xml not found (is this a valid OCCAS install?)"
    fi
else
    MW_HOME_WARNING="\$MW_HOME environment variable is not set"
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
    for subdir in libs admin services test proto apps; do
        for dir in "${SCRIPT_DIR}/${subdir}"/*/; do
            local name=$(basename "$dir")
            # Skip always-built modules
            case "$name" in
                applications) continue ;;
            esac
            # `|| true` matters under `set -e`: this test is the last command in
            # the loop, so a directory without a pom.xml (leftover Eclipse
            # metadata, a stale target/) makes the function return non-zero. When
            # that directory sorts last, `ALL_MODULES=$(discover_modules)` fails
            # and the build dies with no message at all.
            { [ -f "$dir/pom.xml" ] && echo "$name"; } || true
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
    for d in libs admin services test apps proto; do
        # Require a pom.xml, matching discover_modules. A bare directory is not
        # a module: services/acl is nothing but leftover Eclipse metadata and a
        # stale target/, and matching it shadowed the real proto/acl and shipped
        # a WAR from an old build.
        if [ -f "${SCRIPT_DIR}/${d}/${name}/pom.xml" ]; then
            echo "${d}/${name}"
            return
        fi
    done
}

# --- Map module source dir to a dist subdir ---
# Apps go in tier subdirs so operators can see at a glance where each one
# deploys. Libraries stay at dist root — they have their own special-cased
# deployment paths (WebLogic shared library, approuter/ JAR drop) that don't
# fit the generic admin/services tier model.
#
# Services are the exception to "the EAR is the deploy unit": they ship as loose
# WARs in dist/services/ and deploy one by one. Oracle's Remote Console cannot
# show the status of an application bundled inside an EAR, so a single
# blade-services.ear made every service invisible individually — you could see
# that the EAR was running, not which service inside it was. Separate WARs cost
# a longer deploy loop and buy per-service state, start/stop and targeting.
dist_subdir_for() {
    case "$1" in
        apps/*)                    echo "" ;;     # the EARs (blade-admin/test.ear) → dist root
        services/*)                echo "services" ;; # each service WAR is its own deploy unit — see below
        admin/*|test/*)            echo "skip" ;; # component WARs: the EAR is the deploy unit, not copied to dist
        proto/*)                   echo "proto" ;; # incubator apps — built, deployed by hand
        libs/*)                    echo "" ;;     # libraries at root (shared lib + approuter jar)
        *)                         echo "" ;;
    esac
}

# --- Copy every built WAR/JAR to dist/<ver>-<build>/<tier>/ ---
# Iterates the active profile's INCLUDED_MODULES list, and within each module
# copies only the artifact the module *declares* via <finalName> (which the
# parent POM maps to <warName>). A blind target/*.war glob would also sweep up
# a stale WAR left by an earlier build under a different finalName — e.g. a
# leftover vorpal-blade-services-transfer.war beside the current transfer.war —
# shipping two copies of one service to dist, since the default `./build.sh`
# does not `clean` between runs. Modules with no finalName fall back to a
# filtered glob that excludes Maven side-artifacts (sources, javadoc, tests,
# the war-plugin's intermediate -classes.jar). Also copies the active build
# profile and platform conf files to the dist root for traceability.
copy_all_to_dist() {
    [ "$SKIP_DIST" = true ] && return 0
    mkdir -p "$DISTDIR"
    local copied=0 missing=0
    local mod mdir target f produced subdir destdir final_name
    while IFS= read -r mod; do
        [ -n "$mod" ] || continue
        mdir=$(module_dir "$mod")
        if [ -z "$mdir" ]; then
            echo "  warn: module '$mod' listed in ${PROFILE}.conf has no source directory"
            continue
        fi
        target="${SCRIPT_DIR}/${mdir}/target"
        [ -d "$target" ] || { missing=$((missing + 1)); continue; }
        subdir=$(dist_subdir_for "$mdir")
        if [ "$subdir" = "skip" ]; then
            continue   # built but not copied to dist (e.g. individual admin WARs — the EAR is the deploy unit)
        elif [ -n "$subdir" ]; then
            destdir="$DISTDIR/$subdir"
            mkdir -p "$destdir"
        else
            destdir="$DISTDIR"
        fi
        produced=0
        # Copy the artifact the module declares via <finalName>, not whatever
        # WARs happen to be in target/. See the function header for why a blind
        # glob ships stale duplicates.
        # `|| true`: a pom without <finalName> makes grep exit 1, and under
        # `set -euo pipefail` that aborts the whole script — which made the
        # no-finalName fallback below unreachable. Modules reaching this point
        # used to all declare one; services/acl does not.
        final_name=$(grep -o '<finalName>[^<]*</finalName>' \
            "${SCRIPT_DIR}/${mdir}/pom.xml" 2>/dev/null \
            | head -1 | sed 's/<[^>]*>//g' || true)
        if [ -n "$final_name" ]; then
            for f in "$target/${final_name}.war" "$target/${final_name}.jar" "$target/${final_name}.ear"; do
                [ -f "$f" ] || continue
                cp -f "$f" "$destdir/"
                copied=$((copied + 1))
                produced=$((produced + 1))
            done
        else
            # No declared finalName — fall back to the filtered glob.
            for f in "$target"/*.war "$target"/*.jar; do
                [ -f "$f" ] || continue
                case "$(basename "$f")" in
                    *-sources.jar|*-javadoc.jar|*-tests.jar|*-classes.jar) continue ;;
                    original-*.jar) continue ;;
                esac
                cp -f "$f" "$destdir/"
                copied=$((copied + 1))
                produced=$((produced + 1))
            done
        fi
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
# artifact actually present in DISTDIR. Walks each tier subdirectory plus the
# dist root (for libraries and the build conf files).
write_deployment_manifest() {
    [ -d "$DISTDIR" ] || return 0
    local manifest="${DISTDIR}/DEPLOYMENT.txt"

    # Classify an admin/-tier WAR by its short context-root filename.
    classify_admin_war() {
        local name="$1" base="${1%.war}"
        case "$name" in
            blade-admin.ear)        echo "admin|AdminServer|Admin tier EAR — all admin apps in one deployable" ;;
            blade-portal.war)       echo "admin|AdminServer|Portal / launcher deck (context: /blade/portal)" ;;
            blade-redirect.war)     echo "admin|AdminServer|Bare /blade 302 → /blade/portal/ (context: /)" ;;
            blade-api.war)          echo "admin|AdminServer|API explorer (context: /blade/api)" ;;
            blade-configurator.war) echo "admin|AdminServer|Config editor (context: /blade/configurator)" ;;
            blade-events.war)       echo "admin|AdminServer|Events console — catalog, designer, JMS admin (context: /blade/events)" ;;
            blade-metrics.war)      echo "admin|AdminServer|Metrics — per-app counters across the cluster (context: /blade/metrics)" ;;
            blade-flow.war)         echo "admin|AdminServer|FSMAR diagram editor (context: /blade/flow)" ;;
            blade-tuning.war)       echo "admin|AdminServer|OCCAS/WebLogic tuning (context: /blade/tuning)" ;;
            blade-phone.war)        echo "admin|AdminServer|WebRTC softphone -- signals to the webrtc service on the ENGINE tier (context: /blade/phone)" ;;
            blade-files.war)        echo "admin|AdminServer|Config file manager (context: /blade/files)" ;;
            blade-logs.war)         echo "admin|AdminServer|Log viewer (context: /blade/logs)" ;;
            blade-javadoc.war)      echo "admin|AdminServer|Javadoc site (context: /blade/javadoc)" ;;
            blade-crud.war)         echo "admin|AdminServer|CRUD editor (context: /blade/crud-editor)" ;;
            blade-analytics.war)    echo "admin|AdminServer|Analytics console (context: /blade/analytics)" ;;
            *)                      echo "admin|AdminServer|Admin app (context: /${base})" ;;
        esac
    }

    # Classify a services/-tier WAR (services + test apps both live here).
    classify_services_war() {
        local name="$1" base="${1%.war}"
        case "$name" in
            test-*.war) echo "test|cluster|SIP test app (context: /${base})" ;;
            *)          echo "service|cluster|SIP service (context: /${base})" ;;
        esac
    }

    # Classify a root-level artifact (libraries + build conf files).
    classify_root_artifact() {
        case "$1" in
            blade-fsmar.jar)
                echo "fsmar|approuter/|SIP application router (reboot engine tier)" ;;
            blade-shared.war)
                echo "shared-lib|admin+cluster|WebLogic shared library (3rd-party JARs)" ;;
            blade-framework.jar)
                echo "framework|bundled in WARs|BLADE framework library (not deployed directly)" ;;
            blade-admin.ear)
                echo "admin|AdminServer|Admin tier EAR — every admin console in one deployable" ;;
            blade-test.ear)
                echo "test|engine0|Test tier EAR — test harness apps (never the production cluster)" ;;
            *.conf)
                echo "metadata|n/a|Build profile / platform used for this build" ;;
            *)
                echo "unknown|?|${1}" ;;
        esac
    }

    print_row() {
        printf '%-32s  %-11s  %-15s  %s\n' "$1" "$2" "$3" "$4"
    }

    list_dir() {
        ( cd "$1" 2>/dev/null && ls -1 2>/dev/null | grep -v '^DEPLOYMENT\.txt$' | sort )
    }

    print_section() {
        # $1 = relative path (e.g. "admin/"), $2 = classifier function name
        local rel="$1" classifier="$2"
        local abs="${DISTDIR}/${rel%/}"
        [ -d "$abs" ] || return 0
        local files=()
        while IFS= read -r f; do files+=("$f"); done < <(list_dir "$abs")
        [ ${#files[@]} -eq 0 ] && return 0
        echo ""
        echo "[ ${rel} ]"
        local f line tier target purpose
        for f in "${files[@]}"; do
            line=$("$classifier" "$f")
            tier="${line%%|*}";       line="${line#*|}"
            target="${line%%|*}";     line="${line#*|}"
            purpose="$line"
            print_row "$f" "$tier" "$target" "$purpose"
        done
    }

    print_root_section() {
        local files=()
        while IFS= read -r f; do
            [ -f "${DISTDIR}/${f}" ] || continue
            files+=("$f")
        done < <(list_dir "$DISTDIR")
        [ ${#files[@]} -eq 0 ] && return 0
        echo ""
        echo "[ libraries + build metadata ]"
        local f line tier target purpose
        for f in "${files[@]}"; do
            line=$(classify_root_artifact "$f")
            tier="${line%%|*}";       line="${line#*|}"
            target="${line%%|*}";     line="${line#*|}"
            purpose="$line"
            print_row "$f" "$tier" "$target" "$purpose"
        done
    }

    {
        echo "BLADE ${REVISION}-${BUILD_NUM} deployment manifest"
        echo "See DEPLOYMENT.md for the four-tier deployment model."
        echo ""
        # Which app version the artifacts actually carry. In dev the build number is
        # deliberately NOT in the app version, so OCCAS redeploys in place instead of
        # registering a new side-by-side version on every build.
        if [ "$BLADE_MODE" = "prod" ]; then
            echo "Build mode:  prod  (app version ${REVISION}-${BUILD_NUM} - traceable, side-by-side)"
        else
            echo "Build mode:  dev   (app version ${REVISION} - redeploys in place; NOT for production)"
        fi
        echo ""
        print_row "Artifact" "Tier" "Target" "Purpose"
        print_row "--------------------------------" "-----------" "---------------" "-------"
        print_root_section
        print_section "admin/"    classify_admin_war
        print_section "services/" classify_services_war
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

# Sticky dev-mode switch: BLADE_SKIP_JAVADOC=1 disables javadoc generation.
# The CLI flag --no-javadoc always wins. Javadocs are generated by default when
# the build JDK is >= 23 (see the javadoc decision block further down).
SKIP_JAVADOC=false
case "${BLADE_SKIP_JAVADOC:-}" in
    1|true|yes|on) SKIP_JAVADOC=true ;;
esac

# `cleanAll`: same as `clean` plus wiping the whole dist/ tree (not just this
# build's dist/<ver>-<build>/). We translate it to Maven's `clean` here and
# remember to nuke dist/ after the clean-purge block below.
REMOVE_ALL_DIST=false

# App-version mode. WebLogic side-by-side versioning keys off
# WebLogic-Application-Version, so a build number that moves every build mints a
# NEW application version each time -- the old one stays registered and must be
# undeployed by name before a redeploy will replace it. That is friction in a test
# loop, so dev is the default:
#
#   dev  (default)  WebLogic-Application-Version = <revision>          e.g. 3.0.4
#                   stable, so OCCAS replaces the app in place on redeploy
#   prod (--prod)   WebLogic-Application-Version = <revision>-<build>  e.g. 3.0.4-7
#                   distinct per build: traceable, side-by-side capable
#
# Override the default with BLADE_MODE=prod in the environment.
BLADE_MODE="${BLADE_MODE:-dev}"

for arg in "$@"; do
    if [ "$arg" = "--" ]; then
        continue
    elif [ "$arg" = "--no-dist" ]; then
        SKIP_DIST=true
    elif [ "$arg" = "--no-javadoc" ]; then
        SKIP_JAVADOC=true
    elif [ "$arg" = "--prod" ]; then
        BLADE_MODE=prod
    elif [ "$arg" = "--dev" ]; then
        BLADE_MODE=dev
    elif [ "$arg" = "cleanAll" ]; then
        REMOVE_ALL_DIST=true
        MAVEN_ARGS+=("clean")
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

# --- $MW_HOME warning ---
# Print only when we fell back to autodetection and $MW_HOME didn't resolve.
# If the user passed a platform on the CLI they made an explicit choice — stay quiet.
# If $MW_HOME resolved cleanly there's nothing to warn about either.
if [ "$PLATFORM_SOURCE" != "cli" ] && [ "$DEFAULT_PLATFORM_SOURCE" != "\$MW_HOME" ] && [ -n "$MW_HOME_WARNING" ]; then
    echo "WARNING: ${MW_HOME_WARNING}"
    echo "         Falling back to ${PLATFORM} (${PLATFORM_SOURCE})."
    echo "         \$MW_HOME is the Oracle Middleware Home convention — required by OPatch"
    echo "         and other Oracle tooling. To silence this and pin the platform"
    echo "         automatically, add to your shell rc:"
    echo "             export MW_HOME=/path/to/your/occas/install"
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

# Validated inline rather than via die(), which is not defined this early in the
# script. An unvalidated mode is worse than a loud failure: no versioning profile
# would match, so the build would silently produce dev-versioned artifacts.
case "$BLADE_MODE" in
    dev|prod) ;;
    *)
        echo "ERROR: unknown BLADE_MODE '${BLADE_MODE}'. Expected 'dev' or 'prod'." >&2
        echo "       dev  = app version ${REVISION} (redeploys in place)" >&2
        echo "       prod = app version ${REVISION}-<build> (traceable releases)" >&2
        exit 2
        ;;
esac
MAVEN_ARGS+=("-Dblade.mode=${BLADE_MODE}")

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
# If they're missing and $MW_HOME points at a valid OCCAS install, bootstrap
# automatically — bootstrap.sh reads $MW_HOME exactly the same way. Without
# $MW_HOME we have no path to the OCCAS install, so we can't self-bootstrap and
# fall back to the manual instruction below.
M2_REPO="${HOME}/.m2/repository"
# The full set of version-keyed jars bootstrap.sh installs (keep in sync with
# bootstrap.sh). Checking only a sentinel jar let a bootstrap from BEFORE a
# jar joined the set pass as "installed" — e.g. mscontrol, added in 3.0.3,
# missing from any earlier bootstrap. (weblogic-maven-plugin is deliberately
# omitted: its coordinate version can differ from WL_VERSION and it's a
# deploy-time, not build-time, dependency.)
REQUIRED_PLATFORM_JARS=(
    "${M2_REPO}/javax/javaee-api/8.0-occas/javaee-api-8.0-occas.jar"
    "${M2_REPO}/com/oracle/weblogic/weblogic-server/${WL_VERSION}/weblogic-server-${WL_VERSION}.jar"
    "${M2_REPO}/com/oracle/weblogic/weblogic-logging/${WL_VERSION}/weblogic-logging-${WL_VERSION}.jar"
    "${M2_REPO}/com/oracle/weblogic/weblogic-security-encryption/${WL_VERSION}/weblogic-security-encryption-${WL_VERSION}.jar"
    "${M2_REPO}/com/oracle/occas/sipservlet-api/${OCCAS_VERSION}/sipservlet-api-${OCCAS_VERSION}.jar"
    "${M2_REPO}/com/oracle/occas/wlss/${OCCAS_VERSION}/wlss-${OCCAS_VERSION}.jar"
    "${M2_REPO}/com/oracle/occas/wlssapi/${OCCAS_VERSION}/wlssapi-${OCCAS_VERSION}.jar"
    "${M2_REPO}/com/oracle/occas/mscontrol/${OCCAS_VERSION}/mscontrol-${OCCAS_VERSION}.jar"
)
occas_installed() {
    local jar
    for jar in "${REQUIRED_PLATFORM_JARS[@]}"; do
        [ -f "$jar" ] || return 1
    done
}

if ! occas_installed && [ -n "${MW_HOME:-}" ] && [ -d "${MW_HOME}/wlserver" ]; then
    echo "OCCAS/WebLogic libraries not found in local Maven repo for platform ${PLATFORM}."
    echo "Bootstrapping from \$MW_HOME=${MW_HOME} ..."
    echo ""
    "${SCRIPT_DIR}/bootstrap.sh" "$MW_HOME"
    echo ""
fi

if ! occas_installed; then
    missing_libs=()
    for jar in "${REQUIRED_PLATFORM_JARS[@]}"; do
        [ -f "$jar" ] || missing_libs+=("$jar")
    done
    echo "Error: OCCAS/WebLogic libraries not found in local Maven repo for platform ${PLATFORM}:"
    printf '  %s\n' "${missing_libs[@]}"
    echo ""
    if [ -n "${MW_HOME:-}" ]; then
        echo "Auto-bootstrap from \$MW_HOME=${MW_HOME} did not install OCCAS ${OCCAS_VERSION}."
        echo "Check that \$MW_HOME points at an OCCAS ${OCCAS_VERSION} install, or run"
        echo "./bootstrap.sh /path/to/occas-${OCCAS_VERSION} manually."
    else
        echo "Run ./bootstrap.sh /path/to/occas first to install them,"
        echo "or set \$MW_HOME and re-run ./build.sh to bootstrap automatically."
    fi
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
# we append `install` so the framework JAR and parent POM land in .m2. All
# other modules have maven-install-plugin skipped (blade.skip.install=true in
# the parent pom), so this is cheap.
HAS_GOALS=false
HAS_INSTALL=false
HAS_BUILD_GOAL=false
HAS_CLEAN=false
MAVEN_GOALS=()
MAVEN_FLAGS=()
for arg in "${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}"; do
    if [[ "$arg" == -* ]]; then
        MAVEN_FLAGS+=("$arg")
    else
        MAVEN_GOALS+=("$arg")
        HAS_GOALS=true
        # Goals in the clean lifecycle don't compile anything, so they
        # shouldn't trigger the install-append below. Anything else is
        # treated as a build goal.
        case "$arg" in
            clean|pre-clean|post-clean) HAS_CLEAN=true ;;
            install|deploy) HAS_INSTALL=true; HAS_BUILD_GOAL=true ;;
            *) HAS_BUILD_GOAL=true ;;
        esac
    fi
done
if [ "$HAS_GOALS" = false ]; then
    MAVEN_GOALS=("install")
    HAS_BUILD_GOAL=true
elif [ "$HAS_INSTALL" = false ] && [ "$HAS_BUILD_GOAL" = true ]; then
    # See header comment: downstream repos resolve BLADE artifacts via Maven
    # version ranges, so we install on every build that produces artifacts.
    # Clean-only runs (./build.sh clean) skip this — nothing to install.
    MAVEN_GOALS+=("install")
fi

# --- Purge installed BLADE artifacts on clean ---
# `mvn clean` only reaches target/; installed artifacts in ~/.m2 are the
# other place build output lands, and version-range consumers (optum's
# [2.0.0,)) resolve to the highest version found there — so a stale install
# silently shadows fresh builds. Scoped to org/vorpal/blade: the
# bootstrapped OCCAS/WebLogic JARs (javax, com.oracle.*) are untouched.
# After a clean-only run, downstream repos can't resolve BLADE until the
# next ./build.sh re-installs it — a loud failure, by design.
if [ "$HAS_CLEAN" = true ]; then
    echo "Purging BLADE artifacts from ${M2_REPO}/org/vorpal/blade"
    rm -rf "${M2_REPO}/org/vorpal/blade"
fi

# --- cleanAll: also wipe the entire dist/ tree ---
# `clean` only reaches each module's target/; the accumulated dist/<ver>-<build>/
# directories survive. cleanAll removes the whole dist/ folder for a from-scratch
# checkout.
if [ "$REMOVE_ALL_DIST" = true ] && [ -d "${SCRIPT_DIR}/dist" ]; then
    echo "Removing ${SCRIPT_DIR}/dist"
    rm -rf "${SCRIPT_DIR}/dist"
fi

# Clean-only runs have no dist to copy. Force SKIP_DIST so we don't write
# an empty dist/<ver>-<build>/ directory containing only the .conf files.
if [ "$HAS_BUILD_GOAL" = false ]; then
    SKIP_DIST=true
fi

ALL_MODULES=$(discover_modules)
TOTAL_COUNT=$(echo "$ALL_MODULES" | wc -l | tr -d ' ')

# --- Module directory names must be unique across the category directories ---
# Every module is addressed by its bare directory name: the conf files list bare
# names, skip flags are -Dskip.<name>, and module_dir() resolves a name by taking
# the FIRST match in libs/admin/services/test/apps/proto order. Two modules
# sharing a name therefore make one of them unaddressable — it cannot be skipped
# independently, and the dist copy silently takes the artifact of whichever
# directory sorts first. That shipped a stale WAR once already (services/acl
# shadowing proto/acl). Fail loudly instead.
DUPLICATE_MODULES=$(echo "$ALL_MODULES" | sort | uniq -d)
if [ -n "$DUPLICATE_MODULES" ]; then
    echo "Error: duplicate module directory names."
    while IFS= read -r dup; do
        [ -z "$dup" ] && continue
        echo "  '${dup}' exists in:"
        for d in libs admin services test apps proto; do
            [ -f "${SCRIPT_DIR}/${d}/${dup}/pom.xml" ] && echo "    ${d}/${dup}"
        done
    done <<< "$DUPLICATE_MODULES"
    echo
    echo "Module names must be unique across libs/, admin/, services/, test/,"
    echo "apps/ and proto/. Rename one, or delete the leftover directory."
    exit 1
fi

# --- One profile per invocation: a build is one Maven reactor, and the admin
#     EAR contents are derived from that reactor's skip flags. Different profiles
#     produce different contents under the same blade-admin.ear name, so run them
#     as separate builds (each gets its own dist/<ver>-<build>/ directory). ---
if [ ${#PROFILES[@]} -gt 1 ]; then
    echo "Error: multiple profiles (${PROFILES[*]}) in one invocation are not supported."
    echo "       Each build is one Maven reactor and produces one blade-admin.ear"
    echo "       whose contents match that profile. Run one profile at a time —"
    echo "       each lands in its own dist/<ver>-<build>/."
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

# Detect the JDK that will run the build (mvnw uses $JAVA_HOME if set, else
# the `java` on PATH). Surfaces both "what's compiling" and "what bytecode
# you're producing" so people stop confusing the two.
# Match the actual version line, not merely the first line: with
# JAVA_TOOL_OPTIONS/_JAVA_OPTIONS set, the JVM prints "Picked up ..." first.
BUILD_JDK_VERSION=$(java -version 2>&1 | grep -m1 -E '^[^ ]+ version ' \
    | sed 's/^[^ ]* version //;s/"//g' | awk '{print $1}')
BUILD_JDK_MAJOR=$(printf '%s' "$BUILD_JDK_VERSION" \
    | awk -F. '{if ($1 == "1") print $2; else print $1}')
if [ -n "${JAVA_HOME:-}" ]; then
    BUILD_JDK_SOURCE="\$JAVA_HOME=${JAVA_HOME}"
else
    BUILD_JDK_SOURCE="PATH: $(command -v java 2>/dev/null || echo 'not found')"
fi

# --- Javadoc generation (on by default) ---
# BLADE source uses Java 23+ Markdown '///' doc comments (JEP 467), so the
# javadoc tool must come from a JDK >= 23 — even though we compile to Java
# ${JAVA_VERSION:-11} bytecode (--release). When the build JDK is older we
# can't render the docs, so we skip them (the build itself is unaffected) and
# warn below. Generating activates the -Pjavadocs profile, which adds the
# admin/javadoc module; we append "javadoc" to the dist copy list so the
# blade-javadoc.war is bundled into blade-admin.ear (admin WARs are not copied to dist individually).
JAVADOC_MIN_JDK=23
JAVADOC_FLAGS=()
JAVADOC_OLD_JDK=false
jdk_ok_for_javadoc=false
if [ -n "${BUILD_JDK_MAJOR:-}" ] && [ "${BUILD_JDK_MAJOR}" -ge "$JAVADOC_MIN_JDK" ] 2>/dev/null; then
    jdk_ok_for_javadoc=true
fi
# Was -Pjavadocs already passed by hand (the legacy `-- -Pjavadocs` form)?
javadoc_manual=false
for f in "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}"; do
    case "$f" in -P*javadocs*) javadoc_manual=true ;; esac
done

if [ "$HAS_BUILD_GOAL" != true ]; then
    # Clean-only run. admin/javadoc is gated behind the `javadocs` profile, so
    # without it a plain `./build.sh clean` would leave admin/javadoc/target/
    # stale. Activate -Pjavadocs so the clean lifecycle reaches that module —
    # safe on any JDK because clean never runs the javadoc tool (it only
    # deletes target/, no JDK 23+ requirement).
    if [ "$HAS_CLEAN" = true ]; then
        JAVADOC_FLAGS+=("-Pjavadocs")
        INCLUDED_MODULES="${INCLUDED_MODULES}"$'\n'"javadoc"
        JAVADOC_STATUS="n/a (clean-only run; admin/javadoc pulled in so clean reaches it)"
    else
        JAVADOC_STATUS="n/a (clean-only run)"
    fi
elif [ "$SKIP_JAVADOC" = true ]; then
    # The admin EAR's javadoc webModule rides the `javadocs` profile id, so
    # without it the EAR simply assembles without blade-javadoc.war.
    JAVADOC_STATUS="SKIPPED (--no-javadoc or BLADE_SKIP_JAVADOC set) — admin EAR built without blade-javadoc.war"
elif [ "$javadoc_manual" = true ]; then
    INCLUDED_MODULES="${INCLUDED_MODULES}"$'\n'"javadoc"
    JAVADOC_STATUS="generating (-Pjavadocs passed explicitly)"
elif [ "$jdk_ok_for_javadoc" = true ]; then
    JAVADOC_FLAGS+=("-Pjavadocs")
    INCLUDED_MODULES="${INCLUDED_MODULES}"$'\n'"javadoc"
    JAVADOC_STATUS="generating (-Pjavadocs → admin/javadoc → blade-javadoc.war)"
else
    JAVADOC_OLD_JDK=true
    # No javadoc WAR on an older JDK → the admin EAR assembles without it.
    JAVADOC_STATUS="SKIPPED — needs JDK ${JAVADOC_MIN_JDK}+ (build JDK is ${BUILD_JDK_MAJOR:-unknown}); admin EAR built without blade-javadoc.war"
fi

# Reusable so the same block prints in the header and the post-build summary.
print_build_info() {
    echo "Build profile: ${PROFILE}"
    echo "Platform:      ${PLATFORM} (${PLATFORM_SOURCE})"
    echo "Build number:  ${BUILD_NUM}"
    echo "Build JDK:     ${BUILD_JDK_VERSION:-unknown} (${BUILD_JDK_SOURCE})"
    echo "Target:        Java ${JAVA_VERSION:-11} bytecode (--release ${JAVA_VERSION:-11})"
    echo "Javadocs:      ${JAVADOC_STATUS}"
    echo "WebLogic:      ${WL_VERSION:-14.1.1 (default)}"
    echo "OCCAS:         ${OCCAS_VERSION:-8.1 (default)}"
}

print_build_info

# Friendly heads-up: maven-compiler-plugin is configured with --release, which
# requires the build JDK >= the target. JDK 8 doesn't know --release at all.
if [ -n "${JAVA_VERSION:-}" ] && [ -n "${BUILD_JDK_MAJOR:-}" ] \
        && [ "$BUILD_JDK_MAJOR" -lt "$JAVA_VERSION" ] 2>/dev/null; then
    echo "WARNING: build JDK ${BUILD_JDK_MAJOR} is older than target ${JAVA_VERSION} — compile will fail."
    echo "         Set JAVA_HOME to a JDK >= ${JAVA_VERSION} and re-run."
fi
# Javadoc generation needs a JDK >= 23 for BLADE's Markdown '///' doc comments
# (JEP 467). Warn — don't fail — when we wanted docs but the build JDK is older.
if [ "$JAVADOC_OLD_JDK" = true ]; then
    echo "WARNING: skipping Javadoc generation — the javadoc tool needs a JDK ${JAVADOC_MIN_JDK}+"
    echo "         for BLADE's Markdown '///' doc comments (JEP 467); build JDK is ${BUILD_JDK_MAJOR:-unknown}."
    echo "         This affects the docs only — bytecode still targets Java ${JAVA_VERSION:-11} (--release ${JAVA_VERSION:-11})."
    echo "         Point JAVA_HOME at a JDK ${JAVADOC_MIN_JDK}+ to build docs, or pass --no-javadoc to silence this."
fi
echo "Modules: ${INCLUDED_COUNT} of ${TOTAL_COUNT}"
# DIST_MSG distinguishes "user told us to skip" from "nothing to dist anyway",
# and is reused below in the post-build summary so both lines match.
if [ "$HAS_BUILD_GOAL" = false ]; then
    DIST_MSG="n/a (clean-only run)"
elif [ "$SKIP_DIST" = true ]; then
    DIST_MSG="SKIPPED (--no-dist or BLADE_SKIP_DIST set)"
else
    DIST_MSG="dist/${REVISION}-${BUILD_NUM}/"
fi
echo "Dist:    ${DIST_MSG}"

if [ "${#SKIP_FLAGS[@]}" -gt 0 ]; then
    EXCLUDED=$(printf '%s\n' "${SKIP_FLAGS[@]}" | sed 's/-Dskip\.//' | tr '\n' ' ')
    echo "Excluding: ${EXCLUDED}"
fi
echo ""

# Hand the profile's module list to admin/javadoc's collect-javadocs.sh
# (4th argument, via -Dblade.included.modules) so blade-javadoc.war contains the
# docs of exactly this build's modules — collected fresh, stale ones pruned.
INCLUDED_CSV=$(printf '%s' "$INCLUDED_MODULES" | tr '\n' ',' | sed 's/^,*//;s/,*$//')

set +e
"${SCRIPT_DIR}/mvnw" -f "${SCRIPT_DIR}/pom.xml" \
    "${MAVEN_GOALS[@]}" \
    "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}" \
    "${JAVADOC_FLAGS[@]+"${JAVADOC_FLAGS[@]}"}" \
    "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" \
    "${PLATFORM_FLAGS[@]+"${PLATFORM_FLAGS[@]}"}" \
    "-Dbuild.number=${BUILD_NUM}" \
    "-Dblade.included.modules=${INCLUDED_CSV}"
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

# Re-print the build header at the end. Maven's reactor summary runs to
# dozens of lines; without this people scroll up to figure out what JDK
# compiled what against which platform.
echo ""
echo "================================ BUILD SUMMARY ================================"
print_build_info
echo "Dist:          ${DIST_MSG}"
echo "==============================================================================="
