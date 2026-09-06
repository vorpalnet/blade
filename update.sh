#!/usr/bin/env bash
# ============================================================================
# update.sh — the OCCAS binary lifecycle: build → canary → flip → roll.
#
# The fourth verb: install.sh stands up the server, build.sh makes artifacts,
# deploy.sh pushes them, update.sh changes the BINARIES under a running
# cluster and leaves nothing to chance.
#
# The model (see UPDATING.md for the full ceremony):
#   <base>/8.3            blessed versioned home (immutable once it has run)
#   <base>/8.3_p1         next home: a clone, patched, manifested, blessed
#   <base>/current  ->    the symlink; ONLY a flip ever changes what runs next
#
# Two mechanisms make it safe:
#   * Launch pinning — misc/start-admin-nm.sh resolves 'current' at launch and
#     substitutes the concrete home into ClassPath/Arguments, so a flip is
#     inert to running JVMs and takes effect per server at its next start.
#     BLADE_OCCAS_HOME overrides the pin (how the canary runs one server on a
#     candidate home while 'current' still points at the blessed one).
#   * Namespace build — opatch refuses an unregistered copy ("RawInventory
#     gets null OracleHomeInfo"; no attachHome ships), so 'build' bind-mounts
#     the clone AT the inventory-registered path inside a PRIVATE mount
#     namespace (unshare -m) and patches it there. opatch sees a registered
#     home; nothing outside the namespace sees the mount; the live tree is
#     never touched and the servers keep running.
#
# Usage:
#   ./update.sh <env> status                   where every link points; what every JVM runs
#   ./update.sh <env> preflight                the fat-finger detector: manifests, JDK,
#                                              space, inventory, patch dir — read-only
#   ./update.sh <env> build [<ver>]            clone current -> <ver> (default <cur>_pN),
#                                              patch it in a private namespace, manifest,
#                                              make it read-only. Never touches live.
#   ./update.sh <env> distribute <ver>         ship <ver> to the engine hosts (sync-occas.sh);
#                                              a no-op once homes are on a shared mount
#                                              (occas.homes.shared=true)
#   ./update.sh <env> canary <ver> [engine]    drain one engine (default engine0), wait
#                                              quiet, restart it PINNED to <ver>, report
#   ./update.sh <env> flip <ver>               repoint 'current' everywhere; refused unless
#                                              <ver> is manifested AND canaried
#   ./update.sh <env> roll [engine1,engine2]   restart the AdminServer, then drain-aware
#                                              rolling restart of the engines (default
#                                              engine0) onto whatever 'current' points at
#   ./update.sh <env> rollback                 flip back to the recorded previous home,
#                                              then roll
#
# Options: -n / --dry-run   print every action; change nothing.
#
# Guardrails: an operator lock in <base> serializes patch days; blessed homes
# are chmod -w and sha256-manifested (preflight verifies them); build refuses
# an existing version dir; flip refuses an uncanaried home; nothing here ever
# deletes a home. Credentials ride env/stdin, never argv — except the WLST
# rolling-restart invocation, which takes them as WLST args on this host only.
# ============================================================================
set -euo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
# Split flags from positionals ourselves: install.sh's parser must see ONLY the
# profile name (and -n) — never a verb argument it would mistake for a command.
UPD_DRY=""
UPD_POS=()
for _a in "$@"; do
    case "$_a" in
        -n|--dry-run) UPD_DRY="-n" ;;
        -h|--help)    UPD_POS=() ; break ;;
        *)            UPD_POS+=("$_a") ;;
    esac
done
UPD_ENV="${UPD_POS[0]:-}"; UPD_VERB="${UPD_POS[1]:-status}"
UPD_A1="${UPD_POS[2]:-}"; UPD_A2="${UPD_POS[3]:-}"
case "$UPD_ENV" in "") sed -n '2,58p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac

# --- The hidden inner verb runs FIRST, before the library load re-parses argv.
# build re-executes itself as:  sudo unshare -m update.sh _patch-inner <env>
# <clone> <loc> <blade_home> <dry>  — root, in a private mount namespace. It
# bind-mounts the clone at the registered path and drives install.sh's
# do_patch at it. BLADE_HOME rides argv (not env: sudo's env_reset would strip
# it and the root HOME would resolve the wrong profile).
if [ "$UPD_VERB" = "_patch-inner" ]; then
    IN_ENV="$UPD_A1"; IN_CLONE="$UPD_A2"; IN_LOC="${UPD_POS[4]:-}"; IN_BLADE_HOME="${UPD_POS[5]:-}"; IN_DRY="${UPD_POS[6]:-off}"
    export BLADE_HOME="$IN_BLADE_HOME"
    if [ "$IN_DRY" = "on" ]; then set -- "$IN_ENV" -n; else set -- "$IN_ENV"; fi
    # shellcheck disable=SC1091
    . "$(dirname "$SELF")/install.sh"
    load_profile
    if [ "$IN_DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] mount --bind ${IN_CLONE} ${IN_LOC}  (private namespace)${C_RESET}"
        PATCH_TARGET="$IN_LOC" ASSUME_YES=1 do_patch
        exit $?
    fi
    mount --bind "$IN_CLONE" "$IN_LOC" || { warn "bind mount failed: ${IN_CLONE} -> ${IN_LOC}"; exit 1; }
    # The mount dies with this namespace; no unmount needed even on failure.
    PATCH_TARGET="$IN_LOC" ASSUME_YES=1 do_patch
    exit $?
fi

# --- BLADE as a library (the gryphon/install.sh pattern): re-point argv at
# what install.sh's parser expects (profile name + flags ONLY), source it, load.
set -- "$UPD_ENV" ${UPD_DRY:+-n}
# shellcheck disable=SC1091
. "$(dirname "$SELF")/install.sh"
load_profile
[ -f "$OCCAS_CONF" ] || die "no profile '${NAME}' — create it first: ./install.sh ${NAME}"

BASE="$(read_prop "$OCCAS_CONF" occas.base.dir)"; BASE="${BASE:-$(dirname "$MWHOME")}"
LINK="$(read_prop "$DEPLOY_CONF" occas.current.link)"; LINK="${LINK:-${BASE}/current}"
CUR_REAL="$(readlink -f "$LINK" 2>/dev/null || true)"
MANIFEST_DIR="${BASE}/.manifests"
LOCK_DIR="${BASE}/.blade-update.lock"

manifest_of() { printf '%s/%s.sha256' "$MANIFEST_DIR" "$1"; }

# The operator lock serializes patch days. A lock names its holder; a stale
# lock (dead pid) is reported, never auto-broken — breaking it is a human call.
lock_acquire() {
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] acquire ${LOCK_DIR}${C_RESET}"; return 0; fi
    if as_install_user mkdir "$LOCK_DIR" 2>/dev/null; then
        as_install_user sh -c "printf '%s pid=%s at %s\n' '$(id -un)' '$$' \"\$(date '+%F %T')\" > '${LOCK_DIR}/holder'" || true
        return 0
    fi
    warn "another update holds the lock: $(as_install_user cat "${LOCK_DIR}/holder" 2>/dev/null || echo '?')"
    warn "if that run is truly dead, remove it by hand:  sudo -u $(iu_name) rm -rf ${LOCK_DIR}"
    return 1
}
lock_release() { [ "$DRY" = "on" ] || as_install_user rm -rf "$LOCK_DIR" 2>/dev/null || true; }

# engine name -> the host that runs it: engine0 lives on the admin box (local);
# engineN (calculated machine names) lives on the Nth machine's address.
host_of_engine() {
    local e="$1" n
    case "$e" in
        "${prefix:-engine}0") printf 'local' ;;
        "${prefix:-engine}"*) n="${e#"${prefix:-engine}"}"
            [ -n "${H_ADDR[$n]:-}" ] && printf '%s' "${H_ADDR[$n]}" || printf '' ;;
        *) printf '' ;;
    esac
}

# Run a command on an engine's host — locally or over ssh — as the install user,
# with env passed as `env K=V` arguments (survives sudo without SETENV).
run_on() { # $1=host('local'|addr) $2...=command words
    local host="$1"; shift
    if [ "$host" = "local" ]; then as_install_user "$@"
    else ssh -o BatchMode=yes "${SSH_USER:-$(id -un)}@${host}" "sudo -H -u '$(iu_name)' $(printf '%q ' "$@")"
    fi
}

# The concrete home each running WebLogic JVM uses, from its cmdline: the first
# ${BASE}/<ver> path wins. One line per JVM: "<server> <home>".
jvms_on() { # $1=host
    local probe="for p in \$(pgrep -f 'weblogic.Name=' 2>/dev/null); do c=\$(tr '\0' ' ' < /proc/\$p/cmdline 2>/dev/null); n=\$(printf '%s' \"\$c\" | grep -oE 'weblogic.Name=[^ ]+' | head -1 | cut -d= -f2); h=\$(printf '%s' \"\$c\" | grep -oE '${BASE}/[^/ :]+' | grep -v '^${BASE}/current\$' | head -1); printf '%s %s\n' \"\$n\" \"\${h:-?}\"; done"
    if [ "$1" = "local" ]; then bash -c "$probe" 2>/dev/null || true
    else ssh -o BatchMode=yes -o ConnectTimeout=8 "${SSH_USER:-$(id -un)}@$1" "$probe" 2>/dev/null || true
    fi
}

wlst_bin() { printf '%s/oracle_common/common/bin/wlst.sh' "$MWHOME"; }
aurl() { printf '%s' "${ADMINURL:-t3://${H_ADDR[0]:-localhost}:7001}"; }

# ----------------------------------------------------------------------------
do_status() {
    log "${C_BOLD}update status — ${NAME}${C_RESET}"
    log "  base:    ${BASE}"
    log "  current: ${LINK} -> $(readlink "$LINK" 2>/dev/null || echo '?')"
    local d bn mark
    for d in "$BASE"/*/; do
        d="${d%/}"; bn="$(basename "$d")"
        [ -L "$d" ] && continue            # 'current' itself is not a home
        [ -d "${d}/wlserver" ] || continue
        mark=""
        [ "$(readlink -f "$d")" = "$CUR_REAL" ] && mark="${mark} · current"
        [ -f "$(manifest_of "$bn")" ] && mark="${mark} · manifested"
        as_install_user test -w "$d" 2>/dev/null && mark="${mark} · writable"
        log "    ${bn}${mark}"
    done
    local i host
    for i in "${!H_NAME[@]}"; do
        [ "$i" -eq 0 ] && host="local" || host="${H_ADDR[$i]}"
        [ "$i" -eq 0 ] || [ "${H_ROLE[$i]}" = "engine" ] || continue
        local lnk; lnk="$(run_on "$host" readlink "$LINK" 2>/dev/null || echo '?')"
        log "  ${H_NAME[$i]} (${host}): current -> ${lnk}"
        local line
        while IFS= read -r line; do [ -n "$line" ] && log "      JVM ${line}"; done < <(jvms_on "$host")
    done
    local homes; homes="$( { jvms_on local; for i in "${!H_NAME[@]}"; do [ "$i" -gt 0 ] && [ "${H_ROLE[$i]}" = "engine" ] && jvms_on "${H_ADDR[$i]}"; done; } | awk '{print $2}' | sort -u | grep -v '^?$' || true)"
    if [ "$(printf '%s\n' "$homes" | grep -c . || true)" -le 1 ]; then ok "converged: every running JVM is on ${homes:-<none running>}"
    else warn "NOT converged — running JVMs span:"; printf '%s\n' "$homes" | sed 's/^/    /'; fi
}

# ----------------------------------------------------------------------------
do_preflight() {
    log "${C_BOLD}update preflight — ${NAME}${C_RESET}"
    local bad=0
    [ -d "$LOCK_DIR" ] && { warn "update lock held: $(cat "${LOCK_DIR}/holder" 2>/dev/null || echo '?')"; bad=1; } \
        || ok "no update lock held"
    [ -n "$CUR_REAL" ] && [ -d "${CUR_REAL}/wlserver" ] && ok "current -> ${CUR_REAL}" \
        || { warn "no Oracle home behind ${LINK}"; bad=1; }
    # Space: a build needs one more copy of the current home in BASE.
    local need have
    need="$(du -sk "$CUR_REAL" 2>/dev/null | cut -f1 || echo 0)"
    have="$(df -Pk "$BASE" | awk 'NR==2{print $4}')"
    if [ "${have:-0}" -gt $((need + need / 5)) ]; then ok "disk: $((have / 1024)) MB free for a $((need / 1024)) MB clone"
    else warn "disk: only $((${have:-0} / 1024)) MB free; the clone needs ~$((need / 1024)) MB"; bad=1; fi
    # opatch needs the OCCAS-certified JDK major (a newer one breaks PSU parsing).
    local wantm jh
    wantm="$(occas_jdk_major "$OCCAS_VERSION" 2>/dev/null || true)"
    jh="$(read_prop "$OCCAS_CONF" java.home)"
    if [ -z "$wantm" ]; then log "  ${C_DIM}certified JDK major unknown for OCCAS ${OCCAS_VERSION:-?} — do_patch will sort it out${C_RESET}"
    elif [ -x "${jh}/bin/java" ] && [ "$(jdk_major "${jh}/bin/java")" = "$wantm" ]; then ok "opatch JDK: java.home is the certified major (${wantm})"
    else
        local found="" ln2
        while IFS= read -r ln2; do [ "${ln2##*$'\t'}" = "$wantm" ] && { found="${ln2%%$'\t'*}"; break; }; done < <(list_jdks 2>/dev/null || true)
        [ -n "$found" ] && ok "opatch JDK: certified major ${wantm} available at ${found}" \
            || { warn "no JDK ${wantm} on this box — do_patch will try to fetch one for opatch"; }
    fi
    # Patch dir preview.
    local pdir; pdir="$(read_prop "$OCCAS_CONF" patch.dir)"; pdir="${pdir/#\~/$HOME}"
    if [ -n "$pdir" ] && [ -d "$pdir" ]; then ok "patch dir ${pdir}: $(ls "$pdir"/*.zip 2>/dev/null | wc -l | tr -d ' ') zip(s)"
    else warn "patch.dir not set / missing — 'build' will ask (or fail headless)"; fi
    # Central inventory: registry-only (no product reads it at runtime), so this
    # is verify-or-warn, never a dependency.
    local invloc invxml
    invloc="$(read_prop "$OCCAS_CONF" inventory.loc)"; invloc="${invloc:-/opt/oracle/oraInventory}"
    invxml="${invloc}/ContentsXML/inventory.xml"
    if as_install_user grep -qs "LOC=\"" "$invxml" 2>/dev/null; then ok "central inventory present: ${invxml}"
    else log "  ${C_DIM}central inventory unreadable at ${invxml} — registry-only; build falls back to the current real path${C_RESET}"; fi
    # Manifest verify — the fat-finger detector. Full sha256 of every blessed
    # home; minutes per home, which is the point: run it on patch day.
    local m bn
    for m in "$MANIFEST_DIR"/*.sha256; do
        [ -f "$m" ] || continue
        bn="$(basename "${m%.sha256}")"
        [ -d "${BASE}/${bn}" ] || { warn "manifest for missing home: ${bn}"; bad=1; continue; }
        info "verifying ${bn} against its manifest…"
        if as_install_user sh -c "cd '${BASE}/${bn}' && sha256sum --quiet -c '$m'" >/dev/null 2>&1; then
            ok "${bn}: matches its manifest"
        else warn "${bn}: DIFFERS from its manifest — a blessed home has been modified"; bad=1; fi
    done
    [ "$bad" = 0 ] && ok "preflight clean." || warn "preflight found problems (above)."
    return "$bad"
}

# ----------------------------------------------------------------------------
do_build() {
    local ver="${1:-}"
    [ -n "$CUR_REAL" ] && [ -d "${CUR_REAL}/wlserver" ] || die "no Oracle home behind ${LINK}"
    if [ -z "$ver" ]; then
        # Next _pN after the current home's stem: 8.3 -> 8.3_p1, 8.3_p1 -> 8.3_p2.
        local stem n=0 d
        stem="$(basename "$CUR_REAL")"; stem="${stem%_p[0-9]*}"
        for d in "$BASE/${stem}"_p*/; do
            [ -d "$d" ] || continue
            local k="${d%/}"; k="${k##*_p}"
            case "$k" in *[!0-9]*) ;; *) [ "$k" -gt "$n" ] && n="$k" ;; esac
        done
        ver="${stem}_p$((n + 1))"
    fi
    local new="${BASE}/${ver}"
    [ -e "$new" ] && die "${new} already exists — homes are append-only; pick another version."
    lock_acquire || return 1
    trap lock_release EXIT

    # The registered home path, from the central inventory; fall back to the
    # current real path (where the product was installed).
    local invloc invxml loc=""
    invloc="$(read_prop "$OCCAS_CONF" inventory.loc)"; invloc="${invloc:-/opt/oracle/oraInventory}"
    invxml="${invloc}/ContentsXML/inventory.xml"
    loc="$(as_install_user grep -o 'LOC="[^"]*"' "$invxml" 2>/dev/null | sed 's/LOC="//;s/"$//' | grep -F "$BASE" | head -1 || true)"
    loc="${loc:-$CUR_REAL}"

    info "build ${ver}: clone $(basename "$CUR_REAL") → ${ver}, patch at registered path ${loc} (private namespace)"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] cp -a ${CUR_REAL} ${new}${C_RESET}"
        log "${C_DIM}  [dry-run] sudo unshare -m update.sh … _patch-inner (bind-mount ${new} at ${loc}, do_patch there)${C_RESET}"
        # The inner verb dry-runs without sudo or a namespace — it mounts nothing.
        "$SELF" x _patch-inner "$NAME" "$new" "$loc" "$BLADE_HOME" on || true
        log "${C_DIM}  [dry-run] sha256 manifest -> $(manifest_of "$ver"); chmod -R a-w ${new}${C_RESET}"
        return 0
    fi
    info "cloning $(basename "$CUR_REAL") ($((($(du -sk "$CUR_REAL" | cut -f1)) / 1024)) MB)…"
    as_install_user cp -a "$CUR_REAL" "$new" || { warn "clone failed"; return 1; }

    # Patch the clone where opatch expects a home to be. Root for unshare -m;
    # everything the inner verb needs rides argv (sudo env_reset strips env).
    if ! sudo unshare -m "$SELF" x _patch-inner "$NAME" "$new" "$loc" "$BLADE_HOME" off; then
        warn "patching FAILED — ${new} is left for inspection (opatch lsinventory -oh ${loc} inside a mount, or just rm -rf it; the live home was never touched)."
        return 1
    fi

    info "writing the sha256 manifest (this is the future fat-finger detector)…"
    as_install_user mkdir -p "$MANIFEST_DIR"
    as_install_user sh -c "cd '${new}' && find . -type f -print0 | sort -z | xargs -0 sha256sum > '$(manifest_of "$ver")'" \
        || { warn "manifest failed"; return 1; }
    as_install_user chmod -R a-w "$new" || true
    ok "built ${ver}: patched, manifested, read-only. The live home was never touched."
    log "  ${C_DIM}next:  ./update.sh ${NAME} distribute ${ver}   (skip when homes are shared)${C_RESET}"
    log "  ${C_DIM}then:  ./update.sh ${NAME} canary ${ver}${C_RESET}"
}

# ----------------------------------------------------------------------------
do_distribute() {
    local ver="$1"
    if [ "$(read_prop "$DEPLOY_CONF" occas.homes.shared)" = "true" ]; then
        ok "homes are on a shared mount (occas.homes.shared=true) — nothing to distribute."
        return 0
    fi
    if [ "$DRY" = "on" ]; then "${SCRIPT_DIR}/sync-occas.sh" "$NAME" distribute "$ver" --dry-run
    else "${SCRIPT_DIR}/sync-occas.sh" "$NAME" distribute "$ver"; fi
}

# ----------------------------------------------------------------------------
# Drain one engine and wait for quiet — the same protocol rolling-restart.py
# uses (Drain MBean -> OPTIONS 503 -> PeriodCountSipThroughput==0 twice).
drain_and_wait() { # $1=engine
    local eng="$1" pw; pw="$(get_admin_pw)" || return 1
    local work; work="$(mktemp -d /tmp/blade-canary.XXXXXX)"
    cat > "${work}/drain.py" <<PYDR
# -*- coding: utf-8 -*-
import sys
from java.lang import Thread, Boolean
from javax.management import ObjectName, Attribute
connect(sys.argv[1], sys.argv[2], sys.argv[3])
domainRuntime()
eng = sys.argv[4]
def by_loc(pat):
    for on in mbs.queryNames(ObjectName(pat), None):
        if on.getKeyProperty('Location') == eng:
            return on
    return None
d = by_loc('vorpal.blade:Name=*,Type=Drain,*')
if d is None:
    print 'DRAIN_ABSENT ' + eng
    sys.exit(3)
mbs.setAttribute(d, Attribute('Drained', Boolean(1)))
print 'DRAINED ' + eng
Thread.sleep(75 * 1000)
sr = by_loc('com.bea:Type=SipServerRuntime,*')
quiet = 0; waited = 0
while waited < 120 and quiet < 2:
    t = -1
    if sr is not None:
        try:
            t = mbs.getAttribute(sr, 'PeriodCountSipThroughput')
        except:
            t = -1
    if t == 0:
        quiet = quiet + 1
    elif t < 0:
        break
    else:
        quiet = 0
    Thread.sleep(10000); waited = waited + 10
print 'QUIET ' + eng
disconnect()
PYDR
    chmod 600 "${work}/drain.py"
    local out
    out="$("$(wlst_bin)" "${work}/drain.py" "$ADMIN_USER" "$pw" "$(aurl)" "$eng" 2>&1)" || true
    rm -rf "$work"
    printf '%s\n' "$out" | grep -E 'DRAINED|QUIET|DRAIN_ABSENT' | sed 's/^/  /'
    printf '%s' "$out" | grep -q 'QUIET' && return 0
    printf '%s' "$out" | grep -q 'DRAIN_ABSENT' && { warn "no Drain MBean on ${eng} (options app not deployed?) — refusing an undrained canary."; return 1; }
    warn "drain did not reach quiet:"; printf '%s\n' "$out" | tail -8 | sed 's/^/  /'; return 1
}

do_canary() {
    local ver="$1" eng="${2:-${prefix:-engine}0}"
    local new="${BASE}/${ver}"
    [ -d "${new}/wlserver" ] || die "no such home: ${new} — build it first."
    [ -f "$(manifest_of "$ver")" ] || die "${ver} has no manifest — only a 'build' output can be canaried."
    local host; host="$(host_of_engine "$eng")"
    [ -n "$host" ] || die "cannot place ${eng} on a host (machine list has ${#H_NAME[@]} entries)."
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] drain ${eng}, wait quiet, restart it with BLADE_OCCAS_HOME=${new} via start-admin-nm.sh on ${host}, report its concrete home${C_RESET}"
        return 0
    fi
    [ "$host" = "local" ] || run_on "$host" test -d "${new}/wlserver" \
        || die "${new} is not present on ${host} — distribute it first."
    drain_and_wait "$eng" || return 1
    info "restarting ${eng} pinned to ${ver}…"
    local domhome="${DOMAINS_DIR}/${DOMAIN}"
    # The NM password and WLST_PROPERTIES come from the unit's own 0600 env file
    # (.blade-nm.env), sourced ON the target host as the install user — the
    # secret never rides argv, locally or across ssh. Everything else is plain.
    local rcmd="set -a; . '${domhome}/.blade-nm.env'; set +a; \
MW_HOME='${MWHOME}' DOMAIN_NAME='${DOMAIN}' DOMAIN_HOME='${domhome}' \
ADMIN_SERVER='${eng}' NM_USER='${ADMIN_USER}' NM_PORT='${NM_PORT:-5556}' \
NM_TYPE='${NM_TYPE:-ssl}' NM_ACTION=restart NM_ADMINURL='$(aurl)' \
BLADE_OCCAS_HOME='${new}' exec bash '${domhome}/${BOOT_SCRIPT_SUBDIR}/start-admin-nm.sh'"
    run_on "$host" bash -c "$rcmd" \
        || { warn "pinned restart of ${eng} failed"; return 1; }
    sleep 5
    local seen; seen="$(jvms_on "$host" | awk -v e="$eng" '$1==e{print $2}')"
    if [ "$seen" = "$new" ]; then
        ok "canary ${eng} is RUNNING on ${ver} (verified from its cmdline)."
        [ "$DRY" = "on" ] || set_conf_prop "$DEPLOY_CONF" update.canary "${ver}:${eng}:$(date +%s)"
        log "  ${C_DIM}watch it take traffic, then:  ./update.sh ${NAME} flip ${ver}${C_RESET}"
    else
        warn "canary started but runs on '${seen:-?}' not ${new} — investigate before flipping."
        return 1
    fi
}

# ----------------------------------------------------------------------------
do_flip() {
    # Split, not one `local` line: bash creates every name first and assigns
    # after, so `local a="$1" b="$a"` reads an unbound `a` under set -u.
    local ver="$1"
    local new="${BASE}/${ver}"
    [ -d "${new}/wlserver" ] || die "no such home: ${new}"
    [ -f "$(manifest_of "$ver")" ] || die "refusing to flip: ${ver} has no manifest (not a blessed build)."
    case "$(read_prop "$DEPLOY_CONF" update.canary)" in
        "${ver}:"*) : ;;
        *) die "refusing to flip: ${ver} has not been canaried (./update.sh ${NAME} canary ${ver})." ;;
    esac
    lock_acquire || return 1
    trap lock_release EXIT
    local prev; prev="$(basename "$(readlink -f "$LINK")")"
    info "flip: current ${prev} → ${ver} (running JVMs stay pinned; the flip lands at each server's next start)"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] record update.previous=${prev}; sync-occas.sh ${NAME} switch ${ver} --local${C_RESET}"
        return 0
    fi
    set_conf_prop "$DEPLOY_CONF" update.previous "$prev"
    "${SCRIPT_DIR}/sync-occas.sh" "$NAME" switch "$ver" --local || return 1
    ok "flipped. Roll when ready:  ./update.sh ${NAME} roll"
}

# ----------------------------------------------------------------------------
do_roll() {
    local engines="${1:-${prefix:-engine}0}"
    local target; target="$(basename "$(readlink -f "$LINK")")"
    lock_acquire || return 1
    trap lock_release EXIT
    info "roll onto ${target}: AdminServer first, then engines (${engines}), drain-aware, one at a time"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] sudo systemctl restart weblogic.service; wait admin; wlst rolling-restart.py $(aurl) ${ADMIN_USER} **** ${engines}${C_RESET}"
        return 0
    fi
    local sudo=""; [ "$(id -u)" != 0 ] && command -v sudo >/dev/null 2>&1 && sudo="sudo"
    info "restarting the AdminServer (weblogic.service)…"
    $sudo systemctl restart weblogic.service || { warn "systemctl restart weblogic failed"; return 1; }
    local i=0
    until admin_running; do
        i=$((i + 1)); [ "$i" -gt 120 ] && { warn "AdminServer not back after 120s — stopping the roll."; return 1; }
        sleep 1
    done
    ok "AdminServer is back on ${target}."
    local pw; pw="$(get_admin_pw)" || return 1
    "$(wlst_bin)" "${SCRIPT_DIR}/misc/rolling-restart.py" "$(aurl)" "$ADMIN_USER" "$pw" "$engines" \
        || { warn "rolling restart reported an error"; return 1; }
    do_status
}

# ----------------------------------------------------------------------------
do_rollback() {
    local prev; prev="$(read_prop "$DEPLOY_CONF" update.previous)"
    [ -n "$prev" ] && [ -d "${BASE}/${prev}/wlserver" ] || die "no recorded previous home to roll back to (update.previous)."
    local cur; cur="$(basename "$(readlink -f "$LINK")")"
    info "rollback: current ${cur} → ${prev}, then roll"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] sync-occas.sh ${NAME} switch ${prev} --local; then roll${C_RESET}"
        return 0
    fi
    "${SCRIPT_DIR}/sync-occas.sh" "$NAME" switch "$prev" --local || return 1
    set_conf_prop "$DEPLOY_CONF" update.previous "$cur"
    do_roll
}

# ----------------------------------------------------------------------------
V2="$UPD_A1"; V3="$UPD_A2"
case "$UPD_VERB" in
    status)     do_status ;;
    preflight)  do_preflight ;;
    build)      do_build "$V2" ;;
    distribute) [ -n "$V2" ] || die "distribute needs a version"; do_distribute "$V2" ;;
    canary)     [ -n "$V2" ] || die "canary needs a version";     do_canary "$V2" "$V3" ;;
    flip)       [ -n "$V2" ] || die "flip needs a version";       do_flip "$V2" ;;
    roll)       do_roll "$V2" ;;
    rollback)   do_rollback ;;
    *) die "unknown verb '${UPD_VERB}' — status, preflight, build, distribute, canary, flip, roll, rollback" ;;
esac
