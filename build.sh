#!/usr/bin/env bash
# ============================================================================
# build.sh - Profile-driven build wrapper for BLADE
#
# A build REQUIRES a profile — there is no "build everything" default. BLADE is a
# development framework; not every module needs building or deploying, so naming a
# profile is a deliberate choice. With no profile on an interactive terminal you
# get a picker (existing profiles, or create one); without a terminal you get a
# non-zero error naming the available profiles. Clean-only runs need no profile.
#
# Usage:
#   ./build.sh <profile> [platform] [--dev|--prod] [--no-dist] [--no-javadoc] [maven-args...]
#   ./build.sh --init                       # build a new profile interactively, then build it
#   ./build.sh --list                       # list available profiles and exit
#
# Examples:
#   ./build.sh default                      # the base set (all but proto/), auto-detected platform
#   ./build.sh default occas-8.2            # base set, OCCAS 8.2
#   ./build.sh minimal occas-8.3            # core routing, OCCAS 8.3
#   ./build.sh full                         # base set PLUS the proto/ incubator apps
#   ./build.sh default clean package        # with explicit Maven goals
#   ./build.sh clean                        # clean-only (no profile needed); purges org.vorpal.blade from ~/.m2
#   ./build.sh cleanAll                     # clean + delete the entire dist/ tree
#   ./build.sh default --no-dist            # skip dist/ copy
#   ./build.sh default --no-javadoc         # skip javadoc generation
#   ./build.sh default                      # dev versioning (default): app version 3.0.4
#   ./build.sh default --prod               # release versioning: app version 3.0.4-<build>
#
# Dev vs prod versioning: WebLogic side-by-side versioning keys off
# WebLogic-Application-Version, so a build number that moves every build mints a NEW
# application version -- the previous one stays registered and blocks an in-place
# redeploy until undeployed by name. dev keeps the version stable (3.0.4) so OCCAS
# replaces the app on redeploy; prod appends the build number for traceable releases.
#   ./build.sh default -- -Pfoo             # build with extra Maven flags
#
# Module profiles:   build-profiles/*.conf   (canonical, committed: default, full, minimal)
#                    .conf/*.conf            (local, gitignored — written by --init)
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
# Per-tier EARs (automatic; contents track the profile):
#   Every tier builds a per-tier EAR from apps/<tier> — blade-admin.ear,
#   blade-services.ear, blade-test.ear, blade-proto.ear. Each component WAR is
#   contributed by an ear-<name> Maven profile activated by !skip.<name> — the same
#   flags this script derives from the conf — so an EAR shrinks to match a trimmed
#   profile. The EAR modules are NOT discovered/selectable (see discover_modules),
#   so they always build. The javadoc WAR rides the `javadocs` profile id: the
#   admin EAR carries blade-javadoc.war when docs are generated.
#   Both the loose WARs and the EAR land in dist/<tier>/ — deploy whichever suits.
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
# Local (gitignored) profiles the --init builder writes, resolved after the
# canonical build-profiles/ ones. Shares the .conf/ dir blade.sh already uses.
LOCAL_PROFILES_DIR="${SCRIPT_DIR}/.conf"
# The base profile. No longer applied automatically (a build now requires an
# explicit profile) — this only names the set --init pre-checks as a starting
# point, and the profile the picker/docs point at first.
BASE_PROFILE="default"

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
    # apps/ (the per-tier EAR modules) is intentionally NOT discovered: the EARs
    # build automatically and aren't user-selectable, so they never get a
    # -Dskip.<name> and never appear in the --init menu.
    for subdir in libs admin services test proto; do
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
# Every tier gets its own dist/ subdirectory holding its loose artifacts; each
# tier's EAR is copied into the same subdir afterwards (see copy_all_to_dist), so
# dist/<tier>/ is self-contained: loose WARs + the EAR, deploy whichever you want.
dist_subdir_for() {
    case "$1" in
        libs/*)      echo "lib" ;;
        admin/*)     echo "admin" ;;
        services/*)  echo "services" ;;
        test/*)      echo "test" ;;
        proto/*)     echo "proto" ;;
        apps/*)      echo "skip" ;;    # the EARs — copied per-tier separately
        *)           echo "" ;;
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

    # Each tier's EAR (apps/<tier> → blade-<tier>.ear) into that tier's dir, so
    # dist/<tier>/ holds the loose WARs AND the assembled EAR side by side. The
    # apps/ subdir name is the tier name (admin/services/test/proto).
    local eardir eartier earf
    for eardir in "${SCRIPT_DIR}"/apps/*/; do
        [ -d "${eardir}target" ] || continue
        eartier=$(basename "$eardir")
        for earf in "${eardir}target"/*.ear; do
            [ -f "$earf" ] || continue
            mkdir -p "$DISTDIR/$eartier"
            cp -f "$earf" "$DISTDIR/$eartier/"
            copied=$((copied + 1))
        done
    done

    [ -n "$CONF_FILE" ] && cp -f "$CONF_FILE" "$DISTDIR/" 2>/dev/null && copied=$((copied + 1))
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

# ---------------------------------------------------------------------------
# Profiles: resolution, listing, interactive picker, and the --init builder.
#
# A build requires a profile. These support that: resolve a name to its conf
# (canonical build-profiles/ first, then local .conf/); list what's available;
# pick one interactively; or build a new one. The picker/builder render to the
# terminal and set the global PROFILES array — no command substitution, so their
# menus display normally.
# ---------------------------------------------------------------------------

# Echo the conf-file path for a profile name, or nothing if it doesn't exist.
# Canonical (committed) profiles win over local (.conf/) ones of the same name.
profile_conf_path() {
    if [ -f "${PROFILES_DIR}/$1.conf" ]; then
        echo "${PROFILES_DIR}/$1.conf"
    elif [ -f "${LOCAL_PROFILES_DIR}/$1.conf" ]; then
        echo "${LOCAL_PROFILES_DIR}/$1.conf"
    fi
}

# One-line banner for the first screen.
print_banner() {
    echo "BLADE ${REVISION}  ·  MIT License  ·  (c) vorpal.net"
}

# A short description for a profile: the conf's "# desc:" line if present, else a
# module count. Keeps the listings self-documenting and local profiles readable.
profile_desc() {
    local conf desc
    conf=$(profile_conf_path "$1")
    [ -n "$conf" ] || return 0
    desc=$(grep -m1 -E '^# *desc:' "$conf" | sed -E 's/^# *desc: *//')
    if [ -n "$desc" ]; then
        echo "$desc"
    else
        echo "$(read_modules "$conf" | grep -c .) modules"
    fi
}

# Print the available profiles with a short description of each: the committed
# ones first, then any local (--init) ones.
list_profiles() {
    local f name any=0
    echo "Existing profiles include:"
    for f in "${PROFILES_DIR}"/*.conf; do
        [ -e "$f" ] || continue
        name=$(basename "${f%.conf}")
        printf '  %-10s %s\n' "$name" "$(profile_desc "$name")"
    done
    for f in "${LOCAL_PROFILES_DIR}"/*.conf; do
        [ -e "$f" ] || continue
        name=$(basename "${f%.conf}")
        [ "$any" = 0 ] && { echo "  ── your own (.conf/, gitignored) ──"; any=1; }
        printf '  %-10s %s\n' "$name" "$(profile_desc "$name")"
    done
}

# Interactive picker: numbered list of profiles + "create" + "quit". Sets the
# global PROFILES array, or exits. Shown when a build is requested on a TTY with
# no profile named.
pick_profile() {
    # Wipe the terminal first so the picker lands on a clean screen (fd 3 is the
    # real tty — see init_profile). `|| true` keeps set -e happy when fd 3 isn't.
    [ -t 3 ] && printf '\033[H\033[2J' >&3 || true
    local -a names=()
    local f
    for f in "${PROFILES_DIR}"/*.conf; do
        [ -e "$f" ] || continue
        names+=("$(basename "${f%.conf}")")
    done
    for f in "${LOCAL_PROFILES_DIR}"/*.conf; do
        [ -e "$f" ] || continue
        names+=("$(basename "${f%.conf}")")
    done

    # Paint to fd 3 (the real terminal, not the tee'd fd 1 — see init_profile),
    # read from fd 0. Keeps the picker off build.log and its prompt on-screen.
    {
        echo ""
        print_banner
        echo ""
        echo "Which would you like to build? BLADE is a development framework, so you"
        echo "pick a profile rather than building everything."
        echo ""
        local i=1 n
        for n in "${names[@]}"; do
            printf '  %2d  %-10s %s\n' "$i" "$n" "$(profile_desc "$n")"
            i=$((i + 1))
        done
        echo "   c  create a new profile (pick modules interactively)"
        echo "   q  quit"
        echo ""
    } >&3

    local choice
    while true; do
        printf '  profile: ' >&3
        read -r choice || choice="q"
        case "$choice" in
            q|Q|"") echo "No profile chosen — nothing built." >&3; exit 1 ;;
            c|C)    init_profile; return ;;
            *[!0-9]*) echo "  please enter a number, or c / q: ${choice}" >&3 ;;
            *)      if [ "$choice" -ge 1 ] && [ "$choice" -le "${#names[@]}" ]; then
                        PROFILES=("${names[$((choice - 1))]}"); return
                    fi
                    echo "  out of range: ${choice}" >&3 ;;
        esac
    done
}

# Read one keystroke, mapping arrows/space/enter/q to words. Mirrors blade.sh's
# _read_key: an Esc is followed by a short timed read to tell a lone Esc from an
# arrow sequence. On EOF (no TTY, closed pipe) it returns 'quit' so --init fails
# safe. j/k double for down/up.
_INIT_ESC_T=0.05; [ "${BASH_VERSINFO[0]}" -lt 4 ] && _INIT_ESC_T=1
_init_read_key() {
    local k r
    IFS= read -rsn1 k 2>/dev/null || { printf 'quit'; return; }
    case "$k" in
        $'\e') IFS= read -rsn2 -t "$_INIT_ESC_T" r 2>/dev/null || r=""
               case "$r" in
                   '[A'|'OA') printf 'up' ;;
                   '[B'|'OB') printf 'down' ;;
                   *)         printf 'other' ;;
               esac ;;
        ' ')            printf 'space' ;;
        ''|$'\n'|$'\r') printf 'enter' ;;
        j|J)            printf 'down' ;;
        k|K)            printf 'up' ;;
        q|Q)            printf 'quit' ;;
        *)              printf 'other' ;;
    esac
}

# The --init profile builder: a blade.sh-style accordion. Every discovered module
# is grouped by tier; exactly one tier is expanded (the one the cursor is in),
# the rest collapse to a header with an n/total count. Up/Down (or j/k) move the
# cursor and flow between tiers; Space toggles the item under the cursor (or, on
# a tier header, every item in that tier); Enter saves + builds; q quits. The
# cursor row is marked with a '›' AND inverse video, and checkboxes are [x]/[ ] —
# shape and position carry the state, never color. Writes .conf/<name>.conf in
# the bare-name format read_modules parses, then sets PROFILES.
init_profile() {
    # Discovered modules with their tier, in discovery order (same set as
    # discover_modules; contiguous per tier because the outer loop is the tier).
    local -a mods=() tiers=()
    local tier dir name
    for tier in libs admin services test proto; do
        for dir in "${SCRIPT_DIR}/${tier}"/*/; do
            [ -e "$dir" ] || continue
            name=$(basename "$dir")
            [ "$name" = "applications" ] && continue
            [ -f "${dir}pom.xml" ] || continue
            mods+=("$name"); tiers+=("$tier")
        done
    done
    if [ "${#mods[@]}" -eq 0 ]; then
        echo "No modules found to choose from." >&2; exit 1
    fi

    # Pre-check the base profile's module set. Membership is a padded string
    # (bash 3.2 has no associative arrays).
    local checked=" " base_conf m
    base_conf=$(profile_conf_path "$BASE_PROFILE")
    if [ -n "$base_conf" ]; then
        while IFS= read -r m; do
            [ -n "$m" ] && checked="${checked}${m} "
        done <<< "$(read_modules "$base_conf")"
    fi
    _chk_has() { case "$checked" in *" $1 "*) return 0 ;; esac; return 1; }

    # Build the linear position list: one header per tier, then that tier's items.
    # pos_cat = tier id, pos_mod = global module index (-1 for a header row).
    # Rendering expands only the cursor's tier, but the cursor walks every row, so
    # moving past a tier's last item lands on the next header and flows onward.
    local -a pos_cat=() pos_mod=()
    local gi prev="__none__"
    for gi in "${!mods[@]}"; do
        if [ "${tiers[$gi]}" != "$prev" ]; then
            prev="${tiers[$gi]}"
            pos_cat+=("$prev"); pos_mod+=("-1")   # header row
        fi
        pos_cat+=("${tiers[$gi]}"); pos_mod+=("$gi")
    done
    local npos=${#pos_cat[@]}

    # Count checked / total modules in a tier (for the header badge).
    _tier_stats() {
        local t="$1" n=0 tot=0 g
        for g in "${!mods[@]}"; do
            [ "${tiers[$g]}" = "$t" ] || continue
            tot=$((tot + 1)); _chk_has "${mods[$g]}" && n=$((n + 1))
        done
        printf '%d/%d' "$n" "$tot"
    }

    # Display label for a tier. The 'apps/' dir holds the EAR assemblers
    # (blade-admin.ear, blade-test.ear), so show it as EARS, not the opaque dir
    # name. Everything else is just its dir name, uppercased.
    _tier_label() {
        case "$1" in
            apps) printf 'EARS' ;;
            *)    printf '%s' "$1" | tr '[:lower:]' '[:upper:]' ;;
        esac
    }

    # Start on the first item (first row after the first header).
    local cursor=1

    # build.sh tees its own stdout to a build log, so fd 1 is a pipe, not the
    # terminal, and [ -t 1 ] is always false. fd 3 is the pre-tee stdout (see the
    # `exec 3>&1` redirect up top) — the real tty. Detect on it, paint the TUI to
    # it (so the escapes reach the screen and never pollute build.log), and read
    # keys from fd 0 (the terminal, which was never redirected).
    local tty=0; [ -t 3 ] && tty=1
    # `return 0` matters under `set -e`: without it, a false `[ tty = 1 ]` test
    # makes the function return 1 and aborts the script (e.g. non-TTY --init).
    _home()  { [ "$tty" = 1 ] && printf '\e[H\e[J' >&3; return 0; }
    _show()  { [ "$tty" = 1 ] && printf '\e[?25h' >&3; return 0; }
    _hide()  { [ "$tty" = 1 ] && printf '\e[?25l' >&3; return 0; }
    # Restore the cursor if the user interrupts.
    trap '_show; exit 130' INT
    _hide

    local key expanded p c mi arrow box label pre
    while true; do
        _home
        echo ""
        print_banner
        echo ""
        echo "  Build a profile — choose the modules to include:"
        echo ""
        expanded="${pos_cat[$cursor]}"
        for p in $(seq 0 $((npos - 1))); do
            c="${pos_cat[$p]}"; mi="${pos_mod[$p]}"
            [ "$p" = "$cursor" ] && pre="›" || pre=" "
            if [ "$mi" = "-1" ]; then
                [ "$c" = "$expanded" ] && arrow="▾" || arrow="▸"
                label=$(printf '%s %-9s %s' "$arrow" "$(_tier_label "$c")" "($(_tier_stats "$c"))")
            else
                [ "$c" = "$expanded" ] || continue
                box="[ ]"; _chk_has "${mods[$mi]}" && box="[x]"
                label=$(printf '    %s %s' "$box" "${mods[$mi]}")
            fi
            if [ "$p" = "$cursor" ] && [ "$tty" = 1 ]; then
                printf '\e[7m %s %s \e[0m\n' "$pre" "$label"
            else
                printf ' %s %s\n' "$pre" "$label"
            fi
        done
        echo ""
        echo "  ↑/↓ move · space toggle · enter save & build · q quit"

        key=$(_init_read_key)
        case "$key" in
            up)    [ "$cursor" -gt 0 ] && cursor=$((cursor - 1)) ;;
            down)  [ "$cursor" -lt $((npos - 1)) ] && cursor=$((cursor + 1)) ;;
            space)
                mi="${pos_mod[$cursor]}"
                if [ "$mi" != "-1" ]; then
                    local sel="${mods[$mi]}"
                    if _chk_has "$sel"; then checked="${checked/ $sel / }"
                    else checked="${checked}${sel} "; fi
                else
                    # Header: toggle the whole tier (all on → all off, else all on).
                    c="${pos_cat[$cursor]}"; local g all=1
                    for g in "${!mods[@]}"; do
                        [ "${tiers[$g]}" = "$c" ] || continue
                        _chk_has "${mods[$g]}" || { all=0; break; }
                    done
                    for g in "${!mods[@]}"; do
                        [ "${tiers[$g]}" = "$c" ] || continue
                        local mm="${mods[$g]}"
                        if [ "$all" = 1 ]; then checked="${checked/ $mm / }"
                        elif ! _chk_has "$mm"; then checked="${checked}${mm} "; fi
                    done
                fi ;;
            enter) break ;;
            quit)  _home; _show; trap - INT; echo "No profile created." >&3; exit 1 ;;
            *) ;;
        esac
    done >&3
    _home; _show; trap - INT

    local -a selected=()
    local mm
    for mm in "${mods[@]}"; do _chk_has "$mm" && selected+=("$mm"); done
    if [ "${#selected[@]}" -eq 0 ]; then
        echo "No modules selected — nothing to build." >&3; exit 1
    fi
    echo "Selected ${#selected[@]} modules." >&3

    local pname=""
    while [ -z "$pname" ]; do
        printf '  name this profile: ' >&3
        read -r pname || { echo "Aborted." >&3; exit 1; }
        case "$pname" in
            "") ;;
            *[!A-Za-z0-9_-]*) echo "  use letters, digits, - or _ only" >&3; pname="" ;;
            *) if [ -f "${PROFILES_DIR}/${pname}.conf" ]; then
                   echo "  '${pname}' is a built-in profile — pick another name" >&3; pname=""
               fi ;;
        esac
    done

    mkdir -p "$LOCAL_PROFILES_DIR"
    local out="${LOCAL_PROFILES_DIR}/${pname}.conf"
    {
        echo "# desc: your own selection of ${#selected[@]} modules (via ./build.sh --init)"
        echo "# Gitignored (.conf/); yours alone. Bare module names, one per line."
        echo "# Rebuild it any time with:  ./build.sh ${pname}"
        echo ""
        for mm in "${selected[@]}"; do echo "$mm"; done
    } > "$out"
    echo "Wrote ${out}" >&3

    PROFILES=("$pname")
}

# --- Parse arguments: collect the profile, one platform, and Maven args ---
PROFILES=()
PLATFORM=""
MAVEN_ARGS=()
LIST_ONLY=false
INIT_REQUESTED=false

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
    elif [ "$arg" = "--list" ]; then
        LIST_ONLY=true
    elif [ "$arg" = "--init" ]; then
        INIT_REQUESTED=true
    elif [ "$arg" = "cleanAll" ]; then
        REMOVE_ALL_DIST=true
        MAVEN_ARGS+=("clean")
    elif [[ "$arg" == -* ]]; then
        MAVEN_ARGS+=("$arg")
    elif [ -n "$(profile_conf_path "$arg")" ]; then
        PROFILES+=("$arg")
    elif [ -z "$PLATFORM" ] && [ -f "${PLATFORMS_DIR}/${arg}.conf" ]; then
        PLATFORM="$arg"
    else
        MAVEN_ARGS+=("$arg")
    fi
done

# --list: print what's available and stop, before any build setup.
if [ "$LIST_ONLY" = true ]; then
    list_profiles
    exit 0
fi

# --init: build a new profile interactively now; it sets PROFILES so the build
# below proceeds with it. (Runs before platform/goal setup — it only needs the
# module tree.)
if [ "$INIT_REQUESTED" = true ]; then
    init_profile
fi

# Note: the old -Dblade.skip.dist flag (read by services/pom.xml's copy-dist
# exec step) is no longer passed — that exec step is commented out along with
# the EAR. The dist copy is now done entirely from build.sh, gated by SKIP_DIST.

# A profile is now required for any build, but the requirement is enforced AFTER
# goal classification below (clean-only runs are exempt), so nothing is defaulted
# here. PROFILES may still be empty at this point.
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

# --- Require a profile for any build (clean-only runs are exempt) ---
# A build compiles/produces artifacts, and the admin EAR's contents are derived
# from the profile's module set — so a build must name one. A clean-only run
# (HAS_BUILD_GOAL=false) has no module allowlist and cleans the whole reactor, so
# it needs no profile: that keeps profile-free callers like `./build.sh clean`
# working. On a TTY we offer a picker; without one we fail loudly (never hang).
if [ ${#PROFILES[@]} -eq 0 ] && [ "$HAS_BUILD_GOAL" = true ]; then
    # Interactive when stdin is a tty AND fd 3 (the pre-tee real stdout — build.sh
    # redirects fd 1 through tee) is a tty. Testing fd 1 here would always be
    # false, forcing the non-interactive path even in a real terminal.
    if [ -t 0 ] && [ -t 3 ]; then
        pick_profile
    else
        # No terminal to prompt at (CI, a wrapping build script). Ask politely,
        # but still exit non-zero so the caller knows the build didn't run.
        print_banner >&2
        echo "" >&2
        echo "Please name a build profile — BLADE builds what you ask for, not" >&2
        echo "everything by default." >&2
        echo "" >&2
        list_profiles >&2
        echo "" >&2
        echo "e.g.  ./build.sh ${BASE_PROFILE}      ( ./build.sh --list to see them," >&2
        echo "                          ./build.sh --init to make your own )" >&2
        exit 1
    fi
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

if [ ${#PROFILES[@]} -eq 0 ]; then
    # Clean-only run with no profile (the only way PROFILES is still empty here —
    # a build would have required one above). No allowlist, so no -Dskip flags:
    # the clean reaches the whole reactor. Dist copy is already forced off for
    # clean-only runs, so PROFILE/CONF_FILE are display-only.
    PROFILE="(clean-only — no profile)"
    CONF_FILE=""
    INCLUDED_MODULES=""
    SKIP_FLAGS=()
else
    PROFILE="${PROFILES[0]}"
    CONF_FILE="$(profile_conf_path "$PROFILE")"
    INCLUDED_MODULES=$(read_modules "$CONF_FILE")

    SKIP_FLAGS=()
    while IFS= read -r flag; do
        [ -n "$flag" ] && SKIP_FLAGS+=("$flag")
    done < <(compute_skip_flags "$CONF_FILE" "$ALL_MODULES")
fi

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
