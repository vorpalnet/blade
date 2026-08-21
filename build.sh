#!/usr/bin/env bash
# ============================================================================
# build.sh - Build wrapper for BLADE
#
# build.sh builds the shippable set — libs/admin/services/test/proto plus the
# per-tier EARs — in one Maven reactor. By default it builds everything; a named
# profile can narrow it. The profile is the SAME ~/.blade/<name>/profile.conf that
# deploy.sh and install.sh use, so one selection drives build AND deploy:
#   build.apps=*|<csv>   which apps to compile
#   ear.<tier>=on|off    bundle a tier into blade-<tier>.ear, or ship loose WARs
# Edit that selection with a checkbox tree:  ./build.sh --edit <name>  (or pick
# "create" from the menu a bare `./build.sh` shows on a terminal). A profile
# WITHOUT those keys — and `./build.sh default` from a superproject — builds the
# full set, unchanged. To iterate on one module, run Maven directly, e.g.
# ./mvnw -pl services/hold package.
#
# Two output modes:
#   dev  (default)  flat dist/               + app version <revision>          (redeploys in place)
#   prod (--prod)   dist/<revision>-<build>/ + app version <revision>-<build>  (traceable release)
#
# Usage:
#   ./build.sh [profile|platform] [--edit] [--list] [--dev|--prod] [--no-dist] [--no-parallel] [maven-args...]
#
# Examples:
#   ./build.sh                              # on a terminal: pick a profile / create one / build all
#   ./build.sh --list                       # list the ~/.blade profiles and exit
#   ./build.sh --edit ashburn               # edit ashburn's profile (apps, EARs, dev/prod) — does not build
#   ./build.sh ashburn                      # build ashburn's selection
#   ./build.sh --prod                       # release build: full set → dist/<rev>-<build>/
#   ./build.sh occas-8.2                    # full set, OCCAS 8.2 platform
#   ./build.sh clean package                # explicit Maven goals
#   ./build.sh clean                        # clean-only (purges org.vorpal.blade from ~/.m2)
#   ./build.sh cleanAll                     # clean + delete the entire dist/ tree
#   ./build.sh --no-dist                    # skip the dist/ copy
#   ./build.sh --no-parallel                # dev build, single-threaded Maven
#
# Dev vs prod versioning: WebLogic side-by-side versioning keys off
# WebLogic-Application-Version, so a build number that moves every build mints a NEW
# application version -- the previous one stays registered and blocks an in-place
# redeploy until undeployed by name. dev keeps the version stable (3.0.4) so OCCAS
# replaces the app on redeploy; prod appends the build number for traceable releases.
#   ./build.sh -- -Pfoo                     # build with extra Maven flags
#
# Platform profiles: build-profiles/platforms/*.conf  (the only conf files left)
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
# Per-tier EARs (automatic):
#   Each SHIPPABLE tier builds a per-tier EAR from apps/<tier> — blade-admin.ear,
#   blade-services.ear, blade-test.ear (no proto EAR — proto/ is a grab-bag,
#   deployed ad-hoc as loose WARs). Each component WAR is contributed by an
#   ear-<name> Maven profile; the whole set builds, so each EAR is complete. Both
#   the loose WARs AND the EAR ship — deploy whichever suits (an EAR for a one-shot
#   whole-tier deploy; loose WARs for per-service start/stop/target). The admin EAR
#   carries blade-javadoc.war on a --prod build (see Javadoc).
#
# Dist management:
#   The dist tree depends on the mode. Development writes flat into dist/ (cleaned
#   first each build); production nests each release in dist/<rev>-<build>/. Either
#   way the layout under <dist> is:
#     <dist>/            the whole-tier EARs (blade-admin/services/test.ear) + build.log
#     <dist>/lib/        blade-framework.jar, blade-shared.war, blade-fsmar.jar
#     <dist>/admin/      loose admin WARs (the same apps as blade-admin.ear)
#     <dist>/services/   loose service WARs
#     <dist>/test/       loose test WARs
#     <dist>/proto/      loose incubator WARs (no EAR)
#   Deploy a whole-tier EAR from the root, or a loose WAR from its folder. build.log
#   (the full build console) lands in <dist> so a build can be reviewed after the
#   fact. On failure a prod build's release directory is removed; a dev build leaves
#   the prior flat output in place (dist/ is never wholesale deleted).
#
#   To skip the copy entirely (useful in local dev loops where you don't need
#   the dist/ folder rewritten on every build):
#     ./build.sh --no-dist                  # one-off
#     export BLADE_SKIP_DIST=1              # sticky for the current shell
#
#   --no-dist on the CLI overrides BLADE_SKIP_DIST=0 from the environment.
#
# Parallel builds:
#   A dev build runs Maven with -T 1C (one thread per core), since the whole
#   reactor builds every time. A prod build stays single-threaded — its two-pass
#   javadoc/EAR assembly is order-sensitive. Opt out with --no-parallel (one-off)
#   or BLADE_NO_PARALLEL=1 (sticky), e.g. to read a non-interleaved reactor log.
#
# Javadoc:
#   The javadoc app aggregates every module's apidocs into blade-javadoc.war
#   (bundled in the admin EAR). It is the slow part, so it is built for a --prod
#   release and SKIPPED in dev for a fast loop. Because it must run AFTER every
#   module has generated its apidocs, a prod build does it in a final pass (see the
#   JAVADOC_ON block), so the WAR is complete regardless of reactor order. BLADE's
#   ///-Markdown doc comments (JEP 467) need the javadoc tool from a JDK >= 23; on
#   an older build JDK the docs are dropped with a warning (bytecode still Java 11).
#
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# build-profiles/ now holds only platforms/ (the OCCAS target confs). The
# module-selection confs (default/full/minimal) and their picker are retired —
# build.sh always builds the full shippable set.
PLATFORMS_DIR="${SCRIPT_DIR}/build-profiles/platforms"

# Shared profile helpers (listing, the numbered picker, key upsert) — the same
# ones deploy.sh/install.sh use, so all three enumerate one ~/.blade pool and
# prompt identically when no profile is named. The app/EAR tree editor itself
# lives here in build.sh (below) since only build.sh selects what to compile.
# shellcheck source=misc/blade-profile.sh
. "${SCRIPT_DIR}/misc/blade-profile.sh"

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
            && echo "Wrote ${DIST_REL:-dist}/build.log" >&3
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
# Used for the duplicate-name check and the dist copy — build.sh builds the full
# set, so this is a census, not a selection. Each module is still profile-activated
# in the parent pom via !skip.<name>, but nothing passes -Dskip anymore, so every
# discovered module builds.
discover_modules() {
    # apps/ (the per-tier EAR modules) is intentionally NOT discovered: the EARs
    # build automatically and are copied to the dist per tier separately.
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

# --- Remove the current build's dist directory on failure ---
cleanup_failed_dist() {
    # Only a production build owns a whole versioned directory to discard. A dev
    # build writes flat into dist/, so its failure must never rm the shared dist/
    # root (which also holds prod dist/<rev>-<build>/ releases) — leave the prior
    # flat output in place instead.
    if [ "${DISTDIR:-}" != "${SCRIPT_DIR}/dist" ] && [ -d "${DISTDIR:-}" ]; then
        rm -rf "$DISTDIR"
        echo "Build failed — removed ${DIST_REL:-dist}/"
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
    # A development build writes flat into dist/; clear the previous flat output
    # first so stale artifacts (a WAR from a module since removed, an old EAR)
    # don't accrue at the stable path. Only the flat surface is cleared — the
    # prod dist/<rev>-<build>/ release directories are left untouched.
    if [ "$DISTDIR" = "${SCRIPT_DIR}/dist" ]; then
        rm -rf "$DISTDIR"/lib "$DISTDIR"/admin "$DISTDIR"/services "$DISTDIR"/test "$DISTDIR"/proto
        rm -f "$DISTDIR"/*.ear "$DISTDIR"/*.war "$DISTDIR"/*.jar \
              "$DISTDIR"/DEPLOYMENT.txt "$DISTDIR"/build.log "$DISTDIR"/*.conf 2>/dev/null || true
    fi
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

    # The per-tier EARs (apps/<tier> → blade-<tier>.ear) go at the dist ROOT — the
    # "deploy the whole tier" units, sitting prominently above the per-tier folders
    # that hold the loose WARs. Deploy an EAR from the root, or a loose WAR from
    # its folder.
    local eardir earf
    for eardir in "${SCRIPT_DIR}"/apps/*/; do
        # Only the shippable tiers (admin/services/test) have a whole-tier EAR;
        # proto has none. A stale apps/proto/target/blade-proto.ear from an old
        # 'full' build would otherwise be swept into every later dist, because
        # 'clean' only touches the active profile's reactor, not proto's target/.
        case "$eardir" in */proto/) continue ;; esac
        # A tier whose EAR flag is off ships loose WARs only — its .ear isn't built
        # this run, so don't sweep a stale one from a previous build into dist.
        _et="$(basename "$eardir")"
        case " ${EAR_SKIP} " in *" ${_et} "*) continue ;; esac
        [ -d "${eardir}target" ] || continue
        for earf in "${eardir}target"/*.ear; do
            [ -f "$earf" ] || continue
            cp -f "$earf" "$DISTDIR/"
            copied=$((copied + 1))
        done
    done

    [ -n "$CONF_FILE" ] && cp -f "$CONF_FILE" "$DISTDIR/" 2>/dev/null && copied=$((copied + 1))
    cp -f "${PLATFORMS_DIR}/${PLATFORM}.conf" "$DISTDIR/" 2>/dev/null && copied=$((copied + 1))

    echo "Copied ${copied} artifacts to ${DIST_REL}/"
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
            blade-services.ear)
                echo "services|cluster|Services tier EAR — every engine-tier service in one deployable" ;;
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
        echo "See DEPLOYING.md for the deployment model (whole-tier EARs at root; loose WARs in folders)."
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

# --- Parse arguments: collect the platform and Maven args ---
PLATFORM=""
MAVEN_ARGS=()
LIST_ONLY=false
INIT_REQUESTED=false
EDIT_REQUESTED=false
# A legacy module-profile name (default/full/minimal) passed by a consumer's
# build.sh — accepted and ignored so those builds keep working (see below).
IGNORED_PROFILE_ARG=""

# Sticky dev-mode switch: BLADE_SKIP_DIST=1 in the env disables dist copying.
# The CLI flag --no-dist always wins (for one-off explicit override).
SKIP_DIST=false
case "${BLADE_SKIP_DIST:-}" in
    1|true|yes|on) SKIP_DIST=true ;;
esac

# Opt out of the dev-loop parallel build (-T). BLADE_NO_PARALLEL in the env is
# sticky; the --no-parallel flag is a one-off override.
NO_PARALLEL=false
case "${BLADE_NO_PARALLEL:-}" in
    1|true|yes|on) NO_PARALLEL=true ;;
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
# Override the default with BLADE_MODE=prod in the environment. A named
# deployment profile (below) can also carry the mode via build.mode=; an explicit
# --dev/--prod on the CLI always wins over the profile.
BLADE_MODE="${BLADE_MODE:-dev}"
MODE_EXPLICIT=false
ENV_PROFILE=""
BLADE_HOME="${BLADE_HOME:-$HOME/.blade}"

# Read a key=value from a conf file (ENC(...) secrets are unwrapped). Same shape
# as the helper in install.sh / deploy.sh, so the three tools read one profile.
read_prop() {
    local v
    v="$({ grep "^$2=" "$1" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$v" in ENC\(*\)) v="${v#ENC(}"; v="${v%)}" ;; esac
    printf '%s' "$v"
}

# Path to a named deployment profile's conf. The new layout
# (~/.blade/<name>/profile.conf) wins over the legacy flat file
# (~/.blade/<name>.conf); empty if neither exists.
env_profile_conf() {
    if [ -f "${BLADE_HOME}/$1/profile.conf" ]; then printf '%s' "${BLADE_HOME}/$1/profile.conf"
    elif [ -f "${BLADE_HOME}/$1.conf" ]; then printf '%s' "${BLADE_HOME}/$1.conf"; fi
}

# ============================================================================
# App / EAR selection — the shared profile carries build.apps + ear.<tier>
# ============================================================================
# build.apps=*        build every discovered app (default; auto-includes new apps)
# build.apps=a,b,c    build only these (bare module names, globally unique)
# ear.admin=on|off    on  → bundle the tier's selected WARs into blade-<tier>.ear
#                     off → ship them as loose WARs (skip the .ear assembler)
# Defaults preserve today's deploy shape: admin/test bundled, services loose.
# proto has no EAR (loose only). These keys are written ONLY by the tree editor
# below; a profile without them (a plain deploy profile) builds the full set, so
# existing profiles and `./build.sh default` are unaffected.
EAR_TIERS="admin services test"
EAR_SKIP=""   # tiers whose .ear is skipped this build (set by apply_profile_selection)

# Default EAR flag for a tier (services ships loose; see memory on FSMAR routing).
ear_default() { case "$1" in services) echo off ;; *) echo on ;; esac; }

# EAR flag for a tier from a conf, falling back to the default.
ear_flag() {
    local v; v="$(read_prop "$1" "ear.$2")"
    case "$v" in on|off) printf '%s' "$v" ;; *) ear_default "$2" ;; esac
}

# Is <module> selected per build.apps in <conf>? '*' or empty ⇒ everything.
app_selected() {
    local apps; apps="$(read_prop "$1" build.apps)"
    case "$apps" in ""|"*") return 0 ;; esac
    case ",${apps}," in *,"$2",*) return 0 ;; esac
    return 1
}

# Does this conf carry an app/EAR selection at all? Only then do we translate it
# into -Dskip flags — a bare deploy profile builds the full set as before.
profile_has_selection() {
    grep -Eq '^(build\.apps|ear\.(admin|services|test))=' "$1" 2>/dev/null
}

# Translate a profile's selection into -Dskip.* flags + a filtered
# INCLUDED_MODULES. Any deselected module is skipped (dropped from the reactor AND,
# for an app, its EAR); a tier with ear.<tier>=off skips only the whole-tier .ear
# (its WARs still build loose). The libraries (framework/shared/fsmar) are
# selectable too — deselecting one skips its rebuild and Maven resolves the copy a
# prior build installed in ~/.m2, cutting compile time. Skip a library nothing has
# built yet and Maven fails loudly on the unresolved dependency — the user's call.
apply_profile_selection() {
    local conf="$1" mod tier kept=""
    while IFS= read -r mod; do
        [ -n "$mod" ] || continue
        if app_selected "$conf" "$mod"; then kept="${kept}${mod}"$'\n'
        else SKIP_FLAGS+=("-Dskip.${mod}"); fi
    done <<< "$INCLUDED_MODULES"
    INCLUDED_MODULES="${kept%$'\n'}"
    for tier in $EAR_TIERS; do
        if [ "$(ear_flag "$conf" "$tier")" = off ]; then
            SKIP_FLAGS+=("-Dskip.${tier}"); EAR_SKIP="${EAR_SKIP} ${tier}"
        fi
    done
}

# --- The interactive app/EAR tree editor (writes into the shared profile) ---
# Read one keystroke over /dev/tty, mapping arrows/space/enter/a/q to words.
# /dev/tty (not fd 0) because build.sh tees stdout to a log and the picker paints
# to the real terminal.
_bp_esc_t=0.05; [ "${BASH_VERSINFO[0]}" -lt 4 ] && _bp_esc_t=1
_bp_read_key() {
    local k r
    IFS= read -rsn1 k < /dev/tty 2>/dev/null || { printf 'quit'; return; }
    case "$k" in
        $'\e') IFS= read -rsn2 -t "$_bp_esc_t" r < /dev/tty 2>/dev/null || r=""
               case "$r" in '[A'|'OA') printf 'up' ;; '[B'|'OB') printf 'down' ;; *) printf 'other' ;; esac ;;
        ' ')            printf 'space' ;;
        ''|$'\n'|$'\r') printf 'enter' ;;
        a|A)            printf 'all' ;;
        m|M)            printf 'mode' ;;
        s|S)            printf 'save' ;;
        j|J)            printf 'down' ;;
        k|K)            printf 'up' ;;
        q|Q)            printf 'quit' ;;
        *)              printf 'other' ;;
    esac
}

# Prompt for a new profile name over /dev/tty; echo it. Returns 1 on EOF.
prompt_new_profile_name() {
    local name=""
    while [ -z "$name" ]; do
        printf '  new profile name: ' > /dev/tty
        IFS= read -r name < /dev/tty || return 1
        case "$name" in
            "") ;;
            *[!A-Za-z0-9_-]*) echo "  letters, digits, - or _ only." > /dev/tty; name="" ;;
            *) [ -f "${PLATFORMS_DIR}/${name}.conf" ] && { echo "  '${name}' is a platform name — pick another." > /dev/tty; name=""; } ;;
        esac
    done
    printf '%s' "$name"
}

# Full-screen accordion: choose which apps compile, and which tiers bundle into an
# EAR. Writes build.apps + ear.<tier> into <conf> ($2 = display name). Renders
# over /dev/tty. State is padded strings (bash 3.2 has no associative arrays).
# Colour is never used — checkbox shape/position and the '›' cursor carry state.
edit_profile_apps() {
    local conf="$1" pname="${2:-$1}"
    [ -t 3 ] || { echo "No terminal — cannot edit a profile interactively." >&2; return 1; }

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
    [ "${#mods[@]}" -gt 0 ] || { echo "No apps found to choose from." >&2; return 1; }

    local checked=" " m
    for m in "${mods[@]}"; do app_selected "$conf" "$m" && checked="${checked}${m} "; done
    _has() { case "$checked" in *" $1 "*) return 0 ;; esac; return 1; }

    local mode; mode="$(read_prop "$conf" build.mode)"
    case "$mode" in production|prod) mode=prod ;; *) mode=dev ;; esac

    local ear_admin ear_services ear_test
    ear_admin=$(ear_flag "$conf" admin); ear_services=$(ear_flag "$conf" services); ear_test=$(ear_flag "$conf" test)
    _ear_get() { case "$1" in admin) echo "$ear_admin" ;; services) echo "$ear_services" ;; test) echo "$ear_test" ;; esac; }
    _ear_tog() { case "$1" in
        admin)    [ "$ear_admin" = on ]    && ear_admin=off    || ear_admin=on ;;
        services) [ "$ear_services" = on ] && ear_services=off || ear_services=on ;;
        test)     [ "$ear_test" = on ]     && ear_test=off     || ear_test=on ;;
    esac; }

    local -a pos_cat=() pos_mod=()
    local gi prev="__none__"
    for gi in "${!mods[@]}"; do
        if [ "${tiers[$gi]}" != "$prev" ]; then prev="${tiers[$gi]}"; pos_cat+=("$prev"); pos_mod+=("-1"); fi
        pos_cat+=("${tiers[$gi]}"); pos_mod+=("$gi")
    done
    local npos=${#pos_cat[@]}
    _tier_stats() { local t="$1" n=0 tot=0 g; for g in "${!mods[@]}"; do [ "${tiers[$g]}" = "$t" ] || continue; tot=$((tot+1)); _has "${mods[$g]}" && n=$((n+1)); done; printf '%d/%d' "$n" "$tot"; }

    local cursor=1 key p c mi arrow box label pre earbox expanded sel g all mm
    printf '\e[?25l' > /dev/tty
    trap 'printf "\e[?25h" > /dev/tty; return 130' INT
    while true; do
        printf '\e[H\e[J' > /dev/tty
        {
            echo ""
            echo "  BLADE — choose apps to build   (profile: ${pname} · mode: $(echo "$mode" | tr a-z A-Z))"
            echo ""
            expanded="${pos_cat[$cursor]}"
            for p in $(seq 0 $((npos - 1))); do
                c="${pos_cat[$p]}"; mi="${pos_mod[$p]}"
                [ "$p" = "$cursor" ] && pre="›" || pre=" "
                if [ "$mi" = "-1" ]; then
                    [ "$c" = "$expanded" ] && arrow="▾" || arrow="▸"
                    case "$c" in
                        libs)  earbox="  libraries" ;;
                        proto) earbox="  loose only" ;;
                        *)     earbox="  [$([ "$(_ear_get "$c")" = on ] && echo x || echo ' ')] EAR" ;;
                    esac
                    label=$(printf '%s %-9s%s  (%s)' "$arrow" "$(echo "$c" | tr a-z A-Z)" "$earbox" "$(_tier_stats "$c")")
                else
                    [ "$c" = "$expanded" ] || continue
                    box="[ ]"; _has "${mods[$mi]}" && box="[x]"
                    label=$(printf '      %s %s' "$box" "${mods[$mi]}")
                fi
                if [ "$p" = "$cursor" ]; then printf '\e[7m %s %s \e[0m\n' "$pre" "$label"
                else printf ' %s %s\n' "$pre" "$label"; fi
            done
            echo ""
            echo "  ↑/↓ move · space toggle (app, or a tier's EAR) · a all-in-tier · m dev/prod"
            echo "  s save & exit · q quit without saving"
        } > /dev/tty
        key=$(_bp_read_key)
        case "$key" in
            up)   [ "$cursor" -gt 0 ] && cursor=$((cursor - 1)) ;;
            down) [ "$cursor" -lt $((npos - 1)) ] && cursor=$((cursor + 1)) ;;
            space)
                mi="${pos_mod[$cursor]}"
                if [ "$mi" != "-1" ]; then
                    sel="${mods[$mi]}"
                    if _has "$sel"; then checked="${checked/ $sel / }"; else checked="${checked}${sel} "; fi
                else
                    c="${pos_cat[$cursor]}"; case "$c" in admin|services|test) _ear_tog "$c" ;; esac
                fi ;;
            all)
                c="${pos_cat[$cursor]}"; all=1
                for g in "${!mods[@]}"; do [ "${tiers[$g]}" = "$c" ] || continue; _has "${mods[$g]}" || { all=0; break; }; done
                for g in "${!mods[@]}"; do
                    [ "${tiers[$g]}" = "$c" ] || continue
                    mm="${mods[$g]}"
                    if [ "$all" = 1 ]; then checked="${checked/ $mm / }"
                    elif ! _has "$mm"; then checked="${checked}${mm} "; fi
                done ;;
            mode) [ "$mode" = prod ] && mode=dev || mode=prod ;;
            save|enter) break ;;
            quit)  printf '\e[?25h' > /dev/tty; trap - INT; echo "  (cancelled — profile unchanged)" > /dev/tty; return 1 ;;
        esac
    done
    printf '\e[?25h' > /dev/tty; trap - INT

    local -a picked=(); for m in "${mods[@]}"; do _has "$m" && picked+=("$m"); done
    local apps_val
    if   [ "${#picked[@]}" -eq "${#mods[@]}" ]; then apps_val="*"
    elif [ "${#picked[@]}" -eq 0 ];             then apps_val=""
    else apps_val="$(IFS=,; echo "${picked[*]}")"; fi

    blade_set_prop "$conf" build.mode "$mode"
    blade_set_prop "$conf" build.apps "$apps_val"
    blade_set_prop "$conf" ear.admin "$ear_admin"
    blade_set_prop "$conf" ear.services "$ear_services"
    blade_set_prop "$conf" ear.test "$ear_test"
    echo "  saved ${conf}" > /dev/tty
    echo "  mode=${mode} · ${#picked[@]}/${#mods[@]} apps · EAR admin=${ear_admin} services=${ear_services} test=${ear_test}" > /dev/tty
}

# Interactive: pick / create / edit a profile for this build. Sets ENV_PROFILE
# (global; may stay empty for a full-set build) and returns 0 to build it. Any path
# that opens the app/EAR editor (--edit, or "create" from the picker) EXITS after
# saving — editing configures the profile, it never builds; build with
# `./build.sh <profile>`. Returns 1 if the picker was cancelled (nothing to build).
resolve_build_profile() {
    local pick c
    if [ "$EDIT_REQUESTED" = true ]; then
        if [ -z "$ENV_PROFILE" ]; then
            pick="$(blade_pick_profile)" || return 1
            case "$pick" in
                __create__) ENV_PROFILE="$(prompt_new_profile_name)" || return 1 ;;
                *)          ENV_PROFILE="$pick" ;;
            esac
        fi
        c="$(blade_profile_conf_path "$ENV_PROFILE")"; [ -n "$c" ] || c="${BLADE_HOME}/${ENV_PROFILE}/profile.conf"
        if edit_profile_apps "$c" "$ENV_PROFILE"; then
            echo "Saved '${ENV_PROFILE}'. Build it with:  ./build.sh ${ENV_PROFILE}"
        fi
        exit 0    # --edit configures the profile; it never builds. Build separately.
    fi
    # Bare build, no profile named → list/create (skip for a legacy default arg).
    if [ -z "$ENV_PROFILE" ] && [ -z "$IGNORED_PROFILE_ARG" ]; then
        pick="$(blade_pick_profile 'A|build the full shippable set (no profile)')" || return 1
        case "$pick" in
            __A__)      ENV_PROFILE="" ;;
            __create__) ENV_PROFILE="$(prompt_new_profile_name)" || return 1
                        if edit_profile_apps "${BLADE_HOME}/${ENV_PROFILE}/profile.conf" "$ENV_PROFILE"; then
                            echo "Created '${ENV_PROFILE}'. Build it with:  ./build.sh ${ENV_PROFILE}"
                        fi
                        exit 0 ;;   # editing a profile configures it; it never builds
            *)          ENV_PROFILE="$pick" ;;
        esac
    fi
    return 0
}

for arg in "$@"; do
    if [ "$arg" = "--" ]; then
        continue
    elif [ "$arg" = "--no-dist" ]; then
        SKIP_DIST=true
    elif [ "$arg" = "--no-parallel" ]; then
        NO_PARALLEL=true
    elif [ "$arg" = "--prod" ]; then
        BLADE_MODE=prod; MODE_EXPLICIT=true
    elif [ "$arg" = "--dev" ]; then
        BLADE_MODE=dev; MODE_EXPLICIT=true
    elif [ "$arg" = "--list" ]; then
        LIST_ONLY=true
    elif [ "$arg" = "--init" ]; then
        INIT_REQUESTED=true
    elif [ "$arg" = "--edit" ]; then
        EDIT_REQUESTED=true
    elif [ "$arg" = "cleanAll" ]; then
        REMOVE_ALL_DIST=true
        MAVEN_ARGS+=("clean")
    elif [[ "$arg" == -* ]]; then
        MAVEN_ARGS+=("$arg")
    elif [ -n "$(env_profile_conf "$arg")" ]; then
        # A named deployment profile (~/.blade/<name>[/profile].conf) — the same
        # profile install.sh/deploy.sh use. Take its build.mode unless the CLI
        # named one explicitly. build.sh needs nothing else from it.
        ENV_PROFILE="$arg"
        case "$(read_prop "$(env_profile_conf "$arg")" build.mode)" in
            production|prod) [ "$MODE_EXPLICIT" = true ] || BLADE_MODE=prod ;;
            development|dev) [ "$MODE_EXPLICIT" = true ] || BLADE_MODE=dev ;;
        esac
    elif [ "$arg" = "default" ] || [ "$arg" = "full" ] || [ "$arg" = "minimal" ]; then
        # A retired module-profile name. optum/att-tao invoke `./build.sh default`;
        # accept and ignore it so those builds keep working (noted before the build).
        IGNORED_PROFILE_ARG="$arg"
    elif [ -z "$PLATFORM" ] && [ -f "${PLATFORMS_DIR}/${arg}.conf" ]; then
        PLATFORM="$arg"
    else
        MAVEN_ARGS+=("$arg")
    fi
done

# --list prints the shared profile pool and exits. --init is a synonym for --edit
# (create/refine a profile's app+EAR selection); the editor runs from the
# interactive-resolution block once the build goal is known.
if [ "$LIST_ONLY" = true ]; then
    echo "BLADE profiles (~/.blade):"
    _pl="$(blade_list_profiles)"
    [ -n "$_pl" ] && printf '%s\n' "$_pl" | sed 's/^/  /' || echo "  (none yet)"
    exit 0
fi
[ "$INIT_REQUESTED" = true ] && EDIT_REQUESTED=true

# Note: the old -Dblade.skip.dist flag (read by services/pom.xml's copy-dist
# exec step) is no longer passed — that exec step is commented out along with
# the EAR. The dist copy is now done entirely from build.sh, gated by SKIP_DIST.

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
# Production is archival: each build lands in its own dist/<rev>-<build>/ so
# releases sit side by side and nothing is ever overwritten. Development is a
# scratch surface: it writes straight into dist/ (flat, cleaned first), so the
# path is stable for a fast edit/deploy loop and stale artifacts don't pile up.
# DIST_REL is the same path, relative, for display and the deploy hand-off.
if [ "$BLADE_MODE" = prod ]; then
    DISTDIR="${SCRIPT_DIR}/dist/${REVISION}-${BUILD_NUM}"
    DIST_REL="dist/${REVISION}-${BUILD_NUM}"
else
    DISTDIR="${SCRIPT_DIR}/dist"
    DIST_REL="dist"
fi

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

# --- Interactive profile resolution (TTY + build goal only) ---
# --edit opens the app/EAR tree editor; a bare build with no profile lists the
# pool or offers to create one (Jeff: "if a profile isn't provided, list or
# create"). Guarded on a real terminal, so a non-interactive `./build.sh` in CI
# and superproject calls like `./build.sh default` fall straight through to the
# full set and never block.
# [ -t 3 ] is the interactivity test: fd 3 is the pre-tee original stdout (see the
# `exec 3>&1` up top). It is the ONLY reliable terminal check here — [ -t 1 ] is
# always false (stdout is tee'd to the build log) and [ -e /dev/tty ] is true even
# in CI (the device node exists but can't be opened), which would wrongly divert a
# non-interactive build into the picker and abort it.
if [ "$HAS_BUILD_GOAL" = true ] && [ -t 3 ] \
   && { [ "$EDIT_REQUESTED" = true ] || [ -z "$ENV_PROFILE" ]; }; then
    resolve_build_profile || { echo "Nothing to build."; exit 0; }
    # A profile chosen/created just now may carry build.mode; honour it unless the
    # CLI already named --dev/--prod.
    if [ -n "$ENV_PROFILE" ] && [ "$MODE_EXPLICIT" != true ]; then
        case "$(read_prop "$(env_profile_conf "$ENV_PROFILE")" build.mode)" in
            production|prod) BLADE_MODE=prod ;;
            development|dev) BLADE_MODE=dev ;;
        esac
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

# By default build.sh builds the whole shippable set — every discovered module
# (libs/admin/services/test/proto) plus the per-tier EARs, no -Dskip flags (javadoc
# excepted, below). A ~/.blade profile that carries an app/EAR selection narrows it
# (apply_profile_selection). The LEGACY module-set names default/full/minimal are
# retired: accepted and ignored so optum/att-tao's `./build.sh default` keeps
# working. Clean-only runs build nothing.
CONF_FILE=""
SKIP_FLAGS=()
if [ "$HAS_BUILD_GOAL" != true ]; then
    PROFILE="(clean-only)"
    INCLUDED_MODULES=""
else
    PROFILE="full set"
    if [ -n "$IGNORED_PROFILE_ARG" ]; then
        echo "Note: the module-set names (default/full/minimal) are retired — building the full set (ignoring '${IGNORED_PROFILE_ARG}')."
    fi
    INCLUDED_MODULES="$ALL_MODULES"
    # A profile that carries an app/EAR selection (written by the tree editor)
    # narrows the build: deselected apps get -Dskip.<app>; a tier with its EAR off
    # gets -Dskip.<tier> (its WARs still build loose). A plain deploy profile with
    # no selection keys builds the full set, unchanged.
    if [ -n "$ENV_PROFILE" ]; then
        _selconf="$(env_profile_conf "$ENV_PROFILE")"
        if [ -n "$_selconf" ] && profile_has_selection "$_selconf"; then
            CONF_FILE="$_selconf"
            PROFILE="profile: ${ENV_PROFILE}"
            apply_profile_selection "$_selconf"
        fi
    fi
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

# --- Silence Maven's own JDK warnings (not BLADE's) ---
# Maven 3.9.x bundles jansi + guava; on a recent JDK those trip two runtime
# warnings from ~/.m2/wrapper/dists — jansi's System::load (native access) and
# guava's sun.misc.Unsafe::objectFieldOffset. Neither touches BLADE's compile.
# The flags that quiet them are version-gated, so add each ONLY when the build
# JDK understands it — an older JDK hard-fails on an unrecognized VM option:
#   --enable-native-access       JDK 17+
#   --sun-misc-unsafe-memory-access  JDK 24+
# mvnw applies $MAVEN_OPTS to the Maven JVM. Direct ./mvnw runs don't get this
# (they still warn harmlessly); the flags only silence, they change nothing.
if [ -n "${BUILD_JDK_MAJOR:-}" ]; then
    _maven_jdk_opts=""
    [ "$BUILD_JDK_MAJOR" -ge 17 ] 2>/dev/null && _maven_jdk_opts="--enable-native-access=ALL-UNNAMED"
    [ "$BUILD_JDK_MAJOR" -ge 24 ] 2>/dev/null && _maven_jdk_opts="${_maven_jdk_opts} --sun-misc-unsafe-memory-access=allow"
    [ -n "$_maven_jdk_opts" ] && export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }${_maven_jdk_opts}"
fi

# --- Javadoc: driven by the profile, built in a final pass ---
# `javadoc` is a normal profile module now: listing it in the active profile
# includes admin/javadoc, which aggregates every module's apidocs into
# blade-javadoc.war (bundled in blade-admin.ear). Two facts shape the handling:
#   * BLADE's ///-Markdown doc comments (JEP 467) need the javadoc tool from a
#     JDK >= 23. On an older build JDK we can't render them, so we DROP javadoc
#     (force -Dskip.javadoc) with a warning — the rest of the build is unaffected.
#   * collect-javadocs.sh (in admin/javadoc) must run AFTER every module has
#     generated its apidocs. Instead of relying on reactor order, JAVADOC_ON
#     triggers a two-pass build below: generate everything (-Pjavadoc-gen), then
#     build admin/javadoc + the admin EAR alone, over the now-complete set.
JAVADOC_MIN_JDK=23
JAVADOC_ON=false
JAVADOC_OLD_JDK=false
jdk_ok_for_javadoc=false
if [ -n "${BUILD_JDK_MAJOR:-}" ] && [ "${BUILD_JDK_MAJOR}" -ge "$JAVADOC_MIN_JDK" ] 2>/dev/null; then
    jdk_ok_for_javadoc=true
fi
# Javadoc is the slow part (it aggregates every module's apidocs into
# blade-javadoc.war). It is built for a PRODUCTION release — complete, and it
# ships to customers — and SKIPPED in development for a fast edit/build loop.
javadoc_selected=false
if [ "$BLADE_MODE" = prod ] && printf '%s\n' "$INCLUDED_MODULES" | grep -qx javadoc; then
    javadoc_selected=true
fi

if [ "$HAS_BUILD_GOAL" != true ]; then
    JAVADOC_STATUS="n/a (clean-only run)"
elif [ "$BLADE_MODE" != prod ]; then
    SKIP_FLAGS+=("-Dskip.javadoc")
    JAVADOC_STATUS="skipped (dev build — javadoc is built for --prod releases)"
elif [ "$javadoc_selected" != true ]; then
    JAVADOC_STATUS="off (javadoc not among the built modules)"
elif [ "$jdk_ok_for_javadoc" = true ]; then
    JAVADOC_ON=true
    JAVADOC_STATUS="generating (final pass → admin/javadoc → blade-javadoc.war)"
else
    # prod build, but this JDK can't render ///-Markdown docs — drop it, don't fail.
    JAVADOC_OLD_JDK=true
    SKIP_FLAGS+=("-Dskip.javadoc")
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
    echo "         Point JAVA_HOME at a JDK ${JAVADOC_MIN_JDK}+ to build docs, or use a profile without javadoc."
fi
echo "Modules: ${INCLUDED_COUNT} of ${TOTAL_COUNT}"
# DIST_MSG distinguishes "user told us to skip" from "nothing to dist anyway",
# and is reused below in the post-build summary so both lines match.
if [ "$HAS_BUILD_GOAL" = false ]; then
    DIST_MSG="n/a (clean-only run)"
elif [ "$SKIP_DIST" = true ]; then
    DIST_MSG="SKIPPED (--no-dist or BLADE_SKIP_DIST set)"
else
    DIST_MSG="${DIST_REL}/"
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

run_maven() { "${SCRIPT_DIR}/mvnw" -f "${SCRIPT_DIR}/pom.xml" "$@"; }

# Parallel build for the fast dev loop. Every build is now the whole reactor, so
# -T cuts wall-clock roughly in half on a multi-core machine. Dev only: the prod
# path runs a two-pass javadoc install dance (build → collect apidocs → EAR) that
# stays single-threaded for a reproducible release. --no-parallel / BLADE_NO_PARALLEL
# opts out (e.g. to read an interleaved reactor log). One thread per core (-T 1C).
MVN_PARALLEL=()
if [ "$BLADE_MODE" = dev ] && [ "$JAVADOC_ON" != true ] && [ "$NO_PARALLEL" != true ]; then
    MVN_PARALLEL=(-T 1C)
fi

set +e
if [ "$JAVADOC_ON" = true ]; then
    # Two-pass so javadoc collects a COMPLETE apidoc set regardless of reactor
    # order (see the javadoc block above). Pass 1: build + install everything,
    # generating each module's apidocs (-Pjavadoc-gen), but hold back admin/javadoc
    # and the admin EAR. Pass 2: build ONLY those two — admin/javadoc's
    # collect-javadocs.sh now runs after every apidoc exists, and the admin EAR
    # bundles the resulting blade-javadoc.war. Pass 2's deps (framework, admin
    # WARs) resolve from ~/.m2, installed by pass 1.
    # -Dblade.skip.install=false: pass 2 is a separate reactor, so the admin WARs
    # apps/admin bundles must be resolvable from ~/.m2 — install them here (this is
    # the same contract downstream repos rely on; see optum/build.sh Step 0).
    run_maven \
        "${MAVEN_GOALS[@]}" \
        "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}" \
        -Pjavadoc-gen -Dblade.skip.install=false \
        "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" \
        -Dskip.javadoc -Dskip.admin \
        "${PLATFORM_FLAGS[@]+"${PLATFORM_FLAGS[@]}"}" \
        "-Dbuild.number=${BUILD_NUM}" \
        "-Dblade.included.modules=${INCLUDED_CSV}"
    MVN_EXIT=$?
    if [ $MVN_EXIT -eq 0 ]; then
        echo ""
        echo "=== Javadoc pass: collecting apidocs → blade-javadoc.war → admin EAR ==="
        run_maven \
            -pl admin/javadoc,apps/admin \
            "${MAVEN_GOALS[@]}" \
            "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}" \
            "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" \
            "${PLATFORM_FLAGS[@]+"${PLATFORM_FLAGS[@]}"}" \
            "-Dbuild.number=${BUILD_NUM}" \
            "-Dblade.included.modules=${INCLUDED_CSV}"
        MVN_EXIT=$?
    fi
else
    run_maven \
        "${MVN_PARALLEL[@]+"${MVN_PARALLEL[@]}"}" \
        "${MAVEN_GOALS[@]}" \
        "${MAVEN_FLAGS[@]+"${MAVEN_FLAGS[@]}"}" \
        "${SKIP_FLAGS[@]+"${SKIP_FLAGS[@]}"}" \
        "${PLATFORM_FLAGS[@]+"${PLATFORM_FLAGS[@]}"}" \
        "-Dbuild.number=${BUILD_NUM}" \
        "-Dblade.included.modules=${INCLUDED_CSV}"
    MVN_EXIT=$?
fi
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
