#!/usr/bin/env bash
# ============================================================================
# upgrade.sh — bring a running domain up to the BLADE checked out here.
#
# One of five verbs: install.sh stands up the server, build.sh makes artifacts,
# deploy.sh pushes them, patch.sh changes the OCCAS binaries, and upgrade.sh
# takes a domain from the BLADE it runs to the one you just pulled:
#
#   git pull && ./upgrade.sh <env>
#
# It upgrades what is there and adds nothing: an application is redeployed only
# when it is already deployed and this build has it, on the targets it has now.
# Every step is safe to run again, so a run that stops halfway is finished by
# running it again.
#
#   1. preflight   the profile, the domain, the checkout
#   2. build       ./build.sh
#   3. resources   the JMS event bus and the analytics data source
#                  (install.sh's 'jms' and 'datasource', both re-runnable)
#   4. database    SchemaUpgrade: the schema on an empty database, the
#                  migrations it has not had, the views when they changed,
#                  then each directory in the profile's upgrade.sql.modules
#   5. library     blade-shared, when this build's version is not deployed
#   6. approuter   blade-fsmar.jar, when it differs from the one installed
#   7. apps        every deployed application this build has a changed copy
#                  of, redeployed (ArchiveDigest: by content, not bytes)
#   8. report      what changed, and what waits for a restart
#
# Run it on the admin host, where install.sh and deploy.sh run.
#
# Usage:
#   ./upgrade.sh <env> [--dry-run] [--yes] [--sql FILE] [--skip-build] [--prod]
#
#   --dry-run     say what each step would do; change nothing
#   --yes         change the analytics database without asking (unattended runs)
#   --sql FILE    for a database your DBA changes: write step 4's SQL to FILE
#                 and stop. Run upgrade.sh again once it is applied.
#   --skip-build  use the build already in dist/
#   --prod        build with --prod (versioned dist, javadoc)
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=misc/blade-paths.sh
. "${SCRIPT_DIR}/misc/blade-paths.sh"

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    B=$'\e[1m'; D=$'\e[2m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; N=$'\e[0m'
else
    B=""; D=""; G=""; Y=""; R=""; N=""
fi
log()  { printf '%s\n' "$*"; }
step() { printf '\n%s%s%s\n' "$B" "$*" "$N"; }
ok()   { printf '  %sok%s %s\n' "$G" "$N" "$*"; }
warn() { printf '  %swarning:%s %s\n' "$Y" "$N" "$*"; }
die()  { printf '%serror:%s %s\n' "$R" "$N" "$*" >&2; exit 1; }

read_prop() {
    local v
    v="$({ grep "^${2}=" "$1" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$v" in ENC\(*\)) v="${v#ENC(}"; v="${v%)}" ;; esac
    printf '%s' "$v"
}

# --- arguments ---------------------------------------------------------------
ENV_NAME=""; DRY=false; YES=false; SQL_OUT=""; SKIP_BUILD=false; PROD=false
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help)    sed -n '2,39p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -n|--dry-run) DRY=true ;;
        -y|--yes)     YES=true ;;
        --sql)        SQL_OUT="${2:?--sql needs a file}"; shift ;;
        --skip-build) SKIP_BUILD=true ;;
        --prod)       PROD=true ;;
        -*)           die "unknown option: $1" ;;
        *)            [ -z "$ENV_NAME" ] && ENV_NAME="$1" || die "unexpected argument: $1" ;;
    esac
    shift
done
[ -n "$ENV_NAME" ] || die "usage: ./upgrade.sh <env> [--dry-run] [--yes] [--sql FILE] [--skip-build] [--prod]"
DRYFLAG=""; [ "$DRY" = true ] && DRYFLAG="--dry-run"

RESTART=()      # what waits for a restart
CHANGED=()      # what this run changed
LEFT=()         # deployed, but not from this build

# --- 1. preflight ------------------------------------------------------------
step "1. Preflight"
CONF="$(blade_profile_conf "$ENV_NAME")"
[ -f "$CONF" ] || die "no profile '${ENV_NAME}' (${CONF}). Create it with ./install.sh ${ENV_NAME}."
DOMAIN_DIR="$(read_prop "$CONF" domains.dir)/$(read_prop "$CONF" domain.name)"
CONFIG_XML="${DOMAIN_DIR}/config/config.xml"
[ -r "$CONFIG_XML" ] || die "cannot read ${CONFIG_XML}: run upgrade.sh on the admin host, as a user who can read the domain."
ORACLE_HOME_DIR="$(read_prop "$CONF" oracle.home)"; ORACLE_HOME_DIR="${ORACLE_HOME_DIR:-/opt/oracle/occas/current}"
JAVA_DIR="$(read_prop "$CONF" java.home)"; [ -n "$JAVA_DIR" ] || { [ -d /opt/oracle/java/current ] && JAVA_DIR=/opt/oracle/java/current || JAVA_DIR="${JAVA_HOME:-}"; }
WL_JAR="${ORACLE_HOME_DIR}/wlserver/server/lib/weblogic.jar"
[ -f "$WL_JAR" ] || die "no weblogic.jar under ${ORACLE_HOME_DIR}: set oracle.home in the profile."
ok "profile ${CONF}"
ok "domain ${DOMAIN_DIR}"
# Tables of applications built outside this checkout (Gryphon's meetings, say):
# each directory holds a migrations/ of its own, applied after BLADE's.
MODULE_ARGS=()
IFS=',' read -r -a _mods <<< "$(read_prop "$CONF" upgrade.sql.modules)"
for _m in ${_mods[@]+"${_mods[@]}"}; do
    _m="$(printf '%s' "$_m" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"; [ -n "$_m" ] || continue
    [ -d "${_m}/migrations" ] || die "upgrade.sql.modules names ${_m}, which has no migrations/ directory."
    MODULE_ARGS+=(--module "$_m"); ok "database module ${_m}"
done
if git -C "$SCRIPT_DIR" rev-parse --git-dir >/dev/null 2>&1; then
    ok "BLADE $(git -C "$SCRIPT_DIR" describe --tags --always 2>/dev/null) on $(git -C "$SCRIPT_DIR" rev-parse --abbrev-ref HEAD)"
    [ -z "$(git -C "$SCRIPT_DIR" status --porcelain --untracked-files=no)" ] || warn "the checkout has uncommitted changes; they are in this build."
fi

# --- 2. build ----------------------------------------------------------------
step "2. Build"
if [ "$SKIP_BUILD" = true ]; then
    ok "skipped (--skip-build): using dist/ as it is"
elif [ "$DRY" = true ]; then
    log "  would run ./build.sh$([ "$PROD" = true ] && echo ' --prod')"
else
    if [ "$PROD" = true ]; then "${SCRIPT_DIR}/build.sh" --prod; else "${SCRIPT_DIR}/build.sh"; fi
    ok "built"
fi

# --- 3. resources ------------------------------------------------------------
step "3. Resources: JMS event bus and analytics data source"
# --sql promises to change nothing: it writes step 4's SQL and stops.
if [ -n "$SQL_OUT" ]; then
    ok "skipped (--sql): the domain is left as it is"
else
    "${SCRIPT_DIR}/install.sh" "$ENV_NAME" jms ${DRYFLAG:+"$DRYFLAG"} || die "the JMS event bus could not be reconciled (above)."
    "${SCRIPT_DIR}/install.sh" "$ENV_NAME" datasource ${DRYFLAG:+"$DRYFLAG"} || die "the analytics data source could not be reconciled (above)."
fi

# --- 4. database -------------------------------------------------------------
step "4. Analytics database"
FW_JAR="${SCRIPT_DIR}/libs/framework/target/blade-framework.jar"
[ -f "$FW_JAR" ] || die "no ${FW_JAR}: run ./build.sh (or drop --skip-build)."
schema() {
    "${JAVA_DIR:+${JAVA_DIR}/bin/}java" -cp "${FW_JAR}:${WL_JAR}" org.vorpal.blade.framework.v3.jdbc.SchemaUpgrade \
        --sql-dir "${SCRIPT_DIR}/services/analytics/sql" ${MODULE_ARGS[@]+"${MODULE_ARGS[@]}"} --domain "$DOMAIN_DIR" "$@"
}
src=0
if [ -n "$SQL_OUT" ]; then
    schema --sql "$SQL_OUT" || src=$?
    [ "$src" -eq 2 ] && { ok "analytics is not configured in this domain; nothing to write."; exit 0; }
    [ "$src" -eq 0 ] || die "the database changes could not be written (above)."
    log ""
    log "  Give ${SQL_OUT} to your DBA. Once it is applied, run ./upgrade.sh ${ENV_NAME} again"
    log "  to finish: it will find the database current and deploy."
    exit 0
elif [ "$DRY" = true ]; then
    schema --status || src=$?
else
    # Look first: a change to a live schema has no undo, so say what it is and
    # ask before making it.
    plan="$(schema --status)" || src=$?
    printf '%s\n' "$plan" | sed 's/^/  /'
    if [ "$src" -eq 0 ] && printf '%s\n' "$plan" | grep -q '^  to do:'; then
        log ""
        warn "these changes go to the live analytics database, with the data source's login."
        log "    upgrade.sh has no undo: take a backup first."
        log "    A new column or index on a large table can take a while and hold up writes;"
        log "    events wait on the JMS topic meanwhile."
        log "    If schema changes must go through your DBA, stop here and run"
        log "    ./upgrade.sh ${ENV_NAME} --sql FILE instead."
        if [ "$YES" = true ]; then
            log "    --yes: applying."
        elif [ -t 0 ]; then
            printf '  Apply them now? [y/N] '; read -r answer
            case "$answer" in
                y|Y|yes|YES) ;;
                *) log "  Nothing was changed and nothing was deployed."; exit 0 ;;
            esac
        else
            die "not applied: no terminal to ask. Run with --yes, or with --sql FILE for a DBA. Nothing was deployed."
        fi
        schema || src=$?
        [ "$src" -eq 0 ] && CHANGED+=("analytics database brought current")
    elif [ "$src" -eq 0 ]; then
        ok "the analytics database is current."
    fi
fi
case "$src" in
    0) ;;
    2) ok "analytics is not configured in this domain; skipped." ;;
    *) die "the analytics database upgrade failed (above). Nothing was deployed; fix it and run upgrade.sh again." ;;
esac

# --- what the domain has deployed --------------------------------------------
# One "<kind> <name> <targets> <source-path>" line per app-deployment and
# library in config.xml.
deployed() {
    awk '
        /<app-deployment>/ { kind="app"; name=""; tgt=""; src="" }
        /<library>/        { kind="library"; name=""; tgt=""; src="" }
        kind != "" && /<name>/        && name == "" { n=$0; sub(/.*<name>/, "", n); sub(/<\/name>.*/, "", n); name=n }
        kind != "" && /<target>/      && tgt == ""  { t=$0; sub(/.*<target>/, "", t); sub(/<\/target>.*/, "", t); tgt=t }
        kind != "" && /<source-path>/ && src == ""  { p=$0; sub(/.*<source-path>/, "", p); sub(/<\/source-path>.*/, "", p); src=p }
        /<\/app-deployment>/ || /<\/library>/ { if (kind != "") print kind, name, tgt, src; kind="" }
    ' "$CONFIG_XML"
}
# Whether two archives hold the same content (ArchiveDigest). A digest that
# cannot be computed stops the run: read as "unchanged", it would skip every
# application and report success.
same_content() {
    local out
    out="$("${JAVA_DIR:+${JAVA_DIR}/bin/}java" -cp "$FW_JAR" org.vorpal.blade.framework.v3.deploy.ArchiveDigest "$1" "$2" 2>&1)" \
        || die "cannot compare ${1} with ${2}: ${out}"
    [ "$(printf '%s\n' "$out" | cut -d' ' -f1 | sort -u | wc -l)" -eq 1 ]
}
# deploy.sh, its output kept back unless it fails.
quiet_deploy() {
    local out rc=0
    out="$("${SCRIPT_DIR}/deploy.sh" "$@" 2>&1)" || rc=$?
    [ "$rc" -eq 0 ] || printf '%s\n' "$out" | tail -25 | sed 's/^/    /'
    return "$rc"
}

# The build, found as deploy.sh finds it: the flat dev tree when dist/ holds the
# tier folders, else the newest dist/<rev>-<build>/.
DIST_ROOT="${SCRIPT_DIR}/dist"; DIST_DIR="$DIST_ROOT"
_flat=false
for _t in lib admin services test proto; do [ -d "${DIST_ROOT}/${_t}" ] && _flat=true; done
if [ "$_flat" = false ]; then
    DIST_DIR="$(ls -1td "$DIST_ROOT"/*/ 2>/dev/null | head -1)"; DIST_DIR="${DIST_DIR%/}"
fi
in_build() {   # the path of this artifact in the build, or nothing
    local t
    if [ "$DIST_DIR" = "$DIST_ROOT" ]; then
        for t in "" lib admin services test proto; do
            [ -f "${DIST_DIR}/${t:+$t/}$1" ] && { printf '%s' "${DIST_DIR}/${t:+$t/}$1"; return 0; }
        done
    else
        find "$DIST_DIR" -maxdepth 2 -type f -name "$1" 2>/dev/null | head -1
    fi
    return 0
}

# --- 5. shared library -------------------------------------------------------
step "5. Shared library (blade-shared)"
LIB_WAR="$(in_build blade-shared.war)"
if [ -z "$LIB_WAR" ]; then
    warn "this build has no blade-shared.war; the deployed library is left as it is."
else
    LIB_VER="$(unzip -p "$LIB_WAR" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' | sed -n 's/^Implementation-Version: *//p' | head -1)"
    LIB_TGT="$(deployed | awk '$1 == "library" && $2 ~ /^blade-shared/ { print $3; exit }')"
    if deployed | awk -v v="$LIB_VER" '$1 == "library" && $2 ~ /^blade-shared/ && $2 ~ ("@" v "$") { found=1 } END { exit !found }'; then
        ok "blade-shared ${LIB_VER} is already deployed"
    else
        quiet_deploy "$ENV_NAME" "$LIB_WAR" "${LIB_TGT:-AdminServer,BEA_ENGINE_TIER_CLUST}" --library ${DRYFLAG:+"$DRYFLAG"} \
            || die "blade-shared ${LIB_VER} could not be deployed (above)."
        ok "blade-shared ${LIB_VER} deployed"
        CHANGED+=("blade-shared ${LIB_VER} deployed beside the earlier version, which stays until you undeploy it")
    fi
fi

# --- 6. application router ---------------------------------------------------
step "6. Application router (blade-fsmar.jar)"
APPROUTER_DIR="$(read_prop "$CONF" approuter.dir)"
FSMAR="$(in_build blade-fsmar.jar)"
if [ -z "$APPROUTER_DIR" ] || [ ! -f "${APPROUTER_DIR}/blade-fsmar.jar" ]; then
    ok "no blade-fsmar.jar installed in this domain (approuter.dir); nothing to replace."
elif [ -z "$FSMAR" ]; then
    warn "this build has no blade-fsmar.jar; the installed one is left as it is."
elif cmp -s "$FSMAR" "${APPROUTER_DIR}/blade-fsmar.jar"; then
    ok "unchanged"
else
    quiet_deploy "$ENV_NAME" "$FSMAR" --approuter ${DRYFLAG:+"$DRYFLAG"} || die "blade-fsmar.jar could not be copied (above)."
    ok "blade-fsmar.jar replaced"
    CHANGED+=("blade-fsmar.jar replaced")
    RESTART+=("the engines, for the new application router (it is read at server start)")
fi

# --- 7. applications ---------------------------------------------------------
step "7. Applications"
# The artifact is the deployed file's own name, so an application deployed as
# loose WARs is never swapped for its tier's EAR, or the other way round.
UNCHANGED=0
while read -r kind name targets src; do
    [ "$kind" = app ] || continue
    artifact="$(in_build "$(basename "$src")")"
    if [ -z "$artifact" ]; then
        LEFT+=("$name"); continue
    fi
    case "$src" in /*) ;; *) src="${DOMAIN_DIR}/${src}" ;; esac
    if [ -f "$src" ] && same_content "$artifact" "$src"; then
        UNCHANGED=$((UNCHANGED + 1)); continue
    fi
    quiet_deploy "$ENV_NAME" "$artifact" "$targets" ${DRYFLAG:+"$DRYFLAG"} || die "${name} could not be redeployed (above). Fix it and run upgrade.sh again."
    ok "${name} redeployed on ${targets}"
    CHANGED+=("${name} redeployed on ${targets}")
done < <(deployed)
ok "${UNCHANGED} unchanged, left as they are"

# --- 8. report ---------------------------------------------------------------
step "8. Done$([ "$DRY" = true ] && echo ' (dry run: nothing was changed)')"
[ "${#CHANGED[@]}" -eq 0 ] && log "  Nothing to change."
for c in ${CHANGED[@]+"${CHANGED[@]}"}; do log "  · ${c}"; done
[ "${#LEFT[@]}" -gt 0 ] && log "  ${D}Not built here, left as they are: ${LEFT[*]}${N}"
if [ "${#RESTART[@]}" -gt 0 ]; then
    log ""
    log "  ${B}Waiting for a restart:${N}"
    for r in ${RESTART[@]+"${RESTART[@]}"}; do log "  · ${r}"; done
fi
exit 0
