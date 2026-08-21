#!/usr/bin/env bash
# ============================================================================
# install.sh - Guided installer/configurator for BLADE (OCCAS + BLADE).
#
# One interview builds a PROFILE — a directory under .conf/<name>/ holding the
# two config files the rest of the tooling reads (plus their secrets):
#
#   .conf/<name>/occas.conf     silent install + domain + patching
#   .conf/<name>/deploy.conf    ./deploy.sh, ./tls/* (deploy + TLS)
#   .conf/<name>/the config     admin password + store password + TLS passphrases
#
# The whole .conf/ directory is gitignored — profiles describe YOUR hosts/IPs
# and must never land in the (open-source) repo. The committed examples under
# build-profiles/ are templates only.
#
# It drops you into a DASHBOARD: the interview is broken into independent phases
# (Domain, OCCAS+JDK, Hosts & Node Manager, Cluster, Static engine, Runtime, TLS,
# Password) plus host RUN actions. Pick one, several ("1 3 5"), a range ("1-4"),
# or "all" — so you can re-run a single phase to refine it without the full
# interview. Everything EXCEPT build and deploy lives here:
#   - create/edit a profile, phase by phase
#   - download the OCCAS media from Oracle eDelivery (RUN: dl)
#   - install OCCAS binaries (silent)
#   - create + start the standalone Node Manager domain 'nmdomain' (RUN: n).
#     Node Manager runs in its OWN basic domain, binding 0.0.0.0, so app/cluster
#     domains can be rebuilt/upgraded without ever taking Node Manager down.
#   - create the dynamic-cluster app domain and enroll it into that NM (RUN: c)
#   - start the AdminServer via Node Manager (RUN: s; misc/start-admin-nm.sh)
#   - install systemd boot services so Node Manager (RUN: e) and the AdminServer
#     (RUN: w) come back up on reboot. Nothing else starts Node Manager at boot,
#     so without these a host that reboots simply stays down.
#   - provision the ENGINE hosts (RUN: E): rsync the OCCAS home, JDK and certs to
#     the same absolute paths, install their boot services, start their servers
#   - add the static test engine to a LIVE domain (RUN: j), for when the domain
#     already exists and re-running 'c' would clobber it
#   - deploy the hosted WebLogic Remote Console at /rconsole (RUN: o) — WLS
#     14.1.2 dropped the built-in /console
#   - stop Node Manager to re-read enrollments (RUN: k)
#   - open the firewalld ports OCCAS needs (RUN: f)
#   - own the edge nginx reverse proxy on the front-door box (RUN: nginx to set
#     the vhosts/certs, ngx to render + validate + reload /etc/nginx/nginx.conf).
#     TLS terminates here and HTTP + WebSocket forward to the local AdminServer
#     (admin vhost) and engine0 (apps vhost). naxsi WAF optional; certs supplied.
#   - supply/generate the TLS cert (RUN: g/sup; tls/make-certs.sh). HTTPS/SIP-TLS is
#     stamped onto the ServerTemplate + AdminServer at configure (emit_tls_block),
#     offline — no separate online "turn it on" step and no running server needed.
#   - UNINSTALL, in reverse-of-install order (each row confirms first):
#       remove app domain + profile (RUN: r) · remove Node Manager domain (RUN: b)
#       deinstall the OCCAS product (RUN: di) · remove install dirs (RUN: md)
#       remove install user & group (RUN: ug) · delete the LOCAL repo clone (RUN: repo)
# Build with ./build.sh and deploy with ./deploy.sh <profile> afterwards.
#
# Usage:
#   ./install.sh                 pick a profile (or create one), then the dashboard
#   ./install.sh <name>          open profile <name> in the dashboard
#   ./install.sh <name> wizard      run the full linear interview first
#   ./install.sh <name> preflight   run host-prerequisite checks first
#   ./install.sh <name> install     unattended install (install → TLS → start), no menu
#   ./install.sh <name> uninstall   unattended teardown (app+NM domains)
#                                   add --purge to also remove product/dirs/user
#   ./install.sh <name> status      one-shot health snapshot of the profile
#   ./install.sh <name> backup      snapshot profile + domain config to a tgz
#   flags: -y/--yes (assume yes)  -n/--dry-run  --no-backup  --purge
#   ./install.sh -v | --version     print the BLADE version
#   ./install.sh -h
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF_BASE="${SCRIPT_DIR}/.conf"                     # legacy per-profile dir (migration source only)
BLADE_HOME="${BLADE_HOME:-$HOME/.blade}"            # ONE config file per env lives here: <env>.conf

if [ -z "${NO_COLOR:-}" ] && [ -t 1 ]; then
    C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
    C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_DIM=""; C_BOLD=""; C_RESET=""; fi
log()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s\xe2\x9c\x93%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s\xe2\x9a\xa0%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()  { printf '%s\xe2\x9c\x97%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }
rule() { printf '%s%s%s\n' "$C_DIM" "  ----------------------------------------------------------------------" "$C_RESET"; }
# Product/license header shown at the top of every screen (\xc2\xb7 = ·, \xc2\xa9 = ©).
banner() { printf '  %sBLADE installer%s  \xc2\xb7  MIT License  \xc2\xb7  \xc2\xa9 vorpal.net\n' "$C_BOLD" "$C_RESET"; }

# Dimmed, indented explanatory text. Feed it a heredoc.
help() { local l; while IFS= read -r l; do printf '%s  %s%s\n' "$C_DIM" "$l" "$C_RESET"; done; }

# --- prompt helpers -----------------------------------------------------------
# Esc-to-cancel: pressing Esc at the start of any prompt sets PAGE_ABORT, which
# makes every remaining prompt on the page a no-op so the form unwinds back to
# the menu (dispatch_row then skips the save). dispatch_row clears it per action.
PAGE_ABORT=0
# Read a line into $1 (hidden when $2=1), with the prompt already printed. Uses a
# native cooked-mode read, so paste and line editing (backspace) work exactly as
# the terminal does them. A line that STARTS with Esc cancels the page (press Esc
# then Enter). EOF / no TTY (piped --yes install) takes the default, not an abort.
# (Cooked mode can't see a lone Esc keypress before Enter — that's the price of
# leaving paste to the terminal instead of reading it byte-by-byte.)
_read_or_abort() {  # $1=destvar  $2=hidden(1|0)
    # Internal temp must not collide with the caller's var name (bash has dynamic
    # scope; ask/ask_secret pass __in/__a/__b) or printf -v would write our local.
    local __roa
    if [ "${2:-0}" = 1 ]; then
        IFS= read -rs __roa 2>/dev/null || { printf -v "$1" '%s' ""; return 0; }; echo
    else
        IFS= read -r  __roa 2>/dev/null || { printf -v "$1" '%s' ""; return 0; }
    fi
    case "$__roa" in $'\e'*) PAGE_ABORT=1; return 1 ;; esac
    printf -v "$1" '%s' "$__roa"
}
# ask VAR "label" "default"   — Enter accepts the default; Esc cancels the page.
ask() {
    local __v="$1" __l="$2" __d="${3:-}" __in
    [ "${PAGE_ABORT:-0}" = 1 ] && return 1
    if [ -n "$__d" ]; then printf '  %s [%s]: ' "$__l" "$__d"; else printf '  %s: ' "$__l"; fi
    _read_or_abort __in 0 || return 1
    [ -z "$__in" ] && __in="$__d"
    printf -v "$__v" '%s' "$__in"
}
# yesno "label" "Y|N"  — returns 0 for yes. Default shown in caps. Esc cancels.
yesno() {
    local __l="$1" __d="${2:-Y}" __in __hint
    [ "${PAGE_ABORT:-0}" = 1 ] && return 1
    if [ "${ASSUME_YES:-0}" = 1 ]; then log "  ${__l} ${C_DIM}[--yes]${C_RESET}"; return 0; fi
    [ "$__d" = "Y" ] && __hint="Y/n" || __hint="y/N"
    printf '  %s [%s]: ' "$__l" "$__hint"
    _read_or_abort __in 0 || return 1
    [ -z "$__in" ] && __in="$__d"
    case "$__in" in [Yy]*) return 0 ;; *) return 1 ;; esac
}
# ask_secret VAR "label"  — hidden, confirmed; empty is allowed (skips). Esc cancels.
ask_secret() {
    local __v="$1" __l="$2" __a __b
    [ "${PAGE_ABORT:-0}" = 1 ] && return 1
    printf '  %s: ' "$__l"; _read_or_abort __a 1 || return 1
    if [ -z "$__a" ]; then printf -v "$__v" '%s' ""; return 0; fi
    printf '  confirm: '; _read_or_abort __b 1 || return 1
    if [ "$__a" != "$__b" ]; then warn "didn't match — try again"; ask_secret "$__v" "$__l"; return; fi
    printf -v "$__v" '%s' "$__a"
}
gen_pass() {
    openssl rand -base64 24 2>/dev/null | tr -d '/+=\n' | cut -c1-24 \
        || head -c 18 /dev/urandom | base64 | tr -d '/+=\n' | cut -c1-24
}
is_ip() { case "$1" in *[!0-9.]*) return 1 ;; *.*.*.*) return 0 ;; *) return 1 ;; esac; }

# Follow-up steps the just-run action wants to offer at the TUI return prompt.
# Each becomes one pressable line ("'n'  restart Node Manager"). The runner
# clears the list before dispatching, so hints never carry over between steps.
NEXT_K=(); NEXT_D=()
next_step()       { NEXT_K+=("$1"); NEXT_D+=("$2"); }
next_step_reset() { NEXT_K=(); NEXT_D=(); }

read_prop() {
    local file="$1" key="$2" v
    v="$({ grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    # Secrets are stored as key=ENC(value); strip the wrapper on read. Config
    # values have no ENC() and pass through. (Future: decrypt inside ENC() here.)
    case "$v" in ENC\(*\)) v="${v#ENC(}"; v="${v%)}" ;; esac
    printf '%s' "$v"
}

# Set key=value in a conf file: update in place if the key exists, else append.
# Uses '|' as the sed delimiter since values are typically paths (with slashes).
set_conf_prop() {
    local file="$1" key="$2" val="$3" tmp
    if [ -f "$file" ] && grep -q "^${key}=" "$file"; then
        tmp="$(mktemp)" && sed "s|^${key}=.*|${key}=${val}|" "$file" > "$tmp" && mv "$tmp" "$file"
    else
        printf '%s=%s\n' "$key" "$val" >> "$file"
    fi
}

# Privileged engine transfer (xfer_rsync/xfer_run_as/xfer_owner_of) — shared
# with sync-occas.sh and tls/install-ssl.sh.
# shellcheck source=misc/xfer.sh
. "${SCRIPT_DIR}/misc/xfer.sh"
# Shared profile-path resolution + legacy ~/.blade migration (one profile per env:
# ~/.blade/<name>/profile.conf + certs/). The same file certs.sh, make-certs.sh and
# deploy.sh source, so every tool migrates and resolves identically.
# shellcheck source=misc/blade-paths.sh
. "${SCRIPT_DIR}/misc/blade-paths.sh"
# shellcheck source=misc/blade-profile.sh
. "${SCRIPT_DIR}/misc/blade-profile.sh"

# --- args ---------------------------------------------------------------------
# Version tracks pom.xml's <revision>, so a dev's bug report pins to a build.
BLADE_VERSION="$(sed -n 's/.*<revision>\(.*\)<\/revision>.*/\1/p' "${SCRIPT_DIR}/pom.xml" 2>/dev/null | head -1)"
BLADE_VERSION="${BLADE_VERSION:-3.0.1}"
case "${1:-}" in
    -h|--help)            sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -v|--version|version) printf 'BLADE %s\n' "$BLADE_VERSION"; exit 0 ;;
esac
# A leading FLAG is not a profile name. './install.sh --dry-run' used to create a
# profile directory literally called '--dry-run' and then offer to install it.
NAME=""
__rest_from=1
case "${1:-}" in
    -*) ;;                                  # flag: no name given, parse from $1
    *)  NAME="${1:-}"; __rest_from=2 ;;
esac
JUMP=""          # subcommand: wizard|preflight|install|uninstall|status|backup
ASSUME_YES=0     # -y/--yes: auto-answer every yesno prompt
BACKUP=1         # snapshot before a domain teardown; --no-backup disables
PURGE=0          # uninstall --purge: also product, dirs, user/group
KEEP_PROFILE=0   # set by the uninstall ladder so a reinstall can reuse the profile
# First non-flag arg after <name> is the subcommand; flags may appear anywhere.
for __a in "${@:${__rest_from}}"; do
    case "$__a" in
        -y|--yes)      ASSUME_YES=1 ;;
        -n|--dry-run)  DRY=on ;;
        --no-backup)   BACKUP=0 ;;
        --backup)      BACKUP=1 ;;
        --purge|--all) PURGE=1 ;;
        -*)            warn "ignoring unknown flag: ${__a}" ;;
        *)             [ -z "$JUMP" ] && JUMP="$__a" || warn "ignoring extra arg: ${__a}" ;;
    esac
done

# Existing-profile scalar defaults (edit mode). Set once NAME/paths are known.
OCCAS_CONF=""; DEPLOY_CONF=""; WLS_SECRET=""
# Set by do_remove_domain after it deletes the active profile, so the dashboard
# loops know to drop out instead of redrawing a profile that no longer exists.
PROFILE_GONE=0
# Set by do_remove_repo after it schedules deletion of the local clone (install.sh
# included): the dashboard loops drop out so we exit before the tree disappears.
REPO_GONE=0
set_paths() {
    if [ -z "$NAME" ]; then
        BLADE_CONF="${BLADE_HOME}/.conf"
        OCCAS_CONF="$BLADE_CONF"; DEPLOY_CONF="$BLADE_CONF"; WLS_SECRET="$BLADE_CONF"
        PROFILE_DIR="$BLADE_HOME"; BLADE_LOG=""; URLS_FILE=""
        return 0
    fi
    # ONE file per env holds config + secrets, at ~/.blade/<env>/profile.conf. The
    # three var names all alias it. blade_profile_conf migrates a legacy
    # ~/.blade/<env>.conf (+ its loose certs) into the new layout on first touch.
    BLADE_CONF="$(blade_profile_conf "$NAME")"
    OCCAS_CONF="$BLADE_CONF"; DEPLOY_CONF="$BLADE_CONF"; WLS_SECRET="$BLADE_CONF"
    # Every GENERATED artifact for this env lives in its per-env folder,
    # ~/.blade/<env>/ — profile.conf, certs/, the log, the URL list.
    PROFILE_DIR="${BLADE_HOME}/${NAME}"
    BLADE_LOG="${PROFILE_DIR}/${NAME}.log"
    URLS_FILE="${PROFILE_DIR}/${NAME}.urls"
    mkdir -p "$PROFILE_DIR" 2>/dev/null || true
    # One-time migration: fold a legacy .conf/<name>/{occas,deploy}.conf + its
    # secrets into ~/.blade/<name>.conf. Config keys stay plain; secret keys are
    # wrapped ENC(...) (plaintext for now; a decrypt hook slots in later). The dead
    # wls.password copy is dropped. Old files are left in place for rollback.
    if [ ! -f "$BLADE_CONF" ]; then
        local _old="${CONF_BASE}/${NAME}"
        if [ -f "${_old}/occas.conf" ] || [ -f "${_old}/deploy.conf" ]; then
            ( umask 077
              cat "${_old}/occas.conf" "${_old}/deploy.conf" 2>/dev/null | grep -vE '^[[:space:]]*(#|$)'
              cat "${_old}/wls.secret" "${_old}/occas.secret" "${_old}/deploy.secret" 2>/dev/null \
                | grep -E '^[a-zA-Z][a-zA-Z0-9._]*=' | grep -v '^wls\.password=' \
                | sed -E 's/^([a-zA-Z0-9._]+)=(.*)$/\1=ENC(\2)/'
            ) > "$BLADE_CONF" 2>/dev/null || true
            chmod 600 "$BLADE_CONF" 2>/dev/null || true
        fi
    fi
}
# Every ~/.blade profile name — new layout first (~/.blade/<name>/profile.conf),
# then legacy flat (~/.blade/<name>.conf) and the ancient .conf/<name>/. Deduped.
# Enumerate profile names. The two current layouts come from the shared
# blade_list_profiles (so all three scripts agree); install.sh additionally
# tolerates an ancient $CONF_BASE/<name>/occas.conf, appended + deduped here.
list_profile_names() {
    local seen=" " p d
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        case "$seen" in *" $p "*) ;; *) echo "$p"; seen="${seen}${p} " ;; esac
    done < <(blade_list_profiles)
    if [ -d "$CONF_BASE" ]; then
        for d in "$CONF_BASE"/*/; do
            [ -f "${d}occas.conf" ] || continue
            p="$(basename "$d")"; case "$seen" in *" $p "*) ;; *) echo "$p"; seen="${seen}${p} " ;; esac
        done
    fi
}

# True if <name> already has a profile (either layout).
_profile_exists() { [ -f "${BLADE_HOME}/$1/profile.conf" ] || [ -f "${BLADE_HOME}/$1.conf" ]; }

# Save the loaded profile under a NEW environment name (clone), then switch to it
# so its values are offered for editing. Config is copied; SECRETS (ENC(...)) and
# CERTS are NOT — the new environment sets its own password and regenerates certs
# (different SANs anyway). Edit its domain, machine addresses and admin URL after.
do_clone_profile() {
    [ -n "$NAME" ] && [ -f "$OCCAS_CONF" ] || { warn "no profile loaded to clone."; return 1; }
    local newname=""
    ask newname "  Save as a new environment named" ""
    [ -n "$newname" ] || { warn "cancelled."; return 0; }
    case "$newname" in
        "$NAME")          warn "that is the current name."; return 1 ;;
        *[!A-Za-z0-9_-]*) warn "use letters, digits, - or _ only."; return 1 ;;
    esac
    _profile_exists "$newname" && { warn "'${newname}' already exists."; return 1; }
    local newdir="${BLADE_HOME}/${newname}" newconf="${BLADE_HOME}/${newname}/profile.conf"
    mkdir -p "$newdir" || { warn "could not create ~/.blade/${newname}."; return 1; }
    ( umask 077; grep -vE '^[a-zA-Z0-9._]+=ENC\(' "$OCCAS_CONF" > "$newconf" )
    chmod 600 "$newconf" 2>/dev/null || true
    ok "cloned '${NAME}' → '${newname}' (secrets & certs NOT copied)."
    log "  Edit its domain name, machine addresses and admin URL for the new environment,"
    log "  then generate its certificate (STEP 4) and set its admin password."
    NAME="$newname"; set_paths; load_profile
}

# Rename this environment's ~/.blade profile directory — a typo fix. Does NOT
# touch the running server or its domain; only the ~/.blade/<name>/ folder moves.
do_rename_profile() {
    [ -n "$NAME" ] || { warn "no profile loaded."; return 1; }
    local newname=""
    ask newname "  Rename '${NAME}' to" "$NAME"
    { [ -n "$newname" ] && [ "$newname" != "$NAME" ]; } || { warn "cancelled."; return 0; }
    case "$newname" in *[!A-Za-z0-9_-]*) warn "use letters, digits, - or _ only."; return 1 ;; esac
    _profile_exists "$newname" && { warn "'${newname}' already exists."; return 1; }
    blade_migrate_profile "$NAME"                       # ensure new layout before moving
    mv "${BLADE_HOME}/${NAME}" "${BLADE_HOME}/${newname}" || { warn "rename failed."; return 1; }
    [ -f "${BLADE_HOME}/${newname}/${NAME}.log" ]  && mv "${BLADE_HOME}/${newname}/${NAME}.log"  "${BLADE_HOME}/${newname}/${newname}.log"  2>/dev/null || true
    [ -f "${BLADE_HOME}/${newname}/${NAME}.urls" ] && mv "${BLADE_HOME}/${newname}/${NAME}.urls" "${BLADE_HOME}/${newname}/${newname}.urls" 2>/dev/null || true
    ok "renamed '${NAME}' → '${newname}'."
    NAME="$newname"; set_paths; load_profile
}

# Delete this environment's ~/.blade profile (config + certs). Does NOT touch the
# running server — use the UNINSTALL rows for that. Confirms first.
do_delete_profile() {
    [ -n "$NAME" ] || { warn "no profile loaded."; return 1; }
    yesno "Delete the ~/.blade profile for '${NAME}' (config + certs)? The running server is NOT touched." "N" || return 0
    rm -rf "${BLADE_HOME:?}/${NAME}" "${BLADE_HOME:?}/${NAME}.conf"
    ok "deleted ~/.blade profile '${NAME}'."
    PROFILE_GONE=1   # the dashboard loop drops out (the profile is gone)
}

# exget <key>  — value from an existing conf (for edit defaults), else "".
exget() {
    local v=""
    [ -n "$OCCAS_CONF" ]  && [ -f "$OCCAS_CONF" ]  && v="$(read_prop "$OCCAS_CONF" "$1")"
    [ -z "$v" ] && [ -n "$DEPLOY_CONF" ] && [ -f "$DEPLOY_CONF" ] && v="$(read_prop "$DEPLOY_CONF" "$1")"
    printf '%s' "$v"
}
# d <key> <hardcoded>  — existing value wins, else the hardcoded default.
d() { local v; v="$(exget "$1")"; [ -n "$v" ] && printf '%s' "$v" || printf '%s' "$2"; }

# Ensure .conf/ is gitignored so secrets can never be committed.
ensure_gitignore() {
    local gi="${SCRIPT_DIR}/.gitignore"
    if ! { [ -f "$gi" ] && grep -qE '^/?\.conf/?$' "$gi"; }; then
        {
            printf '\n# BLADE install profiles (host facts, IPs, secrets) — never commit.\n'
            printf '/.conf/\n'
        } >> "$gi"
        ok "added /.conf/ to .gitignore"
    fi
}

# ============================================================================
# Profile state + interview phases
#
# The interview is broken into independent phases. load_profile reads the conf
# files into globals; each phase_* asks only its own questions (pre-filled from
# those globals) and updates them; save_profile rewrites the confs from the
# globals. So any single phase can be re-run from the dashboard without the full
# interview, and the conf files stay fully formed and commented.
# ============================================================================

# What I found on this machine — the pre-interview environment scan.
env_scan() {
    local _os _envmw="${MW_HOME:-}" _jd
    _os="$(uname -s)"
    log "${C_BOLD}What I found on this machine:${C_RESET}"
    printf '  %-9s %s\n' "OS:" "$_os"
    if [ -n "$_envmw" ]; then
        if occas_installed "$_envmw"; then
            printf '  %-9s %s   [OCCAS %s installed]\n' "MW_HOME:" "$_envmw" "$(detect_occas_version "$_envmw" || true)"
        else
            printf '  %-9s %s   [not installed here]\n' "MW_HOME:" "$_envmw"
        fi
    else
        printf '  %-9s %s\n' "MW_HOME:" "not set — you'll choose one below"
    fi
    _jd="$(jdk_describe "")" || true
    printf '  %-9s %s\n' "JDK:" "$_jd"
    log ""
    return 0
}

# Determine the OCCAS version (e.g. 8.3) from an installer jar. Authoritative
# source is INSIDE the jar: OUI component entries are named
# 'oracle_occas_server_8.3.0.0' etc. (the MANIFEST version is just the OUI
# launcher, not the product). Falls back to the path/filename when the jar can't
# be read (e.g. building a profile on a host without the jar).
installer_version() {
    local jar="$1" v=""
    if [ -f "$jar" ] && command -v unzip >/dev/null 2>&1; then
        v="$(unzip -l "$jar" 2>/dev/null \
              | grep -oE 'oracle_occas[a-zA-Z_]*_[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' \
              | grep -oE '[0-9]+\.[0-9]+' | head -1 || true)"
    fi
    [ -n "$v" ] || v="$({ basename "$jar" 2>/dev/null; printf '%s\n' "$jar"; } | grep -oE '[0-9]+\.[0-9]+' | head -1 || true)"
    printf '%s' "$v"
}

# Read the conf files into the profile globals (defaults when a key is absent).
# All state lives in globals (no 'local') so the phases can share and update it.
load_profile() {
    set_paths
    PF_OK="$(d preflight.passed "")"   # last preflight result, for the dashboard ✓
    DOMAIN="$(d domain.name "")"
    START_MODE="$(d server.start.mode dev)"
    ADMIN_USER="$(d admin.username weblogic)"
    # MaxMetaspaceSize is a CAP, not a reservation — raising it costs nothing
    # until the space is actually used. 512m is too low for BLADE's app set: a
    # full deploy dies with "OutOfMemoryError: Metaspace" partway through, and a
    # BLADE engine needs well over 1g for the full service set. Keep -Xms modest
    # so the two
    # JVMs on an admin box don't reserve the machine out from under each other.
    MEM_ARGS="$(d server.mem.args "-Xms512m -Xmx1536m -XX:MaxMetaspaceSize=2g")"
    # ORACLE_HOME is a SYMLINK, not the real directory. Patching builds a new
    # versioned home beside the current one and flips this link, so a patch is
    # atomic and a rollback is one flip. Everything -- the domain, the units,
    # Node Manager -- must resolve through it, or it cannot be swapped.
    #
    #   <base>/8.3.0        real GA home
    #   <base>/8.3.0_p1     patched copy
    #   <base>/current ->   the link; oracle.home points HERE
    OCCAS_BASE="$(d occas.base.dir /opt/oracle/occas)"
    OCCAS_VER="$(d occas.home.version "")"
    MWHOME="$(d oracle.home "${MW_HOME:-}")"
    [ -n "$MWHOME" ] || MWHOME="${OCCAS_BASE}/current"
    # Domains live OUTSIDE the home. Inside it, flipping the symlink would swing
    # the domain path onto the patched copy's snapshot of user_projects and
    # silently lose every config change made since the copy was taken.
    DOMAINS_DIR="$(d domains.dir "$(dirname "$OCCAS_BASE")/domains")"
    OCCAS_VERSION="$(d occas.version "")"
    INSTALLER_JAR="$(d installer.jar "")"
    # Beside the install, never under a home dir: the OUI runs as install.user,
    # and a 0700 /home/<anyone> is a wall for every other user on the box.
    INV_LOC="$(d inventory.loc "$(dirname "${OCCAS_BASE:-/opt/oracle/occas}")/oraInventory")"
    INV_GRP="$(d inventory.group oinstall)"
    INSTALL_USER="$(d install.user oracle)"
    # Numeric IDs default to Oracle's long-standing convention (oracle=54321,
    # oinstall=54321) — the ids Oracle's preinstall RPMs and container images use.
    # Override via install.uid/gid. See phase_occas.
    INSTALL_UID="$(d install.uid 54321)"
    INSTALL_GID="$(d install.gid 54321)"
    INSTALL_TYPE="$(d install.type 'Complete with Examples')"
    JAVA_HOME_VAL="$(d java.home "${JAVA_HOME:-}")"
    JAVA_BUILD_VAL="$(d java.build.home "")"   # the 23+ JDK for ./build.sh
    # Like the Oracle home, java.home is a SYMLINK on Linux: JDKs sit side by
    # side under java.dir and java.home points at <java.dir>/current, so a
    # Java upgrade is a flip of that one link (the patch step offers it).
    JAVA_BASE="$(d java.dir /opt/oracle/java)"
    prefix="$(d server.name.prefix engine)"
    match="$(d machine.match.expression "")"
    NM_DOMAIN="$(d nm.domain.name nmdomain)"
    NM_BIND="$(d nm.bind.address 0.0.0.0)"
    NM_PORT="$(d nm.listen.port 5556)"
    # Pinned: Node Manager always runs an SSL listener with its own permanent
    # blade-nm certificate (see do_nmdomain). An old profile saying nm.type=plain
    # is overridden — there is no un-certificated Node Manager deployment.
    NM_TYPE="$(d nm.type ssl)"
    [ "$NM_TYPE" = "ssl" ] || { warn "nm.type='${NM_TYPE}' is no longer supported — Node Manager is always SSL with its own certificate."; NM_TYPE=ssl; }
    DCOUNT="$(d dynamic.server.count "")"
    # DYN_MAX is the dynamic cluster's MaximumDynamicServerCount — a high FIXED
    # ceiling (default 1000), NOT the machine count. The actual running engines
    # follow the match expression (machine1..N), so "add a machine" just extends
    # the expression; the ceiling never needs resizing. setMaxDynamicClusterSize is
    # a no-op per the RE'd OCCAS code and the WLS setter rejects INT_MAX, so the
    # template line is commented out at stage time; MaximumDynamicServerCount alone
    # governs.
    DYN_MAX="$(d dynamic.server.max 1000)"
    # STATIC machine0/engine0/AdminServer: the admin box runs a configured engine0
    # (created by emit_static_engine0_block), OUTSIDE the dynamic template. The
    # DYNAMIC range therefore starts at 1 — engine1 on machine1, engine2 on
    # machine2 — with no off-by-one and no second engine on the admin box.
    SRV_START_INDEX="$(d server.name.starting.index 1)"
    BUILD_MODE="$(d build.mode dev)"
    # The LOGIN user, not the install user: cloud images only plant the ssh key
    # for their login account (opc, ec2-user). Privilege on the far side comes
    # from that user's sudo, applied per command — never from oracle-owned keys.
    SSH_USER="$(d ssh.user "$(id -un)")"
    # Default from the LIVE domain, not a hardcoded t3:7001 — once STEP 4 turns on
    # SSL, _wls_adminurl reads config.xml and returns t3s://<addr>:7002, so the
    # deploy page reflects the domain you actually built. Before any domain exists
    # it falls back to this box's own routable name (never localhost).
    ADMINURL="$(d wls.adminurl "$(_wls_adminurl)")"
    SSL_PORT="$(d tls.ssl.port 7002)"
    SIP_TLS="$(d sip.tls.enabled true)"
    SIP_PORT="$(d sip.tls.port 5061)"
    SIP_VER="$(d sip.tls.versions TLSv1.2)"
    SIP_TWOWAY="$(d sip.tls.twoway false)"
    # Plain SIP is on by default -- that is how OCCAS builds a domain. Turning it
    # off is a deliberate SIPS-only posture.
    SIP_PLAIN="$(d sip.plain.enabled true)"
    SIP_PLAIN_PORT="$(d sip.plain.port 5060)"
    # generate = self-signed internal CA; supply = the site's own certificate.
    # The WebLogic demo certificate is never an option: it is publicly known.
    # Keystores live in the domain's own config/certs: WebLogic replicates the
    # config tree to every managed server at startup, so the engines get their
    # keystores automatically -- no per-node push. It is still outside the Oracle
    # home (domains are), so a patch 'current' flip can't swap the certificates.
    # emit_tls_block writes this path + alias into the server template.
    KEYSTORE_DIR="$(d tls.keystore.dir "${DOMAINS_DIR}/${DOMAIN}/config/certs")"
    ID_ALIAS="$(d tls.identity.alias blade-identity)"
    # Logs live OUTSIDE the domain so they never clutter it or fill the data
    # volume: local, rotatable (logrotate), shippable. Node Manager's own log
    # goes to ${LOG_DIR}/nodemanager; server logs will follow here later.
    LOG_DIR="$(d log.dir /var/log/weblogic)"
    # false = every engine on the template's ports (the production shape);
    # true = base+index, which only suits several engines on one host.
    DYN_CALC_PORTS="$(d dynamic.calculated.ports false)"
    CERT_SOURCE="$(d cert.source generate)"
    CERT_P12="$(d cert.import.p12 "")"
    CERT_PEM="$(d cert.import.cert "")"
    CERT_KEY="$(d cert.import.key "")"
    CERT_CHAIN="$(d cert.import.chain "")"
    CA_CN="$(d tls.ca.cn 'BLADE Internal CA')"
    ID_CN="$(d tls.identity.cn "")"
    # --- nginx edge reverse proxy (this box only, the front door) -------------
    # When the nginx row runs, install.sh OWNS /etc/nginx/nginx.conf: TLS
    # terminates here and HTTP + WebSocket forward to the local WebLogic servers
    # (admin vhost → AdminServer, apps vhost → engine0). Blank server-names skip
    # the whole thing. backend.addr blank ⇒ derived from `hostname -I` at render
    # time: the AdminServer SSL listener binds its ListenAddress, not localhost,
    # so 127.0.0.1 can't reach it, and deriving live re-picks the right IP after
    # a compute re-image.
    NGX_ADMIN_SN="$(d nginx.server_name.admin "")"
    NGX_APPS_SN="$(d nginx.server_name.apps "")"
    NGX_BACKEND="$(d nginx.backend.addr "")"
    NGX_ADMIN_PORT="$(d nginx.admin.port "$SSL_PORT")"
    NGX_APPS_PORT="$(d nginx.apps.port 8001)"
    NGX_FULLCHAIN="$(d nginx.tls.fullchain "")"
    NGX_PRIVKEY="$(d nginx.tls.privkey "")"
    NGX_MAXBODY="$(d nginx.client.max.body.size 500m)"
    # on|off|auto — auto emits the naxsi includes only when the core ruleset is
    # present (naxsi is a compiled nginx module + rule files: a separate prereq).
    NGX_NAXSI="$(d nginx.naxsi auto)"
    # hosts → arrays. machine.N = name:addr:port:type; pub/fqdn in host.N.*
    H_NAME=(); H_ADDR=(); H_PORT=(); H_TYPE=(); H_PUB=(); H_FQDN=(); H_ROLE=()
    local i=1 m nm na np nt
    while :; do
        m="$(exget "machine.${i}")"; [ -n "$m" ] || break
        IFS=: read -r nm na np nt <<< "$m"
        H_NAME+=("$nm"); H_ADDR+=("$na"); H_PORT+=("${np:-$NM_PORT}"); H_TYPE+=("${nt:-$NM_TYPE}")
        H_PUB+=("$(exget "host.${i}.pub")"); H_FQDN+=("$(exget "host.${i}.fqdn")")
        [ "$i" -eq 1 ] && H_ROLE+=("admin") || H_ROLE+=("engine")
        i=$((i + 1))
    done
    migrate_profile
    return 0
}

# Bring a profile up to date for the STATIC-machine0/engine0 model.
#
# machine0/engine0/AdminServer are a STATIC pair on the admin box (created offline
# by emit_static_engine0_block); the DYNAMIC cluster is engine1..N on machine1..N.
# Older profiles — including the previous all-dynamic model where engine0 was a
# dynamic member on machine0 — get migrated in place:
#   * static.server = <prefix>0:machine0 is (re)asserted.
#   * machine.match.expression = the ENGINE machines only (machine1..N); machine0
#     is EXCLUDED so the dynamic calculation never lands a second engine on the
#     admin box (the off-by-one that put engine1 on machine0). Empty on a
#     single-box install — then the static engine0 is the whole tier.
#   * server.name.starting.index = 1 (the dynamic range starts at engine1).
#   * dynamic.server.max defaults to 1000 (a fixed ceiling, NOT the machine count).
migrate_profile() {
    [ -f "$OCCAS_CONF" ] || return 0
    local changed=0 want_static want_match i
    want_static="${prefix:-engine}0:${H_NAME[0]:-machine0}"
    # Engine machines only: array index 1..N; index 0 (machine0/admin) excluded.
    want_match=""
    for i in "${!H_NAME[@]}"; do
        [ "$i" -eq 0 ] && continue
        want_match="${want_match:+${want_match},}${H_NAME[$i]}"
    done

    if [ "$(read_prop "$OCCAS_CONF" static.server)" != "$want_static" ]; then
        set_conf_prop "$OCCAS_CONF" static.server "$want_static"; changed=1
    fi
    if [ "$(read_prop "$OCCAS_CONF" machine.match.expression)" != "$want_match" ]; then
        set_conf_prop "$OCCAS_CONF" machine.match.expression "$want_match"
        match="$want_match"; changed=1
    fi
    if [ "$(read_prop "$OCCAS_CONF" server.name.starting.index)" != "1" ]; then
        set_conf_prop "$OCCAS_CONF" server.name.starting.index 1
        SRV_START_INDEX=1; changed=1
    fi
    if [ -z "$(read_prop "$OCCAS_CONF" dynamic.server.max)" ]; then
        set_conf_prop "$OCCAS_CONF" dynamic.server.max "${DYN_MAX:-1000}"
        DYN_MAX="${DYN_MAX:-1000}"; changed=1
    fi
    [ "$changed" = 1 ] && warn "profile migrated: static ${want_static}; dynamic engines = ${want_match:-<none>} (start index 1, ceiling ${DYN_MAX:-1000})."
    return 0
}

# ----- phase 1: WebLogic domain ----------------------------------------------
phase_domain() {
    log ""; log "${C_BOLD}Domain & admin user${C_RESET}"
    help <<'EOF'
A WebLogic "domain" is a unique name for this deployment. It is NOT a DNS or
internet domain. It is simply the name of the directory in which all files go.
EOF
    while :; do
        ask DOMAIN "Domain" "$DOMAIN"
        case "$DOMAIN" in
            "")        warn "a domain name is required." ;;
            *[\ ]*)    warn "no spaces in a domain name." ;;
            *.*)       warn "that has a dot — a WebLogic domain is not a DNS name. Use it only if you're sure."; break ;;
            *)         break ;;
        esac
    done
    # New domains come up in 'dev' mode (simpler first run); the Tuning app
    # switches to production for performance/security.
    [ -n "$START_MODE" ] || START_MODE="dev"
    ask ADMIN_USER "Admin username" "$ADMIN_USER"
    return 0
}

# ----- phase 2: OCCAS location, version & JDK --------------------------------
phase_occas() {
    log ""; log "${C_BOLD}OCCAS — installer, location, version & Java${C_RESET}"
    env_scan
    help <<'EOF'
Point me at the OCCAS installer first — I read the version from it. Then the OS
user/group that will own the install (created by the 'Create install user &
group' step), and MW_HOME, the middleware home OCCAS uses (WL_HOME is
MW_HOME/wlserver). If OCCAS is already installed at MW_HOME, I read the version
from the install instead and skip the install step.
EOF
    # 1. Installer first — derive the OCCAS version from its path/name.
    # No usable jar on file? Hunt the home tree for one before asking — the
    # media usually landed somewhere under ~ and the path is a pain to type.
    if [ ! -f "${INSTALLER_JAR:-}" ]; then
        log "  ${C_DIM}looking for occas_generic.jar under ${HOME} …${C_RESET}"
        local _jar; _jar="$(find "$HOME" -name occas_generic.jar -not -path '*/.*' 2>/dev/null | head -1 || true)"
        [ -n "$_jar" ] && { INSTALLER_JAR="$_jar"; ok "found ${_jar}"; }
    fi
    ask INSTALLER_JAR "OCCAS installer jar (occas_generic.jar; Enter to skip if already installed)" "$INSTALLER_JAR"
    local dv=""
    [ -n "$INSTALLER_JAR" ] && dv="$(installer_version "$INSTALLER_JAR")"
    [ -n "$dv" ] && ok "read OCCAS version ${dv} from the installer"

    # 2. The OS user + group that will own OCCAS (the 'u' step creates them).
    ask INSTALL_USER "Install OS user (owns the OCCAS install)" "$INSTALL_USER"
    ask INV_GRP      "Install OS group"                         "$INV_GRP"
    if [ "${#H_NAME[@]}" -gt 1 ]; then
        help <<'EOF'
The numeric IDs default to Oracle's convention: oracle=54321, oinstall=54321
(the ids Oracle's preinstall RPMs and container images use). Enter to accept
them, or override if those numbers are already taken on any host.

Engine provisioning rsyncs as an ordinary ssh user, which cannot chown, so the
copied files simply end up owned by that user on the far side — names match and
the numbers do not have to. Agreeing on the numbers still matters in two cases:

  - MW_HOME on shared storage (NFS): the server checks NUMBERS, not names, so
    'oracle' as 54321 here and 1002 there is a genuine permission failure.
  - anything that copies as root, which does preserve numeric uid/gid.
EOF
        ask INSTALL_UID "Numeric uid for ${INSTALL_USER} (Oracle standard 54321)" "$INSTALL_UID"
        ask INSTALL_GID "Numeric gid for ${INV_GRP} (Oracle standard 54321)"      "$INSTALL_GID"
    fi

    # 3. Where OCCAS is / will be installed.
    while :; do
        ask MWHOME "MW_HOME (install location)" "$MWHOME"
        [ -n "$MWHOME" ] && break
        warn "MW_HOME is required — the directory OCCAS is (or will be) installed in."
    done

    # 4. Version: an existing install's registry wins; else the installer-derived
    #    value; else ask. The inventory/type inputs only matter for a fresh install.
    if occas_installed "$MWHOME"; then
        local v; v="$(detect_occas_version "$MWHOME")"
        if [ -n "$v" ]; then
            OCCAS_VERSION="$v"
            ok "OCCAS ${OCCAS_VERSION} already installed at ${MWHOME} — the install step will be skipped."
        else
            warn "OCCAS is installed at ${MWHOME} but its version is unreadable."
            while :; do ask OCCAS_VERSION "OCCAS version" "${OCCAS_VERSION:-$dv}"; [ -n "$OCCAS_VERSION" ] && break; warn "OCCAS version is required."; done
        fi
    else
        log "  ${C_DIM}no OCCAS at ${MWHOME} — configuring a fresh install.${C_RESET}"
        [ -n "$dv" ] && OCCAS_VERSION="$dv"
        while :; do
            ask OCCAS_VERSION "OCCAS version" "$OCCAS_VERSION"
            [ -n "$OCCAS_VERSION" ] && break
            warn "OCCAS version is required (e.g. 8.1, 8.3) — or point me at the installer above."
        done
        ask INV_LOC      "Oracle inventory location" "$INV_LOC"
        ask INSTALL_TYPE "Install type"              "$INSTALL_TYPE"
    fi

    # MW_HOME must be the STABLE '<base>/current' symlink, with the real versioned
    # home beside it. Everything downstream — the domain's setDomainEnv.sh, the
    # systemd units, Node Manager — bakes in whatever path this is, so pointing it
    # at 'current' is what makes a later patch a one-flip switch (sync-occas.sh
    # switch) with symmetric rollback, instead of a hunt-and-edit of every domain
    # config. Mirrors the java.home 'current' link below. Linux only; macOS dev
    # profiles keep the raw versioned path (they never patch or run servers).
    if [ "$(uname -s)" = "Linux" ]; then
        local _obase _ocur _oreal
        _obase="$(dirname "$MWHOME")"; _ocur="${_obase}/current"
        if occas_installed "$MWHOME"; then _oreal="$(readlink -f "$MWHOME")"   # adopt the real home
        elif [ "$MWHOME" = "$_ocur" ]; then _oreal="${_obase}/${OCCAS_VERSION}" # fresh: version names the dir
        else _oreal="$MWHOME"; fi                                              # fresh: honor the versioned path typed
        OCCAS_BASE="$_obase"
        OCCAS_VER="$(basename "$_oreal")"
        if [ "$MWHOME" != "$_ocur" ]; then
            if yesno "Use ${_ocur} as MW_HOME — a stable link to ${_oreal}? Lets a patch flip in one step and roll back the same way." "Y"; then
                # An existing install is published through the link now; a fresh
                # install's link is created by the install step once it lands.
                if occas_installed "$MWHOME"; then
                    if [ "$DRY" = "on" ]; then
                        log "${C_DIM}  [dry-run] ln -sfn ${_oreal} ${_ocur}${C_RESET}"
                    elif ln -sfn "$_oreal" "$_ocur" 2>/dev/null || sudo ln -sfn "$_oreal" "$_ocur" 2>/dev/null; then
                        ok "${_ocur} -> ${_oreal}"
                    else
                        warn "could not create ${_ocur} — keeping MW_HOME=${MWHOME}."; _ocur=""
                    fi
                fi
                [ -n "$_ocur" ] && MWHOME="$_ocur"
            fi
        fi
    fi

    # Two JDKs, on purpose. OCCAS ${OCCAS_VERSION} is CERTIFIED on a specific major
    # and it is NOT optional: a newer JDK breaks opatch's PSU/system-patch parsing
    # ("Unable to parse the xml file"), so the runtime — servers AND opatch — runs
    # the certified JDK, published as java.home (<java.dir>/current). The BLADE
    # build (./build.sh) separately needs 23+, so a newer JDK is fetched for the
    # build and published as <java.dir>/build. A JDK upgrade is then a link flip.
    log ""
    local _runmaj; _runmaj="$(occas_jdk_major "$OCCAS_VERSION")"; _runmaj="${_runmaj:-21}"
    local _bldmaj; _bldmaj="$(d java.build.major 25)"
    if [ "$(uname -s)" != "Linux" ]; then
        # macOS dev: no auto-download; the two JDKs are set up on the Linux target.
        [ -x "${JAVA_HOME_VAL}/bin/java" ] || JAVA_HOME_VAL="${JAVA_HOME:-}"
        log "  ${C_DIM}runtime JDK ${_runmaj} + build JDK ${_bldmaj} are for the Linux install target; set them up there.${C_RESET}"
        return 0
    fi

    info "Runtime JDK ${_runmaj} — certified for OCCAS ${OCCAS_VERSION} (servers + opatch); build JDK ${_bldmaj} — for ./build.sh."
    local _rt; _rt="$(ensure_jdk "$_runmaj")"
    if [ -n "$_rt" ] && [ -x "${_rt}/bin/java" ]; then
        JAVA_HOME_VAL="$(link_jdk "$_rt" current)"
    else
        warn "no runtime JDK ${_runmaj} available — OCCAS + opatch require it. Install it under ${JAVA_BASE:-/opt/oracle/java} and re-run this phase."
    fi
    local _bd; _bd="$(ensure_jdk "$_bldmaj")"
    if [ -n "$_bd" ] && [ -x "${_bd}/bin/java" ]; then
        JAVA_BUILD_VAL="$(link_jdk "$_bd" build)"
    else
        log "  ${C_DIM}build JDK ${_bldmaj} not set up — ./build.sh will need a 23+ JDK on its PATH.${C_RESET}"
    fi
    return 0
}

# ----- phase 3: hosts & Node Manager -----------------------------------------
phase_hosts() {
    log ""; log "${C_BOLD}Hosts & Node Manager${C_RESET}"
    help <<'EOF'
A WebLogic "Machine" is the logical name for one physical/virtual HOST. Node
Manager runs on each host; WebLogic "Servers" (the AdminServer, the SIP engine
instances) are assigned to a Machine so its Node Manager can start them. Below
you name the HOSTS — not the servers.

Two different addresses are involved, and they are NOT the same thing:
  - Node Manager BINDS to 0.0.0.0 — it listens on every interface of its host.
    That's fixed (set once below); you do not enter it per host.
  - Each host has a REACHABLE address (IP or hostname) that the AdminServer
    dials to talk to that host's Node Manager. THAT is what you enter per host.

For a local Mac dry-run, use 127.0.0.1 as the reachable address everywhere —
swap in the real OCI addresses when you copy this profile up.
EOF
    log ""
    log "  ${C_BOLD}Node Manager (same on every host)${C_RESET}"
    log "  ${C_DIM}binds to ${NM_BIND}; runs in its own basic domain '${NM_DOMAIN}', independent of the app domains.${C_RESET}"
    ask NM_PORT "  Node Manager listen port"      "$NM_PORT"
    log "  ${C_DIM}listener: always SSL, with NM's own permanent certificate (alias blade-nm) — not configurable.${C_RESET}"
    ask prefix  "SIP engine server name prefix"   "$prefix"
    match="${prefix}*"

    # THIS machine only. A blade install is complete on one box -- AdminServer
    # plus engine0 -- and extra capacity is added afterwards with "Add a machine",
    # which is an online operation. Asking for a three-box cluster up front forced
    # everyone through a shape most installs do not have, and growing later meant
    # hand-editing the profile and re-running configure, which clobbers the domain.
    local cur_an="${H_NAME[0]:-machine0}" cur_aa="${H_ADDR[0]:-}" cur_ap="${H_PUB[0]:-}" cur_af="${H_FQDN[0]:-}"
    [ -n "$cur_aa" ] || cur_aa="$(hostname -I 2>/dev/null | awk '{print $1}')"
    [ -n "$cur_aa" ] || cur_aa="127.0.0.1"
    H_NAME=(); H_ADDR=(); H_PORT=(); H_TYPE=(); H_PUB=(); H_FQDN=(); H_ROLE=()

    log ""
    log "  ${C_BOLD}This machine${C_RESET} — runs the AdminServer and ${prefix}0"
    local aname aaddr apub afqdn
    ask aname "  machine name (the HOST, not a server)" "$cur_an"
    ask aaddr "  reachable address (IP/host others dial for Node Manager)" "$cur_aa"
    ask apub  "  public IP (for the cert SAN; Enter to skip)" "$cur_ap"
    ask afqdn "  fully-qualified DNS name (for SAN; Enter to skip)" "$cur_af"
    H_NAME+=("$aname"); H_ADDR+=("$aaddr"); H_PORT+=("$NM_PORT"); H_TYPE+=("$NM_TYPE")
    H_PUB+=("$apub");   H_FQDN+=("$afqdn"); H_ROLE+=("admin")

    # Everything that was entered as an "engine host" here is now added later.
    log ""
    log "  ${C_DIM}More machines are added afterwards (dashboard: Add a machine),${C_RESET}"
    log "  ${C_DIM}which creates machine1 with ${prefix}1, machine2 with ${prefix}2, and so on.${C_RESET}"
    return 0
}

# ----- phase 4: dynamic cluster shape ----------------------------------------

# ----- phase 6: runtime / deploy ---------------------------------------------
phase_runtime() {
    log ""; log "${C_BOLD}Runtime / deploy settings${C_RESET}"
    ask BUILD_MODE "Build mode (dev|prod)" "$BUILD_MODE"
    ask SSH_USER      "SSH user for reaching engine nodes (reboot/provision)" "$SSH_USER"
    ask ADMINURL      "WebLogic admin URL (deploy runs ON the AdminServer)" "$ADMINURL"
    return 0
}

# ----- nginx edge reverse proxy ----------------------------------------------
# Only the front-door box runs this. Leave the server-names blank to skip it.
# Settings persist to the profile; the 'nginx' ACTION row renders + reloads.
phase_nginx() {
    log ""; log "${C_BOLD}nginx reverse proxy (edge TLS + WebSocket)${C_RESET}"
    help <<'EOF'
The public front door for THIS box. nginx terminates TLS and reverse-proxies to
the local WebLogic servers: the admin vhost → AdminServer (HTTPS), the apps
vhost → engine0 (HTTP). WebSocket upgrades (Configurator, WebRTC) are forwarded.
Leave a server-name blank to omit that vhost. Certs are supplied here (e.g.
Let's Encrypt); install.sh does not obtain or renew them.
EOF
    ask NGX_ADMIN_SN "  Admin vhost server_name (blank = none)" "$NGX_ADMIN_SN"
    ask NGX_APPS_SN  "  Apps vhost server_name (blank = none)"  "$NGX_APPS_SN"
    local defbe; defbe="${NGX_BACKEND:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
    ask NGX_BACKEND    "  Backend address (this box's WebLogic listen addr)" "$defbe"
    ask NGX_ADMIN_PORT "  AdminServer SSL port" "${NGX_ADMIN_PORT:-$SSL_PORT}"
    ask NGX_APPS_PORT  "  engine0 HTTP port"    "${NGX_APPS_PORT:-8001}"
    # Suggest the Let's Encrypt path for the cert's base domain (admin vhost minus
    # its first label), but any PEM pair is fine.
    local certbase="${NGX_ADMIN_SN#*.}"
    ask NGX_FULLCHAIN "  TLS fullchain PEM path"    "${NGX_FULLCHAIN:-${certbase:+/etc/letsencrypt/live/${certbase}/fullchain.pem}}"
    ask NGX_PRIVKEY   "  TLS private-key PEM path"  "${NGX_PRIVKEY:-${certbase:+/etc/letsencrypt/live/${certbase}/privkey.pem}}"
    ask NGX_MAXBODY   "  client_max_body_size (admin uploads)" "${NGX_MAXBODY:-500m}"
    ask NGX_NAXSI     "  naxsi WAF includes (on|off|auto)" "${NGX_NAXSI:-auto}"
    return 0
}

# ----- phase 7: TLS -----------------------------------------------------------
phase_tls() {
    log ""; log "${C_BOLD}TLS (HTTPS + SIP TLS)${C_RESET}"
    help <<'EOF'
Optional now — you can run the TLS steps later. If you set it up, the cert's
SAN list is built from every host name / FQDN / IP you entered above so one
identity cert satisfies hostname verification however a client connects.
EOF
    if yesno "Set up TLS settings now?" "Y"; then
        # Where the certificate comes from is now two explicit menu pages:
        # 'g' (generate a self-signed CA) and 'sup' (supply your own). This page
        # is the TLS TRANSPORT only -- SSL/SIPS ports and the keystore passphrases.

        # --- SIP channels ----------------------------------------------------
        # OCCAS gives every engine a plain 'sip' channel by default. These two
        # answers are written into the SERVER TEMPLATE at configure time, so
        # every dynamic engine — including ones added later — is stamped the
        # same way. No per-server retrofit.
        ask SSL_PORT "  HTTPS / t3s SSL port" "$SSL_PORT"
        if yesno "  Enable SIPS (SIP over TLS)?" "$([ "$SIP_TLS" = false ] && echo N || echo Y)"; then SIP_TLS=true; else SIP_TLS=false; fi
        if [ "$SIP_TLS" = "true" ]; then
            ask SIP_PORT "    SIPS port"            "$SIP_PORT"
            ask SIP_VER  "    Enabled TLS versions" "$SIP_VER"
            if yesno "    Mutual TLS to the SBC (two-way)?" "$([ "$SIP_TWOWAY" = true ] && echo Y || echo N)"; then SIP_TWOWAY=true; else SIP_TWOWAY=false; fi
            if yesno "  Disable plain SIP (${SIP_PLAIN_PORT:-5060}) — SIPS only?" "$([ "$SIP_PLAIN" = false ] && echo Y || echo N)"; then SIP_PLAIN=false; else SIP_PLAIN=true; fi
        else
            SIP_PLAIN=true
        fi
        [ "$SIP_PLAIN" != false ] && ask SIP_PLAIN_PORT "  Plain SIP port" "${SIP_PLAIN_PORT:-5060}"
        # Generate any missing TLS passphrase (per-key, never clobbering an
        # existing one — see ensure_tls_passphrases). Re-running TLS stays safe.
        ensure_tls_passphrases
    fi
    return 0
}

# ----- TLS certificate: generate a self-signed CA (the 'g' page) -------------
# Records cert.source=generate, collects the CA/identity CN, then runs make-certs.
do_cert_generate() {
    CERT_SOURCE=generate
    log ""; log "${C_BOLD}Generate a self-signed CA${C_RESET}"
    help <<'EOF'
Creates an internal CA and a server identity signed by it. Fine for a lab;
browsers and SBCs will NOT trust it by default. The identity SAN covers every
host / FQDN / IP you entered, so one cert satisfies hostname verification.
WebLogic's demo certificate is never used -- it is publicly known.
EOF
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] would generate a self-signed CA + identity into ~/.blade/${NAME} (make-certs)${C_RESET}"
        return 0
    fi
    ask CA_CN "  Internal CA common name"   "${CA_CN:-BLADE Internal CA}"
    [ -n "$ID_CN" ] || ID_CN="${H_FQDN[0]:-${H_NAME[0]:-}}"
    ask ID_CN "  Identity cert common name" "$ID_CN"
    [ "${PAGE_ABORT:-0}" = 1 ] && { warn "cancelled."; return 0; }
    save_profile
    ensure_tls_passphrases   # certs must be built with the passphrases the config records
    "${SCRIPT_DIR}/tls/make-certs.sh" "$DEPLOY_CONF" || warn "make-certs returned an error"
    # New certs reach Node Manager (and the boot env that must open its trust
    # store) only when the NM domain is re-created — which now refreshes it too.
    [ "$DRY" = "on" ] || [ ! -d "${DOMAINS_DIR}/${NM_DOMAIN}" ] || \
        log "  ${C_DIM}Certs changed: re-run 'Create & start Node Manager' to propagate them to NM and the boot env.${C_RESET}"
}

# ----- TLS certificate: supply your own (the 'sup' page) ---------------------
# Records cert.source=supply, collects the PKCS12 / PEM (+chain), then imports
# into the SAME keystore layout generate produces, so downstream is identical.
# Root-only sources (e.g. Let's Encrypt) are read via sudo by certs.sh.
do_cert_supply() {
    CERT_SOURCE=supply
    log ""; log "${C_BOLD}Supply your own certificate${C_RESET}"
    help <<'EOF'
Point at a PKCS12, or a PEM cert + key, optionally with a CA chain -- the normal
production answer. Root-only files (e.g. Let's Encrypt under /etc/letsencrypt)
are read via sudo. WebLogic's demo certificate is never used.
EOF
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] would import your certificate into ~/.blade/${NAME} (certs.sh import)${C_RESET}"
        return 0
    fi
    log "  Which format is your certificate in?"
    log "     ${C_BOLD}1${C_RESET}) PKCS12 — one .p12/.pfx bundling the cert + private key"
    log "     ${C_BOLD}2${C_RESET}) PEM    — separate cert and key files (Let's Encrypt, most CAs)"
    local fmt deffmt=2; [ -n "$CERT_P12" ] && deffmt=1
    ask fmt "  Choose 1 or 2" "$deffmt"
    [ "${PAGE_ABORT:-0}" = 1 ] && { warn "cancelled."; return 0; }
    case "$fmt" in
        1) CERT_PEM=""; CERT_KEY=""
           ask CERT_P12 "  PKCS12 file (.p12/.pfx)"    "$CERT_P12" ;;
        *) CERT_P12=""
           ask CERT_PEM "  server certificate (PEM)"   "$CERT_PEM"
           ask CERT_KEY "  private key (PEM)"          "$CERT_KEY" ;;
    esac
    ask CERT_CHAIN   "  CA chain (PEM, optional)"      "$CERT_CHAIN"
    [ "${PAGE_ABORT:-0}" = 1 ] && { warn "cancelled."; return 0; }
    save_profile
    "${SCRIPT_DIR}/certs.sh" "$DEPLOY_CONF" import || warn "certificate import returned an error"
    # New certs reach Node Manager (and the boot env that must open its trust
    # store) only when the NM domain is re-created — which now refreshes it too.
    [ "$DRY" = "on" ] || [ ! -d "${DOMAINS_DIR}/${NM_DOMAIN}" ] || \
        log "  ${C_DIM}Certs changed: re-run 'Create & start Node Manager' to propagate them to NM and the boot env.${C_RESET}"
}

# ----- phase 8: admin password (writes the gitignored secret files) ----------
phase_password() {
    log ""; log "${C_BOLD}Admin password${C_RESET}"
    help <<'EOF'
The password for the admin user above. It is set into the new domain by the
configure step and reused by deploy.sh to connect. Stored only in the
gitignored secret files (mode 600). Enter to skip and leave it unchanged.
EOF
    local pw=""
    ask_secret pw "Password for '${ADMIN_USER}'"
    if [ -z "$pw" ]; then warn "no password entered — left unchanged."; return 0; fi
    write_secret "$WLS_SECRET" admin.password "$pw" && ok "saved admin.password to the config (600)"
    return 0
}

# Write/update one key=value in a gitignored secret file (creates it 600).
write_secret() {
    local file="$1" key="$2" val="$3"
    # A file OUTSIDE the repo (e.g. ~/.blade/<env>.conf) can't be committed — safe.
    # A file INSIDE the repo must be gitignored before we write a secret to it.
    case "$file" in
        "$SCRIPT_DIR"/*) git -C "$SCRIPT_DIR" check-ignore -q "$file" 2>/dev/null \
            || { warn "${file#${SCRIPT_DIR}/} is inside the repo but not gitignored — refusing to write a secret."; return 1; } ;;
    esac
    if [ ! -f "$file" ]; then
        ( umask 077; printf '# BLADE config for %s (mode 600). Secrets are key=ENC(...).\n' "$NAME" > "$file" )
    fi
    set_conf_prop "$file" "$key" "ENC(${val})"   # ENC() marks a secret; decrypt hook lands later
    chmod 600 "$file"
    return 0
}

# Ensure the three TLS keystore passphrases exist in the config, generating ONLY
# the ones that are missing. Per-key and idempotent — this NEVER overwrites a
# passphrase that already exists, because an existing one may already be
# protecting a generated keystore (blade-ca/identity/trust). Regenerating it
# would orphan that keystore: keytool could no longer open it, so install-ssl and
# the NM-cert import both fail with "password was incorrect". The old code keyed
# the whole block on tls.ca.passphrase alone, so a run with ca absent but
# keystore/trust already present regenerated all three and orphaned the certs.
# make-certs.sh reads these from the same config, so once they are set here the
# certs it builds always match what the config records.
ensure_tls_passphrases() {
    local made=0 k
    for k in tls.ca.passphrase tls.keystore.passphrase tls.trust.passphrase; do
        [ -n "$(read_prop "$WLS_SECRET" "$k")" ] && continue
        write_secret "$WLS_SECRET" "$k" "$(gen_pass)" && made=$((made + 1))
    done
    [ "$made" -gt 0 ] && ok "generated ${made} random TLS keystore passphrase(s) (saved to the config)"
    return 0
}

# Rewrite occas.conf + deploy.conf from the current globals (keeps comments).
save_profile() {
    [ -n "$NAME" ] || { warn "no profile name — cannot save."; return 1; }
    mkdir -p "$BLADE_HOME" 2>/dev/null || true
    # Secrets share this file now, so capture them before the config rewrite and
    # re-append after (the config blocks below truncate then append to the file).
    local _secrets=""; [ -f "$BLADE_CONF" ] && _secrets="$(grep -E '^[a-zA-Z0-9._]+=ENC\(' "$BLADE_CONF" 2>/dev/null || true)"
    local stamp; stamp="$(date '+%Y-%m-%d %H:%M')"
    local OCCAS_BASE OCCAS_CURRENT KEYSTORE_DIR APPROUTER_DIR ENGINE_NODES SAN idx
    OCCAS_BASE="$(dirname "$MWHOME")"
    OCCAS_CURRENT="${OCCAS_BASE}/current"
    # In the domain's config/certs so it replicates to managed servers on start;
    # under DOMAINS_DIR (outside the Oracle home), so a patch 'current' flip can't
    # swap which certificates are live.
    KEYSTORE_DIR="${DOMAINS_DIR}/${DOMAIN}/config/certs"
    APPROUTER_DIR="${DOMAINS_DIR}/${DOMAIN}/approuter"

    ENGINE_NODES=""; idx=0
    while [ "$idx" -lt "${#H_NAME[@]}" ]; do
        [ "${H_ROLE[$idx]}" = "engine" ] && ENGINE_NODES="${ENGINE_NODES:+${ENGINE_NODES},}${H_NAME[$idx]}"
        idx=$((idx + 1))
    done

    # SAN from all host facts.
    SAN=""; local seen=" "
    _add_san() { case "$seen" in *" $1 "*) : ;; *) SAN="${SAN:+${SAN},}$1"; seen="${seen}$1 " ;; esac; }
    idx=0
    while [ "$idx" -lt "${#H_NAME[@]}" ]; do
        _add_san "dns:${H_NAME[$idx]}"
        [ -n "${H_FQDN[$idx]}" ] && _add_san "dns:${H_FQDN[$idx]}"
        if is_ip "${H_ADDR[$idx]}"; then _add_san "ip:${H_ADDR[$idx]}"; else _add_san "dns:${H_ADDR[$idx]}"; fi
        [ -n "${H_PUB[$idx]}" ] && _add_san "ip:${H_PUB[$idx]}"
        idx=$((idx + 1))
    done
    _add_san "dns:localhost"; _add_san "ip:127.0.0.1"

    # --- occas.conf ---
    {
        echo "# BLADE — OCCAS silent install + dynamic-cluster domain. Profile '${NAME}'."
        echo "# Generated by install.sh on ${stamp}. Re-run: ./install.sh ${NAME}"
        echo "# Consumed by ./install.sh. Admin password lives in the config."
        echo ""
        echo "# --- Step 1: silent product install (runs once; MW_HOME may be shared) ---"
        echo "# ORACLE_HOME is a SYMLINK to a versioned home. Patching builds a new"
        echo "# versioned home beside it and flips this link -- atomic, and a"
        echo "# rollback is one flip back."
        echo "occas.base.dir=${OCCAS_BASE}"
        echo "occas.home.version=${OCCAS_VER:-${OCCAS_VERSION}}"
        echo "oracle.home=${MWHOME}"
        echo "# Domains live OUTSIDE the Oracle home; inside it, a symlink flip"
        echo "# would swing them onto the patched copy's stale snapshot."
        echo "domains.dir=${DOMAINS_DIR}"
        echo "# Logs live outside the domain (local, rotatable). NM writes to"
        echo "# \${log.dir}/nodemanager; set to /tmp/... if you want them ephemeral."
        echo "log.dir=${LOG_DIR}"
        echo "occas.version=${OCCAS_VERSION}"
        echo "installer.jar=${INSTALLER_JAR}"
        echo "inventory.loc=${INV_LOC}"
        echo "inventory.group=${INV_GRP}"
        echo "install.user=${INSTALL_USER}"
        echo "# Numeric IDs. Blank = the OS picks, which is right for most setups."
        echo "# Pin them when MW_HOME is on shared storage (NFS matches on NUMBERS,"
        echo "# not names) or when anything copies the install as root."
        echo "install.uid=${INSTALL_UID}"
        echo "install.gid=${INSTALL_GID}"
        echo "install.type=${INSTALL_TYPE}"
        echo "# JDK the installer (and the servers it configures) run on. On Linux"
        echo "# java.home is the <java.dir>/current LINK: JDKs sit side by side under"
        echo "# java.dir and a Java upgrade is a flip of that one link (RUN patch)."
        echo "java.dir=${JAVA_BASE}"
        echo "java.home=${JAVA_HOME_VAL}"
        echo "# java.build.home is the 23+ JDK for ./build.sh (<java.dir>/build); the"
        echo "# runtime above is the OCCAS-certified major. java.build.major sets which."
        echo "java.build.home=${JAVA_BUILD_VAL:-}"
        echo "java.build.major=$(d java.build.major 25)"
        echo ""
        echo "# --- Step 2: dynamic-cluster domain ---"
        echo "# WebLogic domain = administrative container, NOT a DNS name. configure"
        echo "# writes with OverwriteDomain=true — pointing this at an EXISTING domain"
        echo "# directory CLOBBERS it."
        echo "domain.name=${DOMAIN}"
        echo "server.start.mode=${START_MODE}"
        echo "admin.username=${ADMIN_USER}"
        echo "# JVM args for the domain's servers (setUserOverrides.sh) — the OCCAS"
        echo "# dev default OOMs on Metaspace when the admin EAR deploys."
        echo "server.mem.args=${MEM_ARGS}"
        echo ""
        echo "# --- Cluster shape (BEA_ENGINE_TIER_CLUST): STATIC machine0/engine0 on the"
        echo "# admin box + DYNAMIC engine1..N on machine1..N ---"
        echo "server.name.prefix=${prefix}"
        echo "# static.server = the configured engine on the admin box (outside the template)"
        echo "static.server=${prefix:-engine}0:${H_NAME[0]:-machine0}"
        echo "# machine.match.expression = the ENGINE machines only (machine1..N); machine0 excluded"
        echo "machine.match.expression=${match}"
        echo "# server.name.starting.index = the DYNAMIC range start (1); engine0 is static, index 0"
        echo "server.name.starting.index=${SRV_START_INDEX:-1}"
        echo "# dynamic.server.max = MaximumDynamicServerCount, a fixed ceiling (NOT the machine count)"
        echo "dynamic.server.max=${DYN_MAX:-1000}"
        echo "dynamic.server.count=${DCOUNT}"
        echo ""
        echo "# --- Node Manager: its own basic domain '${NM_DOMAIN}', stable across app"
        echo "# domain rebuilds. NM binds ${NM_BIND} (all interfaces); each machine below"
        echo "# names the REACHABLE address the AdminServer dials. App domains enroll into"
        echo "# this NM (nmEnroll), so recreating a domain never restarts Node Manager. ---"
        echo "nm.domain.name=${NM_DOMAIN}"
        echo "nm.bind.address=${NM_BIND}"
        echo "nm.listen.port=${NM_PORT}"
        echo "nm.type=${NM_TYPE}"
        echo ""
        echo "# --- Physical machines (name:reachableAddr:nmPort:nmType; pub/fqdn for SANs) ---"
        idx=0
        while [ "$idx" -lt "${#H_NAME[@]}" ]; do
            echo "machine.$((idx+1))=${H_NAME[$idx]}:${H_ADDR[$idx]}:${H_PORT[$idx]}:${H_TYPE[$idx]}"
            [ -n "${H_PUB[$idx]}" ]  && echo "host.$((idx+1)).pub=${H_PUB[$idx]}"
            [ -n "${H_FQDN[$idx]}" ] && echo "host.$((idx+1)).fqdn=${H_FQDN[$idx]}"
            idx=$((idx + 1))
        done
    } > "$OCCAS_CONF"

    # --- deploy.conf ---
    {
        echo "# BLADE — deploy + TLS profile '${NAME}'. Generated by install.sh on ${stamp}."
        echo "# Consumed by ./deploy.sh and ./tls/*. Secrets live in the config."
        echo ""
        echo "# --- Build selection (shared with build.sh + deploy.sh) ---"
        echo "# Edit it all with:  ./build.sh --edit ${NAME}"
        echo "#   build.mode=dev    version <rev>, flat dist/ (fast loop);"
        echo "#             prod    version <rev>-<build>, dist/<rev>-<build>/ (traceable)"
        echo "#   build.apps=*      build every app ('*'), or a CSV of app names"
        echo "#   ear.<tier>=on     bundle the tier into blade-<tier>.ear; off = loose WARs"
        echo "# Defaults match the deploy shape: admin/test bundled, services loose."
        echo "build.mode=${BUILD_MODE}"
        echo "build.apps=*"
        echo "ear.admin=on"
        echo "ear.services=off"
        echo "ear.test=on"
        echo ""
        echo "# --- OCCAS binaries (sync-occas.sh) ---"
        echo "occas.base.dir=${OCCAS_BASE}"
        echo "occas.current.link=${OCCAS_CURRENT}"
        echo ""
        echo "# --- WebLogic connection (deploy runs ON the AdminServer) ---"
        echo "wls.adminurl=${ADMINURL}"
        echo "wls.user=${ADMIN_USER}"
        echo ""
        echo "# --- Deployment targets (WebLogic target NAMES, not hostnames) ---"
        echo "wls.targets.admin=AdminServer"
        echo "wls.targets.cluster=BEA_ENGINE_TIER_CLUST"
        echo "wls.targets.both=AdminServer,BEA_ENGINE_TIER_CLUST"
        echo "wls.targets.test=${prefix:-engine}0"
        echo ""
        echo "# --- FSMAR install destination + engine nodes (the 'fsmar' tier) ---"
        echo "ssh.user=${SSH_USER}"
        echo "approuter.dir=${APPROUTER_DIR}"
        echo "engine.nodes=${ENGINE_NODES}"
        echo ""
        echo "# --- Which service WARs to deploy ---"
        echo "deploy.services=*"
        echo ""
        echo "# --- TLS / certificates (tls/make-certs.sh + tls/install-ssl.sh) ---"
        echo "tls.san=${SAN}"
        echo "tls.ca.cn=${CA_CN}"
        echo "tls.identity.cn=${ID_CN}"
        echo "tls.identity.alias=blade-identity"
        echo "tls.validity.days=825"
        echo "tls.key.size=2048"
        echo "tls.keystore.dir=${KEYSTORE_DIR}"
        echo "tls.ssl.port=${SSL_PORT}"
        echo "# Where the server certificate comes from: 'supply' (the site's own,"
        echo "# the normal production answer) or 'generate' (self-signed internal CA)."
        echo "# WebLogic's demo certificate is never used -- it is publicly known."
        echo "cert.source=${CERT_SOURCE}"
        echo "cert.import.p12=${CERT_P12}"
        echo "cert.import.cert=${CERT_PEM}"
        echo "cert.import.key=${CERT_KEY}"
        echo "cert.import.chain=${CERT_CHAIN}"
        echo "# SIP channels, written into the SERVER TEMPLATE at configure time so"
        echo "# every dynamic engine -- present and future -- is stamped identically."
        echo "sip.plain.enabled=${SIP_PLAIN}"
        echo "sip.plain.port=${SIP_PLAIN_PORT}"
        echo "sip.tls.enabled=${SIP_TLS}"
        echo "sip.tls.port=${SIP_PORT}"
        echo "sip.tls.versions=${SIP_VER}"
        echo "sip.tls.twoway=${SIP_TWOWAY}"
        echo ""
        echo "# --- nginx edge reverse proxy (the 'nginx' row installs it) -------------"
        echo "# install.sh owns /etc/nginx/nginx.conf on THIS box: TLS terminates here"
        echo "# and forwards HTTP + WebSocket to the AdminServer (admin vhost) and"
        echo "# engine0 (apps vhost). Blank server-names skip the row. backend.addr"
        echo "# blank ⇒ derived from 'hostname -I' at render time (re-image-safe: the"
        echo "# AdminServer SSL listener binds its ListenAddress, so localhost can't"
        echo "# reach it). Certs are supplied here (e.g. Let's Encrypt); install.sh"
        echo "# does not obtain or renew them."
        echo "nginx.server_name.admin=${NGX_ADMIN_SN}"
        echo "nginx.server_name.apps=${NGX_APPS_SN}"
        echo "nginx.backend.addr=${NGX_BACKEND}"
        echo "nginx.admin.port=${NGX_ADMIN_PORT}"
        echo "nginx.apps.port=${NGX_APPS_PORT}"
        echo "nginx.tls.fullchain=${NGX_FULLCHAIN}"
        echo "nginx.tls.privkey=${NGX_PRIVKEY}"
        echo "nginx.client.max.body.size=${NGX_MAXBODY}"
        echo "# naxsi WAF includes: on|off|auto (auto = on only if naxsi_core.rules exists)"
        echo "nginx.naxsi=${NGX_NAXSI}"
    } >> "$DEPLOY_CONF"
    # Restore the secrets captured before the rewrite (ENC()-wrapped, mode 600).
    [ -n "$_secrets" ] && printf '%s\n' "$_secrets" >> "$BLADE_CONF"
    chmod 600 "$BLADE_CONF" 2>/dev/null || true
    return 0
}

# Full interview (all phases in order) — for first-time setup / 'all'.
run_wizard() {
    set_paths
    log ""
    banner
    [ -n "$NAME" ] && [ -f "$OCCAS_CONF" ] && log "  editing profile '${NAME}' (its values are offered as defaults)"
    load_profile
    # Journey order; phase_occas opens with the environment scan.
    phase_occas; phase_domain; phase_hosts
    phase_tls; phase_runtime
    if [ -z "$NAME" ]; then ask NAME "Save profile as" "$DOMAIN"; set_paths; fi
    [ -n "$NAME" ] || die "a profile name is required."
    save_profile
    ok "wrote ${OCCAS_CONF#${SCRIPT_DIR}/}"
    ok "wrote ${DEPLOY_CONF#${SCRIPT_DIR}/}"
    phase_password
    log ""; ok "Profile '${NAME}' ready."
    return 0
}

# ============================================================================
# The dashboard — a journey-ordered, cursor-driven menu.
#
# Phases (edit the profile) and host actions are interleaved under plain-language
# STEP headers. In a terminal you navigate with the arrow keys, toggle rows with
# space, and run the checked set with Enter. With no TTY (pipes/headless) it
# falls back to a typed numbered menu. Both render from build_menu_rows() and
# execute through dispatch_row(), so the two never drift apart.
# ============================================================================
DRY="${DRY:-off}"   # may be pre-set to "on" by the --dry-run flag during arg parse

# One-line summaries for the busier rows.
_sum_occas() {
    local s="${MWHOME:-—}"
    [ -n "$OCCAS_VERSION" ] && s="${s} · ${OCCAS_VERSION}"
    [ -n "$JAVA_HOME_VAL" ] && [ -x "${JAVA_HOME_VAL}/bin/java" ] && s="${s} · JDK $(jdk_major "${JAVA_HOME_VAL}/bin/java")"
    printf '%s' "$s"
}
_sum_tls() {
    if [ "$SIP_TLS" = "true" ]; then printf 'https :%s · sips :%s %s' "$SSL_PORT" "$SIP_PORT" "$SIP_VER"
    else printf 'https :%s · sip-tls off' "$SSL_PORT"; fi
}
_pw_set() { [ -f "$WLS_SECRET" ] && [ -n "$(read_prop "$WLS_SECRET" admin.password)" ]; }
_sum_lastmachine() {
    local n=$(( ${#H_NAME[@]} - 1 ))
    [ "$n" -ge 1 ] && printf '%s (%s%s)' "${H_NAME[$n]}" "${prefix:-engine}" "$n" || printf 'none — single machine'
}
_sum_engines() {
    local n=0 i
    for i in "${!H_NAME[@]}"; do [ "${H_ROLE[$i]}" = "engine" ] && n=$((n + 1)); done
    [ "$n" -eq 0 ] && { printf 'none — single host'; return; }
    printf '%d host(s) as %s' "$n" "${SSH_USER:-$(id -un)}"
}

# Build the current menu into MR_* parallel arrays (shared by TUI + fallback):
#   MR_TYPE head|phase|action   MR_ID   MR_LABEL   MR_VAL   MR_DONE(1|0|-)
build_menu_rows() {
    MR_TYPE=(); MR_ID=(); MR_LABEL=(); MR_VAL=(); MR_DONE=()
    local nhosts="${#H_NAME[@]}"
    local p_occas=0; { [ -n "$MWHOME" ] && [ -n "$OCCAS_VERSION" ] && [ -n "$JAVA_HOME_VAL" ]; } && p_occas=1
    local p_ident=0; [ -n "$DOMAIN" ] && p_ident=1
    local p_hosts=0; [ "$nhosts" -ge 1 ] && p_hosts=1
    local p_tls=0;   [ -n "$SSL_PORT" ] && p_tls=1
    local p_run=0;   { [ -n "$BUILD_MODE" ] && [ -n "$ADMINURL" ]; } && p_run=1
    local a_i=0; [ -d "${MWHOME}/wlserver" ] && a_i=1
    local a_n=0; [ -d "${DOMAINS_DIR}/${NM_DOMAIN}" ] && a_n=1
    local a_c=0; [ -d "${DOMAINS_DIR}/${DOMAIN}" ] && a_c=1
    # Boot-service rows are "done" only when the unit is installed AND points at
    # our own domain (the same key the guarded teardown uses).
    local a_e=0; grep -qsF "${DOMAINS_DIR}/${NM_DOMAIN}" /etc/systemd/system/nodemanager.service && a_e=1
    local a_w=0; grep -qsF "${DOMAINS_DIR}/${DOMAIN}"    /etc/systemd/system/weblogic.service    && a_w=1
    local nm_state="stopped"; nm_listening "$NM_PORT" && nm_state="running"
    # The AdminServer binds its ListenAddress (not loopback), so a 127.0.0.1 port
    # probe would read "down" on a live server — match the JVM instead. Local, like
    # the NM check: accurate on the admin box, where install.sh and the domain live.
    local admin_state="stopped"; pgrep -f 'weblogic.Name=AdminServer' >/dev/null 2>&1 && admin_state="running"
    local pwlbl="—"; _pw_set && pwlbl="set"

    _row() { MR_TYPE+=("$1"); MR_ID+=("$2"); MR_LABEL+=("$3"); MR_VAL+=("$4"); MR_DONE+=("$5"); }

    local a_u=0; id "${INSTALL_USER:-oracle}" >/dev/null 2>&1 && a_u=1
    local a_m=0; [ -n "$MWHOME" ] && [ -d "$MWHOME" ] && a_m=1
    _row head ""      "STEP 1 · Point at OCCAS, then install it" "" "-"
    _row phase  occas "Where OCCAS lives — home, version, Java"  "$(_sum_occas)" "$p_occas"
    _row action u     "Create install user & group"             "${INSTALL_USER:-oracle}:${INV_GRP:-oinstall}" "$a_u"
    _row action m     "Create install dirs & chown"             "MW_HOME + inventory" "$a_m"
    # "Done" means the media is no longer needed — either it's downloaded, or the
    # product is already installed and never will be.
    local a_dl=0 dl_lbl=""
    if [ -d "${MWHOME}/wlserver" ]; then a_dl=1; dl_lbl="not needed — installed"
    elif [ -n "$INSTALLER_JAR" ] && [ -f "$INSTALLER_JAR" ]; then a_dl=1; dl_lbl="installer present"; fi
    _row action dl    "Download OCCAS media (eDelivery)"        "$dl_lbl" "$a_dl"
    _row action p     "Preflight host checks"                    "" "$(case "${PF_OK:-}" in 1) echo 1;; 0) echo 0;; *) echo -;; esac)"
    _row action i     "Install OCCAS"                            "$([ "$a_i" = 1 ] && echo installed || echo '')" "$a_i"
    _row action patch "Patch" "" "-"
    _row head ""      "STEP 2 · Name it & set the admin login"   "" "-"
    _row phase  ident "Domain name + admin user & password"      "${DOMAIN:-—} / ${ADMIN_USER} · pw ${pwlbl}" "$p_ident"
    _row head ""      "STEP 3 · Describe your machines"          "" "-"
    _row phase  hosts   "Hosts & Node Manager"     "${nhosts} host(s) · ${NM_DOMAIN}@${NM_BIND}:${NM_PORT} ${NM_TYPE}" "$p_hosts"
    _row head ""      "STEP 4 · TLS certificate (HTTPS/SIP-TLS is stamped on the template at configure)" "" "-"
    _row phase  tls "TLS settings"          "$(_sum_tls)" "$p_tls"
    _row action g   "Generate a self-signed CA"   "self-signed internal CA$([ "${CERT_SOURCE:-generate}" = generate ] && echo ' · current')" "-"
    _row action sup "Supply your own certificate" "PKCS12 / PEM (e.g. Let’s Encrypt)$([ "${CERT_SOURCE:-}" = supply ] && echo " · current: ${CERT_P12:-${CERT_PEM:-set}}")" "-"
    _row head ""      "STEP 5 · Start it up (in order)"          "" "-"
    _row action n "Create & start Node Manager" "${NM_DOMAIN} — ${nm_state}" "$a_n"
    _row action c "Create the cluster domain"   "${DOMAIN:-?}" "$a_c"
    _row action s "Start the AdminServer"       "AdminServer — ${admin_state}" "$([ "$admin_state" = running ] && echo 1 || echo -)"
    _row action x "Stop the AdminServer"        "" "-"
    _row action k "Stop Node Manager"           "" "-"
    _row action e "Install Node Manager boot service (systemd)"  "nodemanager.service" "$a_e"
    _row action w "Install AdminServer boot service (via NM)"    "weblogic.service"    "$a_w"
    _row action addm "Add a machine (grows the cluster online)" "$(printf 'next: machine%s → %s%s' "${#H_NAME[@]}" "${prefix:-engine}" "${#H_NAME[@]}")" "-"
    _row action remm "Remove the last machine"                   "$(_sum_lastmachine)" "-"
    _row action E "Re-provision every engine host"               "$(_sum_engines)" "-"
    _row action verify "Verify the cluster (health-check every node)" "" "-"
    _row action o "Deploy WebLogic Remote Console (/rconsole)" "" "-"
    _row action f "Open firewall ports (firewalld)"              "NM/admin/ssl$([ "${SIP_TLS:-false}" = true ] && printf /sip)" "-"
    local p_nginx ngxsum
    if [ -n "${NGX_ADMIN_SN}${NGX_APPS_SN}" ]; then p_nginx=1; ngxsum="${NGX_ADMIN_SN:-—}${NGX_APPS_SN:+, ${NGX_APPS_SN}}"; else p_nginx=-; ngxsum="not configured"; fi
    _row phase  nginx "nginx reverse proxy (edge TLS + WebSocket)" "$ngxsum" "$p_nginx"
    _row action ngx   "Install/refresh nginx config (validate + reload)" "$([ -f /etc/nginx/nginx.conf ] && echo /etc/nginx/nginx.conf)" "-"
    _row head ""      "STEP 6 · Deploy settings (build profile, SSH, admin URL)" "" "-"
    _row phase runtime "Build mode, SSH user, admin URL" "${BUILD_MODE} · ${ADMINURL}" "$p_run"
    # App deployment lives in deploy.sh (it reads THIS profile). install.sh stands
    # up the server; it no longer deploys apps.
    _row head ""      "STEP 7 · Deploy apps → run:  ./deploy.sh ${NAME:-<env>} --all" "" "-"
    # UNINSTALL · listed top-to-bottom in safe teardown order (reverse of STEP 1).
    # The checked set runs in menu order, so ticking any subset tears down safely.
    # Each ✓ means "still present / removable"; each row confirms before deleting.
    local a_repo=0; [ -d "${SCRIPT_DIR}/.git" ] && a_repo=1
    _row head ""      "UNINSTALL · tick what to remove; runs top-to-bottom"  "" "-"
    _row action r  "Remove this app domain + profile (stop, delete, un-enroll)" "${DOMAIN:-?}" "$a_c"
    _row action b  "Remove Node Manager domain + systemd unit"    "${NM_DOMAIN:-?}" "$a_n"
    _row action di "Deinstall OCCAS product (Oracle deinstaller)"  "${MWHOME:-?}" "$a_i"
    _row action md "Remove install dirs (MW_HOME + inventory)"     "${MWHOME:-?}" "$a_m"
    _row action ug "Remove install user & group"                   "${INSTALL_USER:-oracle}:${INV_GRP:-oinstall}" "$a_u"
    _row action repo "Delete local BLADE repo clone (NOT GitHub)"  "${SCRIPT_DIR}" "$a_repo"
    _row head ""        "PROFILE · this environment's ~/.blade profile"            "" "-"
    _row action clonep  "Save as a NEW environment (clone; drops secrets & certs)" "${NAME:+from ${NAME}}" "-"
    _row action renamep "Rename this environment"                                  "${NAME:-—}" "-"
    _row action delp    "Delete this profile (config + certs; NOT the server)"     "${NAME:-—}" "-"
    unset -f _row
}

# Run one row by id (phase → edit + save; action → its worker). Shared dispatch.
# Esc during a phase form cancels it: _save() skips the save so partial edits are
# discarded (the menu reloads clean). PAGE_ABORT is cleared per dispatch.
dispatch_row() {
    local dr=""; PAGE_ABORT=0
    _save() { if [ "${PAGE_ABORT:-0}" = 1 ]; then warn "cancelled — no changes saved."; else save_profile; fi; }
    case "$1" in
        occas)   phase_occas;   _save ;;
        ident)   phase_domain;  phase_password; _save ;;
        hosts)   phase_hosts;   _save ;;
        tls)     phase_tls;     _save ;;
        runtime) phase_runtime; _save ;;
        nginx)   phase_nginx;   _save ;;
        u) do_makeuser  || true ;;
        m) do_makedirs  || true ;;
        dl) do_download  || true ;;
        patch) do_patch  || true ;;
        p) do_preflight || true ;;
        i) do_install   || warn "install returned an error" ;;
        n) do_nmdomain  || warn "nm-domain returned an error" ;;
        c) do_configure || warn "configure returned an error" ;;
        s) start_admin "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        e) do_install_nm_service  || true ;;
        w) do_install_wls_service || true ;;
        addm) do_add_machine      || true ;;
        remm) do_remove_machine   || true ;;
        E) do_provision_engines   || true ;;
        o) do_console             || true ;;
        f) do_open_firewall || true ;;
        ngx) do_install_nginx || true ;;
        x) stop_admin  "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        verify) do_verify || true ;;
        k) stop_nm || true ;;
        r) do_remove_domain "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        b) do_remove_nmdomain || true ;;
        di)   do_deinstall     || true ;;
        md)   do_remove_dirs   || true ;;
        ug)   do_remove_usergrp || true ;;
        repo) do_remove_repo   || true ;;
        clonep)  do_clone_profile  || true ;;
        renamep) do_rename_profile || true ;;
        delp)    do_delete_profile || true ;;
        g)   do_cert_generate || true ;;
        sup) do_cert_supply   || true ;;
        *) warn "unknown row: $1" ;;
    esac
}

# Coloured glyph for a done flag (used by the typed fallback).
_done_glyph() {
    case "$1" in
        1) printf '%b' "${C_GREEN}\xe2\x9c\x93${C_RESET}" ;;
        0) printf '%b' "\xe2\x97\x8b" ;;
        *) printf ' ' ;;
    esac
}

# Read one keypress; echo a token: up|down|space|enter|dry|quit|other.
# Fractional read -t needs bash 4; on 3.2 (stock macOS) fall back to 1s, which
# only delays a bare Esc press -- arrow-key bytes arrive together.
_ESC_T=0.05; [ "${BASH_VERSINFO[0]}" -lt 4 ] && _ESC_T=1
_read_key() {
    local k r
    IFS= read -rsn1 k 2>/dev/null || { printf 'quit'; return; }
    case "$k" in
        $'\e') IFS= read -rsn2 -t "$_ESC_T" r 2>/dev/null || r=""
               # Bare Esc (no trailing bytes) = go up a level → quit here at the
               # top; arrows move; any other escape sequence is ignored.
               case "$r" in '[A'|'OA') printf 'up' ;; '[B'|'OB') printf 'down' ;; '') printf 'quit' ;; *) printf 'other' ;; esac ;;
        '')    printf 'enter' ;;
        ' ')   printf 'space' ;;
        d|D)   printf 'dry' ;;
        q|Q)   printf 'quit' ;;
        [0-9]) printf 'num:%s' "$k" ;;       # jump the cursor to that STEP
        [a-zA-Z]) printf 'other' ;;          # no per-row letter shortcuts
        *)     printf 'other' ;;
    esac
}

# Cursor-driven TUI: ↑/↓ move, space toggles [x], Enter runs the checked set
# (or the highlighted row if none checked), d toggles dry-run, q quits.
dashboard_tui() {
    # Checked row ids as a space-delimited string, not an associative array --
    # macOS ships bash 3.2 and `declare -A` doesn't exist there.
    local CHK=""
    _chk_has() { case " $CHK " in *" $1 "*) return 0 ;; esac; return 1; }
    # Run one or more row ids: leave the alt-screen, dispatch each, then pause and
    # reload the profile. Returns 1 when the profile/repo vanished (caller breaks).
    # Print the return prompt. When the last action registered follow-up steps
    # (next_step), name them (by description, no letters) as guidance for what to
    # select next on the dashboard.
    _return_prompt() {
        if [ "${#NEXT_D[@]}" -gt 0 ]; then
            printf '\n  %sNext:%s ' "$C_BOLD" "$C_RESET"
            local i sep=""
            for i in "${!NEXT_D[@]}"; do printf '%s%s' "$sep" "${NEXT_D[$i]}"; sep=" · "; done
            printf '\n'
        fi
        printf '  %spress Enter to return…%s ' "$C_DIM" "$C_RESET"
    }
    # Breadcrumb under the banner naming the step(s) being run, so an action screen
    # says which page you're on. Looks the label up from the row id(s).
    _tui_subtitle() {
        local id j lbl out=""
        for id in "$@"; do
            lbl=""
            for j in "${!MR_ID[@]}"; do [ "${MR_ID[$j]}" = "$id" ] && { lbl="${MR_LABEL[$j]}"; break; }; done
            [ -n "$lbl" ] && out="${out:+$out · }$lbl"
        done
        [ -n "$out" ] && printf '  %s%s%s\n' "$C_BOLD" "$out" "$C_RESET"
    }
    _tui_run() {
        printf '\e[?25h'; trap - INT
        printf '\e[2J\e[H'; banner; _tui_subtitle "$@"; printf '\n'
        next_step_reset
        local rid; for rid in "$@"; do dispatch_row "$rid"; done
        CHK=""
        { [ "$PROFILE_GONE" = 1 ] || [ "$REPO_GONE" = 1 ]; } && return 1
        # Pause so the action's output is readable, then return to the dashboard
        # (arrow-navigate to whatever's next). No letter chaining, no shortcuts.
        _return_prompt
        _read_key >/dev/null
        load_profile
        printf '\e[?25l'; trap 'printf "\e[?25h\n"' EXIT INT
        return 0
    }
    local sel=0
    printf '\e[?25l'                                   # hide cursor
    trap 'printf "\e[?25h\n"' EXIT INT
    while :; do
        build_menu_rows
        local selrows=() i
        for i in "${!MR_TYPE[@]}"; do [ "${MR_TYPE[$i]}" != head ] && selrows+=("$i"); done
        local nsel="${#selrows[@]}"
        [ "$sel" -lt 0 ] && sel=$((nsel - 1))
        [ "$sel" -ge "$nsel" ] && sel=0
        local cur="${selrows[$sel]}"

        # Accordion: expand only the STEP section holding the highlighted row;
        # the rest collapse to a one-line header (with a hidden-row count). Keeps
        # the whole board inside a short terminal — a PuTTY window won't scroll.
        # Pre-pass: which section is 'cur' in, and how many rows each section has.
        local _sec=-1 _cursec=0 _k; local _seccount=()
        for _k in "${!MR_TYPE[@]}"; do
            if [ "${MR_TYPE[$_k]}" = head ]; then _sec=$((_sec + 1)); _seccount[$_sec]=0
            else _seccount[$_sec]=$(( ${_seccount[$_sec]:-0} + 1 )); fi
            [ "$_k" = "$cur" ] && _cursec=$_sec
        done

        printf '\e[2J\e[H'
        banner
        printf '  %sprofile %s%s        dry-run: %s\n' "$C_DIM" "$NAME" "$C_RESET" "$DRY"
        local _rs=-1
        for i in "${!MR_TYPE[@]}"; do
            if [ "${MR_TYPE[$i]}" = head ]; then
                _rs=$((_rs + 1))
                if [ "$_rs" = "$_cursec" ]; then
                    printf '\n  %s▾ %s%s\n' "$C_BOLD" "${MR_LABEL[$i]}" "$C_RESET"
                else
                    printf '  %s▸ %s%s %s(%s)%s\n' "$C_BOLD" "${MR_LABEL[$i]}" "$C_RESET" "$C_DIM" "${_seccount[$_rs]:-0}" "$C_RESET"
                fi
                continue
            fi
            [ "$_rs" = "$_cursec" ] || continue   # collapsed section — hide its rows
            local box="[ ]"; _chk_has "${MR_ID[$i]}" && box="[x]"
            local g=" "; case "${MR_DONE[$i]}" in 1) g="✓" ;; 0) g="○" ;; esac
            if [ "$i" = "$cur" ]; then
                printf '\e[7m   %s %s %-44s %s \e[0m\n' "$box" "$g" "${MR_LABEL[$i]}" "${MR_VAL[$i]}"
            else
                printf '   %s %s %-44s %s%s%s\n' "$box" "$g" "${MR_LABEL[$i]}" "$C_DIM" "${MR_VAL[$i]}" "$C_RESET"
            fi
        done
        printf '\n  %s↑/↓%s move · %s1-8%s jump to step · %sspace%s select · %senter%s run · %sd%s dry-run · %sEsc/q%s quit\n' \
               "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET"

        local kpress; kpress="$(_read_key)"
        case "$kpress" in
            up)    sel=$((sel - 1)) ;;
            down)  sel=$((sel + 1)) ;;
            space) local id="${MR_ID[$cur]}"
                   if _chk_has "$id"; then
                       CHK=" $CHK "; CHK="${CHK/ $id / }"
                       CHK="${CHK# }"; CHK="${CHK% }"
                   else
                       CHK="${CHK:+$CHK }$id"
                   fi ;;
            dry)   [ "$DRY" = "on" ] && DRY="off" || DRY="on" ;;
            enter) local runids=() j id
                   for j in "${!MR_TYPE[@]}"; do
                       [ "${MR_TYPE[$j]}" = head ] && continue
                       id="${MR_ID[$j]}"; _chk_has "$id" && runids+=("$id")
                   done
                   [ "${#runids[@]}" -eq 0 ] && runids=("${MR_ID[$cur]}")
                   _tui_run "${runids[@]}" || break ;;
            quit)  break ;;
            num:*) # Jump the highlight to the first row of STEP <n> (1-based).
                   # The accordion follows the cursor, so this expands that step
                   # and collapses the rest. Out-of-range digits are a no-op.
                   local _want="${kpress#num:}" _s=-1 _kk _tgt=-1
                   for _kk in "${!MR_TYPE[@]}"; do
                       if [ "${MR_TYPE[$_kk]}" = head ]; then _s=$((_s + 1))
                       elif [ "$_s" = "$((_want - 1))" ]; then _tgt=$_kk; break; fi
                   done
                   if [ "$_tgt" -ge 0 ]; then
                       local _jj
                       for _jj in "${!selrows[@]}"; do
                           [ "${selrows[$_jj]}" = "$_tgt" ] && { sel=$_jj; break; }
                       done
                   fi ;;
            *)     : ;;   # no per-row letter shortcuts — navigate + Enter
        esac
    done
    printf '\e[?25h'; trap - EXIT INT
    log ""
    if [ "$REPO_GONE" = 1 ]; then
        log "  ${C_DIM}Local BLADE clone at ${SCRIPT_DIR} is being removed. GitHub remote is untouched.${C_RESET}"
    elif [ "$PROFILE_GONE" = 1 ]; then
        log "  ${C_DIM}Profile '${NAME}' removed. Re-run ./install.sh to pick or create another.${C_RESET}"
    else
        log "  ${C_DIM}Next: ./build.sh   then   ./deploy.sh ${NAME}${C_RESET}"
    fi
    return 0
}

# Typed fallback (no TTY): the same rows, numbered; run by number(s)/all/d/q.
dashboard_menu() {
    while :; do
        build_menu_rows
        log ""
        banner
        log "  ${C_DIM}profile '${NAME}'${C_RESET}    dry-run: ${DRY}"
        local i n=0; local -a idmap=()
        for i in "${!MR_TYPE[@]}"; do
            if [ "${MR_TYPE[$i]}" = head ]; then
                log ""; log "  ${C_BOLD}${MR_LABEL[$i]}${C_RESET}"
            else
                n=$((n + 1)); idmap[$n]="${MR_ID[$i]}"
                printf '   %b %2d  %-40s %s%s%s\n' "$(_done_glyph "${MR_DONE[$i]}")" "$n" "${MR_LABEL[$i]}" "$C_DIM" "${MR_VAL[$i]}" "$C_RESET"
            fi
        done
        rule
        log "  Select number(s) e.g. ${C_BOLD}1 3 5${C_RESET} · ${C_BOLD}all${C_RESET} (phases) · ${C_BOLD}d${C_RESET} dry-run · ${C_BOLD}q${C_RESET} quit"
        local line; read -r -p "  > " line || line="q"
        [ -n "$line" ] || continue
        local tok quit=0
        for tok in $(printf '%s' "$line" | tr ',' ' '); do
            case "$tok" in
                all) local k; for k in occas ident hosts tls runtime; do dispatch_row "$k"; done ;;
                d)   [ "$DRY" = "on" ] && DRY="off" || DRY="on"; log "  dry-run: ${DRY}" ;;
                q)   quit=1 ;;
                *[!0-9]*) warn "unknown choice: ${tok} (use a number, or all/d/q)" ;;
                *)   [ -n "${idmap[$tok]:-}" ] && dispatch_row "${idmap[$tok]}" || warn "no row ${tok}" ;;
            esac
            { [ "$PROFILE_GONE" = 1 ] || [ "$REPO_GONE" = 1 ]; } && break
        done
        [ "$quit" = 1 ] && break
        { [ "$PROFILE_GONE" = 1 ] || [ "$REPO_GONE" = 1 ]; } && break
    done
    log ""
    if [ "$REPO_GONE" = 1 ]; then
        log "  ${C_DIM}Local BLADE clone at ${SCRIPT_DIR} is being removed. GitHub remote is untouched.${C_RESET}"
    elif [ "$PROFILE_GONE" = 1 ]; then
        log "  ${C_DIM}Profile '${NAME}' removed. Re-run ./install.sh to pick or create another.${C_RESET}"
    else
        log "  ${C_DIM}Next: ./build.sh   then   ./deploy.sh ${NAME}${C_RESET}"
    fi
    return 0
}

# Entry: name a new profile, load it, then drive the TUI (or typed fallback).
dashboard() {
    if [ -z "$NAME" ]; then
        log ""
        while [ -z "$NAME" ]; do ask NAME "Name this profile" ""; done
    fi
    set_paths
    mkdir -p "$PROFILE_DIR"
    load_profile
    local use_tui=0
    if [ -n "${BLADE_FORCE_TUI:-}" ]; then use_tui=1
    elif [ -z "${BLADE_NO_TUI:-}" ] && [ -t 0 ] && [ -t 1 ]; then use_tui=1; fi
    if [ "$use_tui" = 1 ]; then dashboard_tui; else dashboard_menu; fi
    return 0
}

# True if something is already listening on 127.0.0.1:<port> (NM bound 0.0.0.0).
nm_listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&-; return 0; }; return 1; }

# Stop the Node Manager listening on NM_PORT. Finds the PID that owns the port
# (Linux: ss) and kills it only after confirming its cmdline is Node Manager for
# our nmdomain — never a blind pkill. After 'n' re-reads nodemanager.domains.
stop_nm() {
    local port="${NM_PORT:-$(read_prop "$OCCAS_CONF" nm.listen.port)}"; port="${port:-5556}"
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] stop Node Manager listening on :${port}${C_RESET}"; return 0; fi
    nm_listening "$port" || { ok "Node Manager not running on :${port}."; return 0; }
    # When the boot service owns THIS nmdomain, go through systemd. Reading the
    # PID out of `ss` needs root, so an unprivileged run would otherwise fail
    # here with "couldn't resolve the PID" and leave the old NM running --
    # which then serves a stale nodemanager.domains.
    local _nmhome="${DOMAINS_DIR}/${NM_DOMAIN}"
    # Kill as the OWNER of the running NM's domain, not install.user — a signal
    # sent as oracle to a legacy login-user-owned JVM is EPERM, and proc_alive
    # would then watch a process nothing actually signaled.
    local IU_USER; IU_USER="$(iu_owner_user "$_nmhome")"
    if command -v systemctl >/dev/null 2>&1 \
       && grep -qsF -- "$_nmhome" /etc/systemd/system/nodemanager.service; then
        if sudo -n systemctl stop nodemanager.service 2>/dev/null; then
            ok "stopped Node Manager via nodemanager.service."
            return 0
        fi
    fi
    command -v ss >/dev/null 2>&1 || { warn "need 'ss' to find the Node Manager PID — stop it manually."; return 1; }
    local pids p killed=0 cmd
    pids="$(ss -ltnpH "( sport = :${port} )" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)"
    # An unprivileged ss cannot see another user's PID — root's can.
    [ -z "$pids" ] && command -v sudo >/dev/null 2>&1 \
        && pids="$(sudo ss -ltnpH "( sport = :${port} )" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)"
    [ -n "$pids" ] || { warn "couldn't resolve the PID on :${port} (try as root) — stop it manually."; return 1; }
    local killpids=""
    for p in $pids; do
        cmd="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)"
        case "$cmd" in
            *NodeManager*|*nodemanager*|*"${NM_DOMAIN}"*) as_install_user kill "$p" 2>/dev/null && { killed=1; killpids="${killpids} ${p}"; } ;;
            *) warn "pid ${p} on :${port} doesn't look like Node Manager — left alone." ;;
        esac
    done
    [ "$killed" = 1 ] || return 1
    # Wait for the JVM to actually exit and free the port (a bare SIGTERM lingers
    # for seconds). Synchronous stop, so a following restart sees a free port.
    local i=0
    while nm_listening "$port" && [ "$i" -lt 15 ]; do sleep 1; i=$((i + 1)); done
    if nm_listening "$port"; then
        warn "Node Manager still on :${port} after ${i}s — sending SIGKILL."
        for p in $killpids; do as_install_user kill -9 "$p" 2>/dev/null || true; done
        i=0; while nm_listening "$port" && [ "$i" -lt 5 ]; do sleep 1; i=$((i + 1)); done
    fi
    if nm_listening "$port"; then warn "could not free :${port}."; return 1; fi
    ok "stopped Node Manager (${killpids# }); :${port} free."
    return 0
}

# Remove a systemd unit that drives the domain we're deleting — and ONLY then.
# install.sh's install actions ('e'/'w') write these units pointed at our own
# domains, but the same conventional name (nodemanager.service) may already point
# at a completely unrelated WebLogic install on a given host. We therefore touch
# the unit only if its file actually references ${domhome}; otherwise we leave it
# strictly alone. Stop, disable, delete, reload. Uses sudo when not root; never
# fails the caller. Matching by the domain path also works after the directory
# itself is gone (we're matching the unit's text, not the live dir).
remove_domain_systemd_unit() {
    local domhome="$1" unit="$2"
    [ -n "$domhome" ] || return 0
    command -v systemctl >/dev/null 2>&1 || return 0
    local unitfile="/etc/systemd/system/${unit}"
    [ -f "$unitfile" ] || return 0
    if ! grep -qF -- "$domhome" "$unitfile" 2>/dev/null; then
        log "${C_DIM}  left ${unit} alone — it doesn't point at ${domhome}.${C_RESET}"
        return 0
    fi
    local sudo=""
    if [ "$(id -u)" != 0 ] && command -v sudo >/dev/null 2>&1; then sudo="sudo"; fi
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ${sudo:+${sudo} }systemctl stop/disable ${unit}; rm -f ${unitfile}; daemon-reload${C_RESET}"
        return 0
    fi
    $sudo systemctl stop "$unit"    >/dev/null 2>&1 || true
    $sudo systemctl disable "$unit" >/dev/null 2>&1 || true
    $sudo rm -f "$unitfile" && ok "removed systemd unit ${unitfile}."
    $sudo systemctl daemon-reload   >/dev/null 2>&1 || true
}

# The engine-side counterpart of do_provision_engines: undo what it put there.
#
# Two reasons this is not optional. An enabled unit pointing at a deleted domain
# is a boot failure planted for whenever that host next reboots. And the engine's
# COPY of the domain would otherwise survive a teardown, so the next provisioning
# rsync (which has no --delete, deliberately) would merge new files into stale
# ones — the kind of half-state that produces symptoms nobody can trace.
#
# Reachability problems are warnings, not failures: the admin-side teardown must
# still proceed.
remove_engine_systemd_units() {
    local domhome="$1" nmhome="$2" sshu="${SSH_USER:-$(id -un)}" i tgt unit
    [ "${#H_NAME[@]}" -gt 1 ] || return 0
    for i in "${!H_NAME[@]}"; do
        [ "${H_ROLE[$i]}" = "engine" ] || continue
        tgt="${sshu}@${H_ADDR[$i]}"
        if [ "$DRY" = "on" ]; then
            log "${C_DIM}  [dry-run] ${tgt}: stop/disable/remove weblogic-engine.service + nodemanager.service (if they point here); rm -rf ${domhome}${C_RESET}"
            continue
        fi
        if ! ssh -o BatchMode=yes -o ConnectTimeout=8 "$tgt" true 2>/dev/null; then
            warn "${H_NAME[$i]}: unreachable — its boot services were NOT removed. Clean up by hand or they will fail on next boot."
            continue
        fi
        # Stop what is RUNNING before deleting what it is running from. A live
        # server JVM holds its log open and recreates servers/<name>/logs under
        # the tree as fast as rm removes it, so the delete "succeeds" and the
        # directory is still there afterwards. Units first (the clean path),
        # then any JVM still rooted in this domain.
        #
        # The pgrep pattern is bracketed — 'weblogic[.]Name=' does not match the
        # literal text of this very ssh command, which is how a plain pkill -f
        # ends up killing the shell that issued it.
        ssh -o BatchMode=yes "$tgt" "
            for u in weblogic-engine.service nodemanager.service; do
                systemctl list-unit-files \"\$u\" >/dev/null 2>&1 && sudo systemctl stop \"\$u\" >/dev/null 2>&1
            done
            for p in \$(pgrep -f 'weblogic[.]Name=' 2>/dev/null); do
                if tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null | grep -qF -- '${domhome}'; then
                    kill \$p 2>/dev/null && echo \"  stopped pid \$p on \$(hostname)\"
                fi
            done
            for i in 1 2 3 4 5 6 7 8 9 10; do
                pgrep -f 'weblogic[.]Name=' >/dev/null 2>&1 || break
                sleep 2
            done
            for p in \$(pgrep -f 'weblogic[.]Name=' 2>/dev/null); do
                tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null | grep -qF -- '${domhome}' && kill -9 \$p 2>/dev/null
            done
            true
        " 2>/dev/null || warn "${H_NAME[$i]}: could not stop its servers — the domain delete may not stick."

        for unit in weblogic-engine.service nodemanager.service; do
            local guard="$domhome"
            [ "$unit" = nodemanager.service ] && guard="$nmhome"
            ssh -o BatchMode=yes "$tgt" "
                f=/etc/systemd/system/${unit}
                [ -f \"\$f\" ] || exit 0
                grep -qF -- '${guard}' \"\$f\" || { echo \"  left ${unit} alone on \$(hostname) — it doesn't point at ${guard}.\"; exit 0; }
                sudo systemctl stop '${unit}' >/dev/null 2>&1
                sudo systemctl disable '${unit}' >/dev/null 2>&1
                sudo rm -f \"\$f\" && echo \"  removed ${unit} on \$(hostname).\"
                sudo systemctl daemon-reload >/dev/null 2>&1
            " 2>/dev/null || warn "${H_NAME[$i]}: could not remove ${unit}."
        done
        # The engine's copy of the app domain. nmdomain is left alone — it is
        # this host's Node Manager and is not owned by the domain being removed.
        if [ -n "$domhome" ]; then
            ssh -o BatchMode=yes "$tgt" "
                [ -d '${domhome}' ] || exit 0
                rm -rf '${domhome}' && echo \"  removed ${domhome} on \$(hostname).\"
            " 2>/dev/null || warn "${H_NAME[$i]}: could not remove ${domhome}."
        fi
    done
}

# Who ACTUALLY owns a path, as "user:group" — for the systemd User=/Group= lines.
# The configured install.user is what we'd LIKE to own the install; it isn't
# necessarily what does. An install done as an ordinary login user (ashburn's
# OCCAS is owned by 'opc') would otherwise get units that cannot read their own
# domain and fail at boot with a permission error far from the cause. Falls back
# to the configured pair when the path doesn't exist yet, which is the dry-run
# and pre-install case. host="" means look locally.
owner_of_path() {
    local path="$1" host="${2:-}" out=""
    if [ -n "$host" ]; then
        out="$(ssh -o BatchMode=yes -o ConnectTimeout=8 "$host" \
                   "stat -L -c '%U:%G' '${path}' 2>/dev/null" 2>/dev/null)"
    else
        out="$(xfer_owner_of "$path")"
    fi
    case "$out" in
        ?*:?*) printf '%s' "$out" ;;
        *)     printf '%s:%s' "${INSTALL_USER:-oracle}" "${INV_GRP:-oinstall}" ;;
    esac
}

# User:Group for a systemd BOOT unit — owner_of_path, but NEVER root. A WebLogic
# Node Manager or managed server must not run as root: root-owning a domain is a
# defect (a step ran as root instead of the install user), and a unit that bakes
# User=root cements it — Node Manager launches every server AS ITSELF, so one
# root NM turns the whole tier root (and an oracle-run stop can no longer signal
# them). Fall back to the install user and flag the ownership to be chown'd,
# rather than perpetuating root. The warn goes to stderr so it never lands in the
# user:grp this is captured for. owner_of_path stays as-is for OPERATIONAL
# retargeting (iu_owner_user) — only the unit files reject root.
unit_owner_of_path() {
    local og; og="$(owner_of_path "$1" "${2:-}")"
    case "$og" in
        root:*|root)
            warn "${1} is owned by root — WebLogic must not run as root. Rendering the unit as ${INSTALL_USER:-oracle}:${INV_GRP:-oinstall}; chown the domain to that owner so it can read it at boot." >&2
            printf '%s:%s' "${INSTALL_USER:-oracle}" "${INV_GRP:-oinstall}" ;;
        *) printf '%s' "$og" ;;
    esac
}

# --- install-user identity ---------------------------------------------------
# STEP 1 ('u'/'m') hands the whole install to install.user, so every step that
# writes into it must RUN as that user — while the invoker stays whoever the
# cloud image logs in (opc, ec2-user, …). These wrap the difference: a no-op
# when the invoker IS the install user, or the user doesn't exist yet (the
# pre-'u' case), or off Linux; else sudo. -H matters: the OUI and WLST both
# scribble under $HOME, and without it sudo keeps the INVOKER's HOME — which
# the install user cannot even traverse (home dirs are 0700 on OL8+).

iu_name() {
    # IU_USER (dynamically scoped: `local IU_USER=...` in a caller re-targets
    # every iu_*/as_install_user beneath it) overrides for operations on an
    # EXISTING tree — set it from owner_of_path so patching a legacy install
    # owned by the login user runs as THAT user, not install.user.
    local u="${IU_USER:-}"
    [ -n "$u" ] || u="${INSTALL_USER:-}"
    [ -n "$u" ] || u="$(read_prop "$OCCAS_CONF" install.user)"
    printf '%s' "${u:-oracle}"
}

# True when commands must switch identity to reach the install.
iu_switching() {
    [ "$(uname -s)" = "Linux" ] || return 1
    local u; u="$(iu_name)"
    [ "$(id -un)" != "$u" ] || return 1
    id "$u" >/dev/null 2>&1 || return 1
    command -v sudo >/dev/null 2>&1 || return 1
    return 0
}

as_install_user() {
    if iu_switching; then sudo -H -u "$(iu_name)" "$@"; else "$@"; fi
}

# Write stdin to a path the install user owns. The mode is applied via umask
# first, so a secret never exists world-readable even transiently.
iu_write() {
    local path="$1" mode="${2:-644}" um="022"
    case "$mode" in 6??|7??) um="077" ;; esac
    as_install_user sh -c "umask ${um}; cat > '${path}' && chmod ${mode} '${path}'"
}

# set_conf_prop against a file the install user owns: stage the edit on a copy,
# write it back through the identity switch (plain set_conf_prop otherwise).
iu_set_conf_prop() {
    local file="$1" key="$2" val="$3"
    if ! iu_switching; then set_conf_prop "$file" "$key" "$val"; return; fi
    local tmp rc=0; tmp="$(mktemp)"
    # Empty-stage only a file that genuinely doesn't exist. A read failure on
    # an EXISTING file must abort — writing the stage back would silently
    # truncate every other key (think nodemanager.domains losing enrollments).
    if as_install_user test -f "$file"; then
        as_install_user cat "$file" > "$tmp" \
            || { rm -f "$tmp"; warn "cannot read ${file} as $(iu_name) — not rewriting it."; return 1; }
    else
        : > "$tmp"
    fi
    set_conf_prop "$tmp" "$key" "$val"
    iu_write "$file" 644 < "$tmp" || rc=$?
    rm -f "$tmp"; return $rc
}

# Hand a /tmp workdir staged by the invoker to the install user. The chown
# needs root; the caller's cleanup must then also run as_install_user rm -rf.
iu_adopt_dir() {
    iu_switching || return 0
    sudo chown -R "$(iu_name)" "$1"
}

# The owner (user only) of an existing tree — for `local IU_USER` retargeting.
# Operations on an EXISTING tree run as whoever owns it, not install.user: a
# legacy install owned by the login user keeps working after the conf gains
# install.user=oracle. Falls back to the configured pair for missing paths.
iu_owner_user() {
    local o; o="$(owner_of_path "$1")"; printf '%s' "${o%%:*}"
}

# Run a WLST script as the install user: generate a runner in the caller's
# staged workdir (sudo can't cross a function boundary), hand the dir over,
# execute. The caller still owns cleanup (as_install_user rm -rf "$work").
# A fresh bash has no nounset, so Oracle's setWLSEnv.sh (which references
# unbound vars) sources cleanly. Args: workdir pyfile mwhome java_home.
iu_wlst_run() {
    local work="$1" py="$2" mw="$3" jh="$4" wlp="${5:-}"   # $5 = optional WLST_PROPERTIES
    local setwls="${mw}/wlserver/server/bin/setWLSEnv.sh"
    # A stale java.home must fall back to the ambient environment, exactly as
    # the pre-runner code did — exporting a dead JAVA_HOME aborts WLST with an
    # opaque rc far from the actual cause.
    if [ -n "$jh" ] && [ ! -d "$jh" ]; then
        warn "java.home ${jh} does not exist — using the environment's JAVA_HOME."
        jh=""
    fi
    # $5 goes on the java command line, NOT via the WLST_PROPERTIES env var.
    # weblogic.WLST applies WLST_PROPERTIES late (after the security subsystem
    # has initialized): a trust store set that way is still honored (read lazily
    # at connect()), but the hostname verifier is already captured, so a late
    # ignoreHostnameVerification is silently missed and the default verifier
    # fires. Real -D startup properties are set before any WLS class loads, so
    # both trust AND the hostname-ignore take effect.
    cat > "${work}/run.sh" <<EOF
#!/bin/bash
cd '${work}'
${jh:+export JAVA_HOME='${jh}'; PATH='${jh}/bin':"\$PATH"}
# JAVA_VENDOR must accompany JAVA_HOME. setWLSEnv.sh sources commBaseEnv.sh, which
# resets JAVA_HOME to the OUI install record (the BUILD JDK) whenever JAVA_VENDOR is
# empty. Unset, the domain-creation Wizard then bakes that build-JDK path into the
# new domain's setNMJavaHome.sh / setDomainEnv.sh — which fails on every runtime-only
# engine box. Setting it keeps WLST (and the Wizard) on our JAVA_HOME, so the domain
# records the runtime JDK link that provisioning ships to engines.
export JAVA_VENDOR=Oracle
export MW_HOME='${mw}' BEA_HOME='${mw}'
. '${setwls}' >/dev/null
exec java ${wlp} weblogic.WLST '${py}'
EOF
    chmod 700 "${work}/run.sh"
    iu_adopt_dir "$work" || { warn "could not hand ${work} to $(iu_name)."; return 1; }
    as_install_user bash "${work}/run.sh"
}

# Is this pid alive? kill -0 lies across users (EPERM reads as "gone"), so
# prefer /proc; fall back to kill -0 where there is no /proc (macOS).
proc_alive() {
    if [ -d /proc ]; then [ -d "/proc/$1" ]; else kill -0 "$1" 2>/dev/null; fi
}

# --- systemd boot services -------------------------------------------------
# install.sh starts NM and the AdminServer interactively (RUN n/s). These install
# the equivalent systemd units so both come back up on reboot. Both unit files
# are GENERATED here from the live domain paths (the misc/*.service files are
# only hand-edit references) — so the conventional names always point at exactly
# the domain install.sh manages, which is what the guarded teardown above keys on.
#
# startNodeManager.sh / startWebLogic.sh each run their JVM in the foreground, so
# Type=simple + Restart=always is the right shape (matches misc/*.service). Both
# scripts source setDomainEnv.sh -> setUserOverrides.sh, so the server.mem.args
# tuning applies under systemd exactly as under RUN n/s.

# Emit a systemd unit to stdout. after = extra ordering deps (may be empty).
# The JAVA_HOME to bake into systemd units and remote provisioning: prefer the
# <java.dir>/current link whenever it resolves to the same JDK as java.home. A
# unit pinning a versioned dir (Environment=JAVA_HOME=.../jdk-21.0.11) dies the
# day a JDK upgrade removes that dir; the link survives every flip. This never
# CREATES or repoints the link — that is the JDK phase's / patch_jdk's
# (interactive) job, and a silent repoint here could fight a deliberate pin —
# it only chooses the stable name when both names already mean the same JDK.
java_home_stable() {
    local jh="${JAVA_HOME_VAL:-}" link="${JAVA_BASE:-/opt/oracle/java}/current"
    [ -n "$jh" ] || return 0
    if [ "$jh" != "$link" ]; then
        local lreal; lreal="$(readlink -f "$link" 2>/dev/null || true)"
        [ -n "$lreal" ] && [ "$lreal" = "$(readlink -f "$jh" 2>/dev/null)" ] && jh="$link"
    fi
    # Self-heal a DEAD pin: if jh points at a JDK that no longer exists (a patch
    # removed jdk-21.0.11) but the stable link IS a valid JDK, use the link. This
    # only fires when the pin is broken — a deliberate, still-valid pin (java is
    # runnable there) is left exactly as chosen, so it never fights an intended pin.
    if [ ! -x "${jh}/bin/java" ] && [ -x "${link}/bin/java" ]; then jh="$link"; fi
    printf '%s' "$jh"
}

# Relabel paths for SELinux so systemd can exec the boot scripts (and the server
# can exec java + its libs). A freshly mkfs'd block volume — e.g. /opt/oracle on
# its own OCI volume, reformatted — comes up entirely `unlabeled_t`, and systemd
# (init_t) REFUSES to exec an unlabeled_t ExecStart: the unit dies with
# `status=203/EXEC` before the script runs a line. (An interactive start works —
# an unconfined user shell may exec unlabeled_t — which is why NM comes up by hand
# but its boot unit does not.) restorecon applies the policy label (bin/ -> bin_t,
# which is execable). Best-effort and guarded: a no-op off SELinux, when
# restorecon is absent, or when the tree is already labeled — so a normal
# (non-reformatted) install pays nothing and a re-run does not re-walk gigabytes.
selinux_relabel() {
    command -v restorecon >/dev/null 2>&1 || return 0
    if command -v selinuxenabled >/dev/null 2>&1; then selinuxenabled || return 0; fi
    local sudo=""; [ "$(id -u)" != 0 ] && command -v sudo >/dev/null 2>&1 && sudo="sudo"
    local p rp cur
    for p in "$@"; do
        [ -n "$p" ] || continue
        rp="$(readlink -f "$p" 2>/dev/null || printf '%s' "$p")"
        [ -e "$rp" ] || continue
        # Only relabel a tree that is actually unlabeled (the fresh-volume signal);
        # skip an already-labeled one so this stays cheap on every other install.
        cur="$($sudo ls -dZ "$rp" 2>/dev/null | awk '{print $1}')"
        case "$cur" in *:unlabeled_t:*) ;; *) continue ;; esac
        if [ "$DRY" = "on" ]; then
            log "${C_DIM}  [dry-run] restorecon -R ${rp}  (was unlabeled — reformatted volume)${C_RESET}"
            continue
        fi
        info "SELinux: relabeling ${rp} (unlabeled — e.g. a reformatted block volume) …"
        $sudo restorecon -R "$rp" 2>/dev/null \
            && ok "SELinux: relabeled ${rp}" \
            || warn "SELinux: restorecon on ${rp} failed — run 'sudo restorecon -Rv ${rp}' if a boot unit fails with 203/EXEC."
    done
    return 0
}

# Remote twin: relabel on an engine host over ssh (its /opt/oracle may be a fresh
# volume too, rsync'd into). Best-effort; needs passwordless sudo there.
selinux_relabel_remote() {
    local host="$1"; shift
    [ "$#" -gt 0 ] || return 0
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ${host}: restorecon -R $* (if unlabeled)${C_RESET}"; return 0
    fi
    ssh -o BatchMode=yes "$host" "command -v restorecon >/dev/null 2>&1 || exit 0
        if command -v selinuxenabled >/dev/null 2>&1; then selinuxenabled || exit 0; fi
        for p in $*; do
            [ -e \"\$p\" ] || continue
            case \"\$(sudo ls -dZ \"\$p\" 2>/dev/null | awk '{print \$1}')\" in *:unlabeled_t:*) sudo restorecon -R \"\$p\" 2>/dev/null || true ;; esac
        done" 2>/dev/null \
        && ok "${host}: SELinux relabel checked." \
        || warn "${host}: SELinux relabel skipped/failed — 'sudo restorecon -Rv /opt/oracle' there if a boot unit 203/EXECs."
    return 0
}

render_systemd_unit() {
    local desc="$1" workdir="$2" start="$3" stop="$4" user="$5" group="$6" after="$7"
    local jh; jh="$(java_home_stable)"
    printf '%s\n' "[Unit]"
    printf 'Description=%s\n' "$desc"
    printf 'After=network-online.target%s\n' "${after:+ ${after}}"
    printf 'Wants=network-online.target\n'
    printf '\n[Service]\n'
    printf 'Type=simple\n'
    [ -n "$jh" ] && printf 'Environment=JAVA_HOME=%s\n' "$jh"
    printf 'WorkingDirectory=%s\n' "$workdir"
    printf 'ExecStart=%s\n' "$start"
    printf 'ExecStop=%s\n'  "$stop"
    printf 'User=%s\n'  "$user"
    printf 'Group=%s\n' "$group"
    printf 'KillMode=process\n'
    printf 'LimitNOFILE=65535\n'
    printf 'Restart=always\n'
    printf '\n[Install]\n'
    printf 'WantedBy=multi-user.target\n'
}

# Write <unit> to /etc/systemd/system from stdin, then daemon-reload + enable so
# it survives reboot (NEVER forget the reload — see the systemd-daemon-reload
# rule). DRY prints the rendered unit instead of writing. sudo when not root.
install_systemd_unit() {
    local unit="$1" text="$2"
    command -v systemctl >/dev/null 2>&1 || { warn "no systemctl here — cannot install ${unit}."; return 1; }
    local unitfile="/etc/systemd/system/${unit}"
    local sudo=""
    if [ "$(id -u)" != 0 ] && command -v sudo >/dev/null 2>&1; then sudo="sudo"; fi
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] write ${unitfile}:${C_RESET}"
        printf '%s\n' "$text" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] ${sudo:+${sudo} }systemctl daemon-reload; systemctl enable ${unit}${C_RESET}"
        return 0
    fi
    printf '%s\n' "$text" | $sudo tee "$unitfile" >/dev/null \
        || { warn "could not write ${unitfile} (need sudo?)."; return 1; }
    $sudo chmod 644 "$unitfile" 2>/dev/null || true
    $sudo systemctl daemon-reload || { warn "daemon-reload failed for ${unit}."; return 1; }
    $sudo systemctl enable "$unit" >/dev/null 2>&1 \
        && ok "installed + enabled ${unitfile}." \
        || ok "installed ${unitfile} (enable it with: sudo systemctl enable ${unit})."
}

# Same as install_systemd_unit, but on another host over ssh. Engine boxes need
# the identical units; only the delivery differs. The unit text goes over stdin,
# never on the command line — an ssh command line is visible in 'ps' on the far
# side. Requires passwordless sudo there, same as the rest of the engine work.
install_systemd_unit_remote() {
    local host="$1" unit="$2" text="$3"
    local unitfile="/etc/systemd/system/${unit}"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ${host}: write ${unitfile}:${C_RESET}"
        printf '%s\n' "$text" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] ${host}: systemctl daemon-reload; systemctl enable ${unit}${C_RESET}"
        return 0
    fi
    if ! ssh -o BatchMode=yes -o ConnectTimeout=8 "$host" \
             "command -v systemctl >/dev/null 2>&1" 2>/dev/null; then
        warn "${host}: no systemctl — cannot install ${unit}."; return 1
    fi
    printf '%s\n' "$text" \
        | ssh -o BatchMode=yes "$host" "sudo tee '${unitfile}' >/dev/null && sudo chmod 644 '${unitfile}'" \
        || { warn "${host}: could not write ${unitfile} (passwordless sudo?)."; return 1; }
    ssh -o BatchMode=yes "$host" "sudo systemctl daemon-reload" \
        || { warn "${host}: daemon-reload failed for ${unit}."; return 1; }
    ssh -o BatchMode=yes "$host" "sudo systemctl enable '${unit}' >/dev/null 2>&1" \
        && ok "${host}: installed + enabled ${unitfile}." \
        || ok "${host}: installed ${unitfile} (enable it with: sudo systemctl enable ${unit})."
}

# Remote twin of write_nm_envfile. The password goes over ssh STDIN under a
# tight umask — never as an argument, which would show up in 'ps' on the engine.
# Same shape do_provision_engines already uses for engine boot.properties.
write_nm_envfile_remote() {
    local host="$1" envfile="$2" user="$3" pw="$4"
    [ -n "$pw" ] || warn "${host}: no admin password in the profile — the boot service will fail to nmConnect until NM_PASSWORD is set in ${envfile}."
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ${host}: write ${envfile} (NM_PASSWORD=****, 0600, owner ${user})${C_RESET}"
        return 0
    fi
    { printf 'NM_PASSWORD=%s\n' "$pw"; printf 'WLST_PROPERTIES=%s\n' "$(nm_wlst_props)"; } \
        | ssh -o BatchMode=yes "$host" \
              "umask 077 && sudo tee '${envfile}' >/dev/null && sudo chown '${user}' '${envfile}' && sudo chmod 600 '${envfile}'" \
        || { warn "${host}: could not write ${envfile}."; return 1; }
    ok "${host}: wrote ${envfile} (NM password for boot, 0600)."
}

# Emit a server-via-Node-Manager unit to stdout — the AdminServer on the admin
# box, or one engine on an engine box. Unlike the NM unit this is Type=oneshot +
# RemainAfterExit: misc/start-admin-nm.sh does nmStart and exits, and the server
# JVM is a child of Node Manager (not of this unit), so there's no foreground
# process to babysit and no Restart=. ExecStop is an OS-level kill
# (misc/stop-admin-os.sh) because pure-Java NM can't reliably nmKill.
# NM_PASSWORD comes from a 0600 EnvironmentFile, never the unit text.
#
# server   which server to start: AdminServer, or engine1/engine2/...
# adminurl t3://<admin>:<port> — REQUIRED for a managed server (it has to be
#          told where the AdminServer is), empty for the AdminServer itself.
render_admin_nm_unit() {
    local dom="$1" domhome="$2" scriptdir="$3" user="$4" group="$5" envfile="$6"
    local server="${7:-AdminServer}" adminurl="${8:-}"
    local nmport="${NM_PORT:-5556}" nmtype="${NM_TYPE:-ssl}" nmuser="${ADMIN_USER:-weblogic}"
    local jh; jh="$(java_home_stable)"
    printf '%s\n' "[Unit]"
    printf 'Description=WebLogic %s via Node Manager (BLADE %s)\n' "$server" "$dom"
    printf 'After=network-online.target nodemanager.service\n'
    # Wants, not Requires: start-admin-nm.sh waits for the NM listener itself
    # (NM_WAIT/ADMIN_WAIT loops) before nmConnect, so it does not need systemd to
    # guarantee NM. A hard Requires instead FAILS this unit with result
    # 'dependency' whenever NM is up but not under systemd — e.g. right after the
    # interactive "Create & start Node Manager" step, where systemd cannot start
    # its own nodemanager.service because the running NM already holds :5556. The
    # server then actually starts (the ExecStart reaches the running NM) while
    # systemd reports failure. After= still orders us behind NM on a clean reboot.
    printf 'Wants=nodemanager.service\n'
    printf 'Wants=network-online.target\n'
    printf '\n[Service]\n'
    printf 'Type=oneshot\n'
    printf 'RemainAfterExit=yes\n'
    [ -n "$jh" ] && printf 'Environment=JAVA_HOME=%s\n' "$jh"
    printf 'Environment=MW_HOME=%s\n' "$MWHOME"
    printf 'Environment=DOMAIN_NAME=%s\n' "$dom"
    printf 'Environment=DOMAIN_HOME=%s\n' "$domhome"
    printf 'Environment=ADMIN_SERVER=%s\n' "$server"
    printf 'Environment=NM_HOST=localhost\n'
    printf 'Environment=NM_PORT=%s\n' "$nmport"
    printf 'Environment=NM_TYPE=%s\n' "$nmtype"
    printf 'Environment=NM_USER=%s\n' "$nmuser"
    # A managed server must reach the AdminServer to boot. start-admin-nm.sh
    # waits for it (ADMIN_WAIT_SECS) rather than racing the admin box's own boot.
    [ -n "$adminurl" ] && printf 'Environment=NM_ADMINURL=%s\n' "$adminurl"
    printf 'EnvironmentFile=%s\n' "$envfile"
    printf 'ExecStart=%s/start-admin-nm.sh\n' "$scriptdir"
    printf 'ExecStop=%s/stop-admin-os.sh\n'  "$scriptdir"
    printf 'User=%s\n'  "$user"
    printf 'Group=%s\n' "$group"
    printf 'TimeoutStartSec=600\n'
    printf '\n[Install]\n'
    printf 'WantedBy=multi-user.target\n'
}

# The boot units must NOT point into the repo checkout. Engine hosts have no
# clone at all — the rsync carries the DOMAIN, not the repo — and install.sh can
# delete the clone itself (RUN: repo). Either way an ExecStart under
# ${SCRIPT_DIR} is a boot service that breaks later, for a reason nobody will
# connect back to this. Stage the two helpers inside the domain instead, where
# they travel with it to every host.
BOOT_SCRIPT_SUBDIR="bin/blade"
stage_boot_scripts() {
    # Split, not one `local` line: bash creates every name in a `local` first and
    # assigns after, so referencing an earlier one in the same statement can
    # expand while still unbound -- fatal under `set -u`.
    local domhome="$1"
    local dest="${domhome}/${BOOT_SCRIPT_SUBDIR}"
    local s
    [ -n "$domhome" ] || { warn "stage_boot_scripts: no domain home given."; return 1; }
    local IU_USER; IU_USER="$(iu_owner_user "$domhome")"   # stage as the domain's owner
    for s in start-admin-nm.sh stop-admin-os.sh; do
        [ -f "${SCRIPT_DIR}/misc/${s}" ] || { warn "missing ${SCRIPT_DIR}/misc/${s}."; return 1; }
    done
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] copy misc/{start-admin-nm,stop-admin-os}.sh -> ${dest}/${C_RESET}"
        return 0
    fi
    as_install_user mkdir -p "$dest" || { warn "could not create ${dest}."; return 1; }
    # Through the identity switch: the domain is the install user's, and they
    # cannot read the repo checkout (invoker's $HOME) — so pipe, don't cp.
    for s in start-admin-nm.sh stop-admin-os.sh; do
        iu_write "${dest}/${s}" 755 < "${SCRIPT_DIR}/misc/${s}" || { warn "could not stage ${s}."; return 1; }
    done
    ok "staged boot scripts in ${dest}."
}

# Write the NM password to a 0600 EnvironmentFile that misc/start-admin-nm.sh
# reads via systemd at boot (nmConnect creds = the admin creds). Kept out of the
# world-readable unit. Lives inside the domain so a domain teardown removes it.
write_nm_envfile() {
    local envfile="$1" user="$2" pw="$3"
    [ -n "$pw" ] || { warn "no admin password in the profile — weblogic.service will fail to nmConnect until NM_PASSWORD is set in ${envfile}."; }
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] write ${envfile} (NM_PASSWORD=****, 0600, owner ${user})${C_RESET}"
        return 0
    fi
    local sudo=""
    if [ "$(id -u)" != 0 ] && [ "$(id -un)" != "$user" ] && command -v sudo >/dev/null 2>&1; then sudo="sudo"; fi
    { printf 'NM_PASSWORD=%s\n' "$pw"; printf 'WLST_PROPERTIES=%s\n' "$(nm_wlst_props)"; } \
        | ( umask 077; $sudo tee "$envfile" >/dev/null ) \
        || { warn "could not write ${envfile}."; return 1; }
    $sudo chown "$user" "$envfile" 2>/dev/null || true
    $sudo chmod 600 "$envfile" 2>/dev/null || true
    ok "wrote ${envfile} (NM password for boot, 0600)."
}

# Re-write the boot NM env (.blade-nm.env) on the admin box and every engine host
# so a rotated nm.keystore.passphrase (or admin password) reaches the boot path,
# instead of stranding a node's NEXT start on a stale trust passphrase — the
# failure mode where a systemd engine start dies with "trustAnchors must be
# non-empty" because WLST_PROPERTIES names a passphrase that no longer opens
# nm-trust.p12. Touches only hosts whose env already exists (a no-op before the
# boot services are installed), so it is safe to call every time. MUST run AFTER
# the NM keystores are (re)placed, so the passphrase it records matches the
# keystore it must open. Running servers keep their connection; the new value is
# read at their next start.
refresh_boot_envs() {
    [ -n "$DOMAIN" ] || return 0
    local domhome="${DOMAINS_DIR}/${DOMAIN}" envfile="${DOMAINS_DIR}/${DOMAIN}/.blade-nm.env"
    local user grp; IFS=: read -r user grp <<< "$(unit_owner_of_path "$domhome")"
    local pw="${BLADE_WLS_PASSWORD:-}"
    [ -z "$pw" ] && [ -f "$WLS_SECRET" ] && pw="$(read_prop "$WLS_SECRET" admin.password)"
    local sshu="${SSH_USER:-$(id -un)}" i name addr tgt did=0
    # admin (this host) — only if its boot env already exists
    if [ -f "$envfile" ] || [ "$DRY" = "on" ]; then
        write_nm_envfile "$envfile" "$user" "$pw" && did=1
    fi
    # engine hosts (index >= 1), only where the env already exists
    for i in "${!H_NAME[@]}"; do
        [ "$i" -eq 0 ] && continue
        [ "${H_ROLE[$i]}" = "engine" ] || continue
        name="${H_NAME[$i]}"; addr="${H_ADDR[$i]}"; tgt="${sshu}@${addr}"
        if [ "$DRY" = "on" ]; then
            log "${C_DIM}  [dry-run] ${name}: refresh ${envfile} if present${C_RESET}"; continue
        fi
        if ssh -o BatchMode=yes -o ConnectTimeout=8 "$tgt" "test -f '${envfile}'" 2>/dev/null; then
            write_nm_envfile_remote "$tgt" "$envfile" "$user" "$pw" && did=1
        fi
    done
    [ "$did" = 1 ] && log "  ${C_DIM}Boot env refreshed — restart engine servers to pick up the new NM trust passphrase.${C_RESET}"
    return 0
}

# Install nodemanager.service for our nmdomain (RUN: e).
do_install_nm_service() {
    local mw="$MWHOME" nmdom="$NM_DOMAIN"
    [ -n "$nmdom" ] || { warn "no nm.domain.name."; return 1; }
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    [ "$DRY" = "on" ] || [ -d "$nmhome" ] || { warn "nmdomain not found: ${nmhome} — create the Node Manager domain first."; return 1; }
    local user grp; IFS=: read -r user grp <<< "$(unit_owner_of_path "$nmhome")"
    local text
    text="$(render_systemd_unit "WebLogic Node Manager (BLADE ${nmdom})" \
        "$nmhome" "${nmhome}/bin/startNodeManager.sh" "${nmhome}/bin/stopNodeManager.sh" \
        "$user" "$grp" "")"
    install_systemd_unit nodemanager.service "$text"
    # So the NM boot unit can exec startNodeManager.sh (+ java) after a reboot on a
    # fresh /opt/oracle volume. No-op unless the tree is unlabeled.
    selinux_relabel "$nmhome" "${JAVA_BASE:-/opt/oracle/java}" "$mw"
}

# Install weblogic.service for our app domain's AdminServer (RUN: w). Starts the
# AdminServer THROUGH Node Manager (misc/start-admin-nm.sh), exactly like RUN s,
# so the domain must already be enrolled in NM (configure 'c' / a prior 's' does
# that — enrollment persists in nodemanager.domains across reboots). Ordered
# after nodemanager.service and waits for its listener (start-admin-nm.sh).
do_install_wls_service() {
    local mw="$MWHOME" dom="$DOMAIN"
    [ -n "$dom" ] || { warn "no domain name."; return 1; }
    local domhome="${DOMAINS_DIR}/${dom}"
    [ "$DRY" = "on" ] || [ -d "$domhome" ] || { warn "app domain not found: ${domhome} — create the cluster domain first (configure)."; return 1; }
    local user grp; IFS=: read -r user grp <<< "$(unit_owner_of_path "$domhome")"
    # Boot start is nmConnect/nmStart, so the domain must be enrolled in NM. Warn
    # (don't fail) if it isn't yet — 'c' or a first 's' enrolls it persistently.
    local nmfile="${DOMAINS_DIR}/${NM_DOMAIN}/nodemanager/nodemanager.domains"
    if [ "$DRY" != "on" ] && { [ ! -f "$nmfile" ] || ! grep -q "^${dom}=" "$nmfile" 2>/dev/null; }; then
        warn "'${dom}' isn't enrolled in ${NM_DOMAIN} yet — run configure (or start the AdminServer) once so boot start works."
    fi
    # The boot service runs the same scripts install.sh uses, but from inside the
    # domain so the unit doesn't depend on this checkout still being here.
    stage_boot_scripts "$domhome" || return 1
    local pw="${BLADE_WLS_PASSWORD:-}"
    [ -z "$pw" ] && [ -f "$WLS_SECRET" ] && pw="$(read_prop "$WLS_SECRET" admin.password)"
    local envfile="${domhome}/.blade-nm.env"
    write_nm_envfile "$envfile" "$user" "$pw" || true
    local text
    text="$(render_admin_nm_unit "$dom" "$domhome" "${domhome}/${BOOT_SCRIPT_SUBDIR}" "$user" "$grp" "$envfile")"
    install_systemd_unit weblogic.service "$text" || return 1

    # machine0 runs the AdminServer AND the STATIC engine0, so that engine needs its
    # own unit: weblogic.service starts only the AdminServer, and the engine units
    # live on the engine hosts. Without this it is the one server that stays down
    # after a reboot. engine0 is always index 0 (the static admin-box engine),
    # independent of the dynamic range's starting index (1).
    local sname="${prefix:-engine}0"
    write_boot_properties "$domhome" "$sname" "${ADMIN_USER:-weblogic}" "$pw" || true
    local stext
    stext="$(render_admin_nm_unit "$dom" "$domhome" "${domhome}/${BOOT_SCRIPT_SUBDIR}" \
        "$user" "$grp" "$envfile" "$sname" "${ADMINURL:-t3://${H_ADDR[0]}:7001}")"
    install_systemd_unit "weblogic-${sname}.service" "$stext"
    # Before systemd execs either boot script, make sure they (and java, and the
    # server's libs) are SELinux-labeled — a fresh /opt/oracle volume is unlabeled
    # and both units would 203/EXEC. No-op unless the tree is actually unlabeled.
    selinux_relabel "$domhome" "${JAVA_BASE:-/opt/oracle/java}" "$mw"
    # A single-machine install IS the cluster, so leaving its only engine stopped
    # means "install complete" with nothing serving SIP.
    if [ "$DRY" != "on" ] && command -v systemctl >/dev/null 2>&1; then
        info "Starting ${sname} …"
        sudo systemctl start "weblogic-${sname}.service" 2>/dev/null \
            && ok "${sname} started." \
            || warn "${sname} did not start — 'journalctl -u weblogic-${sname}'."
    fi
}

# ----------------------------------------------------------------------------
# Deploy the hosted WebLogic Remote Console (RUN: o).
#
# WLS 14.1.2 dropped the built-in /console, so without this there is no browser
# admin UI at all. Oracle ships the deployer with the product; this just drives
# it. Idempotent — checks config.xml for the app before doing anything.
# ----------------------------------------------------------------------------
do_console() {
    local mw="$MWHOME" dom="$DOMAIN"
    local addr="${H_ADDR[0]:-localhost}"
    local admin_port; admin_port="$(printf '%s' "${ADMINURL:-}" | sed -E 's#.*:([0-9]+).*#\1#')"
    admin_port="${admin_port:-7001}"
    local deployer="${mw}/wlserver/server/bin/remote_console_deployment.py"
    local wlst="${mw}/oracle_common/common/bin/wlst.sh"
    local adminurl="t3://${addr}:${admin_port}"
    local config="${DOMAINS_DIR}/${dom}/config/config.xml"

    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ${wlst} remote_console_deployment.py ${adminurl} ${ADMIN_USER} <pw-on-stdin> -> deploys /rconsole${C_RESET}"
        return 0
    fi
    if grep -q 'weblogic-remote-console-app' "$config" 2>/dev/null; then
        ok "Hosted Remote Console already deployed — http://${addr}:${admin_port}/rconsole"
        return 0
    fi
    [ -f "$deployer" ] || { warn "Remote Console deployer not found: ${deployer} — needs OCCAS/WLS 14.1.2+."; return 1; }
    [ -x "$wlst" ]     || { warn "wlst.sh not found/executable: ${wlst}"; return 1; }
    local pw; pw="$(get_admin_pw)" || return 1
    info "Deploying hosted Remote Console to ${adminurl} …"
    # Password piped on stdin (the deployer reads it there) — never on argv/ps.
    if printf '%s\n' "$pw" | "$wlst" "$deployer" "$adminurl" "${ADMIN_USER:-weblogic}"; then
        ok "Hosted Remote Console deployed — http://${addr}:${admin_port}/rconsole  (Provider: 'This Server')"
    else
        warn "Remote Console deployment failed — see the WLST output above."; return 1
    fi
}

# ----------------------------------------------------------------------------
# Provision the engine hosts (RUN: E).
#
# Everything an engine needs sits at the SAME absolute paths as on the admin box,
# so this is: rsync ORACLE_HOME (product + both domains) + the runtime JDK + the
# env certs, install the boot services, start them. Unreachable hosts are skipped
# with a warning and the run resumes on a re-run.
#
# Two things the old per-host provisioning had to do are gone:
#   * No per-host nodemanager.properties rewrite. NM binds 0.0.0.0 here
#     (nm.bind.address), so the copied file is already right.
#   * No separate enrollment step. NM lives in nmdomain, which the same rsync
#     carries along with its nodemanager.domains file; identical paths on every
#     host mean the enrollment arrives correct.
#
# And the servers are started THROUGH systemd rather than nohup, so provisioning
# exercises the exact path a reboot will take. If this works, boot works.
# ----------------------------------------------------------------------------
# Provision ONE host: everything an engine needs, at the same absolute paths.
#
# Split out of the fleet loop so "Add a machine" and "re-provision everything"
# share one implementation. idx is the index into the H_* arrays; the server it
# runs is <prefix><idx>, which is why machine0 runs engine0.
#
# Skips the multi-GB rsync when OCCAS is already present -- a VM cloned from
# machine0 only needs registering, not re-shipping.
provision_one_host() {
    local idx="$1"

    local mw="$MWHOME" dom="$DOMAIN" nmdom="$NM_DOMAIN"
    local sshu="${SSH_USER:-$(id -un)}"
    local nhosts="${#H_NAME[@]}"
    [ -n "$mw" ] && [ -n "$dom" ] || { warn "profile incomplete (oracle.home / domain.name)."; return 1; }
    if [ "$nhosts" -le 1 ]; then
        ok "No engine hosts in this profile — nothing to provision."
        return 0
    fi

    local cdir; cdir="$(read_prop "$OCCAS_CONF" certs.dir)"
    cdir="${cdir/#\~/$HOME}"; cdir="${cdir:-$(blade_certs_dir_for_conf "$OCCAS_CONF")}"
    local domhome="${DOMAINS_DIR}/${dom}"
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    local jdk; jdk="$(java_home_stable)"   # the link when it matches java.home
    # java.home may be the <java.dir>/current symlink; ship the REAL JDK it
    # resolves to and repoint the link on the far side, same as the Oracle home
    # below -- rsync'ing the link path would land the link itself, dangling.
    local jdk_real="" jdk_link=""
    if [ -n "$jdk" ]; then
        jdk_real="$(readlink -f "$jdk" 2>/dev/null || printf '%s' "$jdk")"
        [ "$jdk_real" != "$jdk" ] && jdk_link="$jdk"
    fi
    local adminurl="${ADMINURL:-t3://${H_ADDR[0]}:7001}"
    local pw; pw="${BLADE_WLS_PASSWORD:-}"
    [ -z "$pw" ] && [ -f "$WLS_SECRET" ] && pw="$(read_prop "$WLS_SECRET" admin.password)"
    local user grp; IFS=: read -r user grp <<< "$(unit_owner_of_path "$mw")"

    # Stage the boot helpers into the domain BEFORE the rsync, so each engine
    # receives them as part of the domain copy rather than needing a repo clone.
    stage_boot_scripts "$domhome" || return 1
    local eng name addr
    [ "${H_ROLE[$idx]}" = "engine" ] || return 0
    name="${H_NAME[$idx]}"; addr="${H_ADDR[$idx]}"
    eng="${prefix:-engine}${idx}"
    local tgt="${sshu}@${addr}"
    rule
    info "${name} (${addr}) → server ${eng}"

    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ssh ${tgt} true; ensure ${grp} + ${user} exist there${C_RESET}"
        log "${C_DIM}  [dry-run] sudo install -d -o ${user} -g ${grp} $(dirname "$mw") ${DOMAINS_DIR} ${LOG_DIR:-/var/log/weblogic}/nodemanager${jdk_real:+ $(dirname "$jdk_real")}${jdk_link:+ $(dirname "$jdk_link")}; -o ${sshu} $(dirname "$cdir")${C_RESET}"
        log "${C_DIM}  [dry-run] xfer_rsync ${user}:${grp} ${mw}/ ${tgt}:${mw}/   (first run moves several GB)${C_RESET}"
        [ -n "$jdk_real" ] && log "${C_DIM}  [dry-run] xfer_rsync ${user}:${grp} ${jdk_real} ${tgt}:$(dirname "$jdk_real")/${C_RESET}"
        [ -n "$jdk_link" ] && log "${C_DIM}  [dry-run] ssh ${tgt} sudo ln -sfn ${jdk_real} ${jdk_link}${C_RESET}"
        log "${C_DIM}  [dry-run] rsync -a ${cdir}/ ${tgt}:${cdir}/${C_RESET}"
        log "${C_DIM}  [dry-run] write ${domhome}/servers/${eng}/security/boot.properties (as ${user})${C_RESET}"
        install_systemd_unit_remote "$tgt" nodemanager.service \
            "$(render_systemd_unit "WebLogic Node Manager (BLADE ${nmdom})" \
                "$nmhome" "${nmhome}/bin/startNodeManager.sh" "${nmhome}/bin/stopNodeManager.sh" \
                "$user" "$grp" "")"
        write_nm_envfile_remote "$tgt" "${domhome}/.blade-nm.env" "$user" "$pw"
        install_systemd_unit_remote "$tgt" weblogic-engine.service \
            "$(render_admin_nm_unit "$dom" "$domhome" "${domhome}/${BOOT_SCRIPT_SUBDIR}" "$user" "$grp" \
                "${domhome}/.blade-nm.env" "$eng" "$adminurl")"
        log "${C_DIM}  [dry-run] systemctl start nodemanager.service weblogic-engine.service${C_RESET}"
        return 0
    fi

    # --- reachability -----------------------------------------------------
    if ! ssh -o BatchMode=yes -o ConnectTimeout=8 "$tgt" true 2>/dev/null; then
        warn "${name}: no key-based ssh to ${tgt} — skipped. (ssh-copy-id ${tgt}, then re-run 'E')"
        return 1
    fi
    if ! ssh -o BatchMode=yes "$tgt" "sudo -n true" 2>/dev/null; then
        warn "${name}: no passwordless sudo for ${sshu} — needed for dirs, firewall and the boot services. Skipped."
        return 1
    fi

    # The units name User=/Group= from the ADMIN box's owner, so BOTH have to
    # exist there. A missing group is not a warning you get to ignore: systemd
    # refuses the unit with 216/GROUP ("Failed to determine group
    # credentials"), which says nothing about which group or which host.
    # Create both — engine hosts have no repo clone, so "run 'u' over there"
    # was never an instruction anyone could follow. Numeric ids are pinned to
    # the local ones when free; ownership itself travels BY NAME (xfer_rsync
    # --chown), so a differing uid is cosmetic, not a failure.
    local ggid; ggid="$(getent group "$grp" 2>/dev/null | cut -d: -f3)"
    if ! ssh -o BatchMode=yes "$tgt" "getent group '${grp}' >/dev/null 2>&1"; then
        info "  group '${grp}' missing there — creating it${ggid:+ (gid ${ggid})}"
        if ! ssh -o BatchMode=yes "$tgt" "sudo groupadd ${ggid:+-g ${ggid}} '${grp}'"; then
            warn "${name}: could not create group '${grp}' — the boot services would fail with 216/GROUP. Skipped."
            return 1
        fi
    fi
    if ! ssh -o BatchMode=yes "$tgt" "id '${user}' >/dev/null 2>&1"; then
        local luid; luid="$(id -u "$user" 2>/dev/null || true)"
        info "  user '${user}' missing there — creating${luid:+ (uid ${luid})}"
        if ! ssh -o BatchMode=yes "$tgt" "sudo useradd ${luid:+-u ${luid}} -g '${grp}' -m '${user}' \
                || sudo useradd -g '${grp}' -m '${user}'"; then
            warn "${name}: could not create user '${user}' — the boot services would fail. Skipped."
            return 1
        fi
    else
        ssh -o BatchMode=yes "$tgt" "id -nG '${user}' | tr ' ' '\n' | grep -qx '${grp}' || sudo usermod -aG '${grp}' '${user}'" \
            || warn "${name}: could not ensure '${user}' is in '${grp}'."
    fi

    # --- landing zone -----------------------------------------------------
    # ORACLE_HOME is a symlink; ship the REAL versioned directory it resolves to
    # and repoint the link on the far side. rsync'ing the link path itself would
    # land a real directory called 'current' on the engine and destroy the flip.
    local real_home ver
    real_home="$(readlink -f "$mw" 2>/dev/null || printf '%s' "$mw")"
    ver="$(basename "$real_home")"
    # Install trees land under the install user; only the cert staging dir
    # (inside the login user's own home) stays the ssh user's.
    if ! ssh -o BatchMode=yes "$tgt" \
         "sudo install -d -o '${user}' -g '${grp}' '$(dirname "$real_home")' '${DOMAINS_DIR}' '${LOG_DIR:-/var/log/weblogic}/nodemanager'${jdk_real:+ '$(dirname "$jdk_real")'}${jdk_link:+ '$(dirname "$jdk_link")'} \
          && sudo install -d -o '${sshu}' '$(dirname "$cdir")'" 2>/dev/null; then
        warn "${name}: could not create target dirs — skipped."
        return 1
    fi
    do_open_firewall_remote "$tgt" || warn "${name}: firewall opening failed — cluster ports may be blocked."

    # --- copy -------------------------------------------------------------
    # Binaries and domains are separate trees now, so they are separate copies.
    # This is also the whole patch story for an engine: it never runs OPatch, it
    # receives a home that was patched and validated once, on machine0.
    # The owner's trees go through xfer_rsync: local root reads the 0600
    # secrets, remote root writes them, --chown keeps the owner by name.
    info "  rsync OCCAS home ${ver} (~1GB) …"
    if ! xfer_rsync "${user}:${grp}" "${real_home}/" "${tgt}:${real_home}/"; then
        warn "${name}: rsync of ${real_home} failed — skipped."; return 1
    fi
    if ! ssh -o BatchMode=yes "$tgt" "sudo ln -sfn '${real_home}' '${mw}'"; then
        warn "${name}: could not point ${mw} at ${ver} — skipped."; return 1
    fi
    info "  rsync domains …"
    local _d
    for _d in "$dom" "$nmdom"; do
        [ -d "${DOMAINS_DIR}/${_d}" ] || continue
        if ! xfer_rsync "${user}:${grp}" \
              "${DOMAINS_DIR}/${_d}/" "${tgt}:${DOMAINS_DIR}/${_d}/" \
              --exclude 'servers/*/logs/' --exclude 'servers/*/tmp/' \
              --exclude 'servers/*/cache/' --exclude 'nodemanager/*.log*' \
              --exclude 'nodemanager/*.pid'; then
            warn "${name}: rsync of domain ${_d} failed — skipped."; return 1
        fi
    done
    if [ -n "$jdk_real" ] && ! xfer_rsync "${user}:${grp}" "$jdk_real" "${tgt}:$(dirname "$jdk_real")/"; then
        warn "${name}: rsync of ${jdk_real} failed — skipped."; return 1
    fi
    if [ -n "$jdk_link" ] && ! ssh -o BatchMode=yes "$tgt" "sudo ln -sfn '${jdk_real}' '${jdk_link}'"; then
        warn "${name}: could not point ${jdk_link} at $(basename "$jdk_real") — skipped."; return 1
    fi
    # The cert staging dir is the invoker's on both sides — plain copy.
    if [ -d "$cdir" ] && ! rsync -a "${cdir}/" "${tgt}:${cdir}/"; then
        warn "${name}: rsync of ${cdir} failed — skipped."; return 1
    fi
    # Keystores live outside the Oracle home, so they are their own copy.
    local ksd="${KEYSTORE_DIR:-}"
    if [ -n "$ksd" ] && [ -d "$ksd" ]; then
        ssh -o BatchMode=yes "$tgt" "sudo install -d -o '${user}' -g '${grp}' '${ksd}'" 2>/dev/null
        if ! xfer_rsync "${user}:${grp}" "${ksd}/" "${tgt}:${ksd}/"; then
            warn "${name}: rsync of keystores (${ksd}) failed — skipped."; return 1
        fi
    fi
    ssh -o BatchMode=yes "$tgt" \
        "sudo chmod -R g-w,o-rwx '${domhome}'; f='${domhome}/config/nodemanager/nm_password.properties'; [ -f \"\$f\" ] && sudo chmod 600 \"\$f\"; true" \
        || warn "${name}: domain permission hardening failed (non-fatal)."

    # --- boot identity ----------------------------------------------------
    # A prod-mode managed server with no boot.properties prompts for the boot
    # username/password on a stdin Node Manager has redirected, and dies with
    # BEA-090782. Piped over ssh stdin so it never reaches a command line.
    # Same BEA-090782 trap as the AdminServer; written before the rsync would
    # be lost, so it goes over ssh stdin here (never a command line).
    if ! printf 'username=%s\npassword=%s\n' "${ADMIN_USER:-weblogic}" "$pw" \
         | ssh -o BatchMode=yes "$tgt" \
               "sudo -u '${user}' sh -c 'd=\"${domhome}/servers/${eng}/security\"; mkdir -p \"\$d\" && umask 177 && cat > \"\$d/boot.properties\"'"; then
        warn "${name}: could not write ${eng} boot.properties — skipped."
        return 1
    fi

    # --- boot services ----------------------------------------------------
    install_systemd_unit_remote "$tgt" nodemanager.service \
        "$(render_systemd_unit "WebLogic Node Manager (BLADE ${nmdom})" \
            "$nmhome" "${nmhome}/bin/startNodeManager.sh" "${nmhome}/bin/stopNodeManager.sh" \
            "$user" "$grp" "")" \
        || { warn "${name}: nodemanager.service not installed."; return 1; }
    write_nm_envfile_remote "$tgt" "${domhome}/.blade-nm.env" "$user" "$pw" || true
    install_systemd_unit_remote "$tgt" weblogic-engine.service \
        "$(render_admin_nm_unit "$dom" "$domhome" "${domhome}/${BOOT_SCRIPT_SUBDIR}" "$user" "$grp" \
            "${domhome}/.blade-nm.env" "$eng" "$adminurl")" \
        || { warn "${name}: weblogic-engine.service not installed."; return 1; }

    # --- start, through systemd (the same path a reboot takes) ------------
    # The engine host's /opt/oracle was just rsync'd into; if it's a fresh volume
    # the files are unlabeled_t and both units would 203/EXEC. Relabel first.
    selinux_relabel_remote "$tgt" "${JAVA_BASE:-/opt/oracle/java}" "$mw" "$domhome" "$nmhome"
    info "  starting nodemanager.service …"
    if ! ssh -o BatchMode=yes "$tgt" "sudo systemctl restart nodemanager.service"; then
        warn "${name}: nodemanager.service failed to start — 'journalctl -u nodemanager' on that host."
        return 1
    fi
    info "  starting ${eng} (weblogic-engine.service) …"
    if ! ssh -o BatchMode=yes "$tgt" "sudo systemctl restart weblogic-engine.service"; then
        warn "${name}: ${eng} did not start — 'journalctl -u weblogic-engine' on that host."
        return 1
    fi
    ok "${name}: provisioned, boot services enabled, ${eng} started."
    return 0
}

# Add a machine (dashboard: addm).
#
# The domain grows ONLINE: create the Machine, append it to the match expression,
# raise the server count, and the template stamps a new engine with the same
# certificate, the same SIP channels and the same ports as every other one. No
# domain rebuild, no downtime -- which is only possible because nothing is a
# static server any more (a static server with SIP channels needs offline WLST).
do_add_machine() {
    [ -n "$DOMAIN" ] && [ "${#H_NAME[@]}" -ge 1 ] || { warn "profile incomplete."; return 1; }
    local n="${#H_NAME[@]}"                  # next index == next server number
    local dn="machine${n}" name addr pub fqdn
    log ""; log "${C_BOLD}Add machine${n}${C_RESET} — will run ${prefix:-engine}${n}"
    ask name "  machine name"                                        "$dn"
    ask addr "  reachable address (IP/host the AdminServer dials)"   ""
    [ -n "$addr" ] || { warn "an address is required."; return 1; }
    ask pub  "  public IP (cert SAN; Enter to skip)"                 ""
    ask fqdn "  fully-qualified DNS name (SAN; Enter to skip)"       ""

    local i
    for i in "${!H_NAME[@]}"; do
        [ "${H_NAME[$i]}" = "$name" ] && { warn "'${name}' is already in this profile."; return 1; }
    done

    # Extend the in-memory view first so provision_one_host can use it.
    H_NAME+=("$name"); H_ADDR+=("$addr"); H_PORT+=("$NM_PORT"); H_TYPE+=("$NM_TYPE")
    H_PUB+=("$pub");   H_FQDN+=("$fqdn");  H_ROLE+=("engine")
    DCOUNT="${#H_NAME[@]}"
    # ENGINE machines only (index 1..N); machine0/engine0 is static, outside the
    # dynamic template, so it is never in the match expression. The ceiling stays
    # fixed at DYN_MAX (default 1000) — adding a machine only extends the match.
    local newmatch="" i
    for i in "${!H_NAME[@]}"; do
        [ "$i" -eq 0 ] && continue
        newmatch="${newmatch:+${newmatch},}${H_NAME[$i]}"
    done
    match="$newmatch"

    # 1. the DOMAIN first. The new server has to exist before the host can be told
    #    to start it, and the rsync in step 2 copies this domain -- so the engine
    #    receives a config that already knows about itself.
    if ! cluster_resize "$name" "$addr" "$newmatch" "${DYN_MAX:-1000}"; then
        warn "could not add ${name} to the domain — nothing changed."
        local last=$(( ${#H_NAME[@]} - 1 ))
        unset "H_NAME[$last]" "H_ADDR[$last]" "H_PORT[$last]" "H_TYPE[$last]" "H_PUB[$last]" "H_FQDN[$last]" "H_ROLE[$last]"
        return 1
    fi

    # 2. persist the profile NOW — the server exists in the domain, so the profile
    #    must record it even if provisioning fails below. Otherwise the two drift
    #    (domain has the server, profile doesn't) and the next 'Add a machine'
    #    mis-numbers because it counts from a short profile. Provisioning is
    #    independently retryable afterward via 'Re-provision every engine host'.
    save_profile

    # 3. then the host
    if ! provision_one_host "$n"; then
        warn "provisioning ${name} failed — it is in the domain and profile but not"
        warn "running; fix the host and re-run 'Re-provision every engine host'."
        return 1
    fi

    ok "machine${n} added — ${prefix:-engine}${n} on ${name} (${addr})."
    return 0
}

# Remove the LAST machine (dashboard: remm).
#
# Only the highest-numbered one. Server index N lands on the Nth machine in the
# match expression, so removing from the middle would silently re-home every
# engine after it onto a different box.
do_remove_machine() {
    local n=$(( ${#H_NAME[@]} - 1 ))
    [ "$n" -ge 1 ] || { warn "nothing to remove — ${H_NAME[0]:-this host} is the install itself."; return 1; }
    local name="${H_NAME[$n]}" addr="${H_ADDR[$n]}" eng="${prefix:-engine}${n}"
    local domhome="${DOMAINS_DIR}/${DOMAIN}"

    yesno "Remove ${name} (${addr}) and its server ${eng}? Stops it, deletes its domain copy and boot services." "N" || return 1

    # Domain first: stop targeting the machine before tearing the host down. The
    # remaining match is the ENGINE machines only (index 1..n-1); machine0 is the
    # static admin box and never in the expression. Ceiling stays DYN_MAX.
    local newmatch="" i
    for i in $(seq 1 $((n - 1))); do newmatch="${newmatch:+${newmatch},}${H_NAME[$i]}"; done
    cluster_resize "" "" "$newmatch" "${DYN_MAX:-1000}" "$name" || warn "domain not updated — continuing with host teardown."

    # Host: reuse the guarded teardown, which stops running servers first.
    local keep_name=("${H_NAME[@]}") keep_addr=("${H_ADDR[@]}") keep_role=("${H_ROLE[@]}")
    H_NAME=("$name"); H_ADDR=("$addr"); H_ROLE=("engine")
    remove_engine_systemd_units "$domhome" "${DOMAINS_DIR}/${NM_DOMAIN}"
    H_NAME=("${keep_name[@]}"); H_ADDR=("${keep_addr[@]}"); H_ROLE=("${keep_role[@]}")

    local last=$(( ${#H_NAME[@]} - 1 ))
    unset "H_NAME[$last]" "H_ADDR[$last]" "H_PORT[$last]" "H_TYPE[$last]" "H_PUB[$last]" "H_FQDN[$last]" "H_ROLE[$last]"
    DCOUNT="${#H_NAME[@]}"; match="$newmatch"
    save_profile
    ok "${name} removed — cluster is now ${DCOUNT} engine(s)."
    return 0
}

# Online WLST: create/drop a Machine and resize the dynamic cluster.
# Empty machine name = resize only (used by remove).
#
# TWO activations, deliberately. Creating the Machine and editing the cluster's
# MachineNameMatchExpression in ONE edit session fails in the prepare phase with
#   ArrayIndexOutOfBoundsException: Index -1 ... at DynamicServersProcessor.setMachineName
# because the expression is resolved against machines that are not committed yet,
# so the name lookup returns -1. Commit the Machine first and it is fine. (This
# is what you do by hand in the console without noticing: save, then edit.)
#
# Also: any STALE server JVM from a previous config will hang the second
# activation in STATE_DISTRIBUTING until it times out. Stop dead servers first.
cluster_resize() {
    local mname="$1" maddr="$2" newmatch="$3" count="$4" delname="${5:-}"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] online WLST, phase 1: ${mname:+create/reuse Machine ${mname} (${maddr}) + activate}${C_RESET}"
        log "${C_DIM}  [dry-run] online WLST, phase 2: match=${newmatch}; count=${count} + activate${C_RESET}"
        [ -n "$delname" ] && log "${C_DIM}  [dry-run] online WLST, phase 3: drop Machine ${delname} + activate${C_RESET}"
        return 0
    fi
    local pw; pw="$(get_admin_pw)" || return 1
    local work; work="$(mktemp -d /tmp/blade-resize.XXXXXX)"
    cat > "${work}/resize.py" <<PYEOF
# -*- coding: utf-8 -*-
connect('${ADMIN_USER:-weblogic}', '${pw}', '${ADMINURL:-t3://${H_ADDR[0]}:7001}')
edit()
try:
    stopEdit('y')
except:
    pass

mname = '${mname}'
# --- phase 1: the Machine, committed on its own -----------------------------
if mname:
    startEdit()
    cd('/')
    # Reuse an existing Machine rather than re-create it. A prior 'remove' that
    # left the Machine behind (or any stale one) makes createUnixMachine throw
    # BeanAlreadyExists; catching that Python error does NOT untaint the JMX edit
    # session, so the following activate() dies with [Management:141191]. Checking
    # first keeps the edit clean and just refreshes the NodeManager address below.
    created = mname not in [m.getName() for m in cmo.getMachines()]
    if created:
        cmo.createUnixMachine(mname)
    cd('/Machines/' + mname + '/NodeManager/' + mname)
    _ch = created
    if cmo.getListenAddress() != '${maddr}':
        cmo.setListenAddress('${maddr}'); _ch = 1
    if cmo.getListenPort() != int('${NM_PORT:-5556}'):
        cmo.setListenPort(int('${NM_PORT:-5556}')); _ch = 1
    if cmo.getNMType() != '${NM_TYPE:-ssl}':
        cmo.setNMType('${NM_TYPE:-ssl}'); _ch = 1
    # Same idempotency as phase 2: an unchanged Machine must not activate, or the
    # push to a running engine can trip the dynamic-server -1 for no reason.
    if _ch:
        save()
        activate(block='true')
    else:
        stopEdit('y')
    print('MACHINE_COMMITTED ' + mname)

# --- phase 2: only now can the cluster reference it --------------------------
# Idempotent: re-applying an UNCHANGED MachineNameMatchExpression still trips the
# prepare phase (ArrayIndexOutOfBounds -1 in DynamicServersProcessor.setMachineName),
# so a re-run against an already-sized cluster fails even though nothing needs to
# change. Set only what differs, and release the edit without activating when the
# cluster is already at the target -- reconciling the profile/host is then free.
edit()
startEdit()
cd('/Clusters/BEA_ENGINE_TIER_CLUST')
ds = cmo.getDynamicServers()
_changed = 0
if ds.getMachineNameMatchExpression() != '${newmatch}':
    ds.setMachineNameMatchExpression('${newmatch}'); _changed = 1
if ds.getMaximumDynamicServerCount() != int('${count}'):
    ds.setMaximumDynamicServerCount(int('${count}')); _changed = 1
if _changed:
    save()
    activate(block='true')
else:
    stopEdit('y')
print('CLUSTER_RESIZED match=${newmatch} count=${count}')

delname = '${delname}'
# --- phase 3: drop a removed Machine, only AFTER the cluster stopped ---------
# referencing it (phase 2 shrank the match expression). Leaving it behind is
# what makes the next 'Add a machine' collide on BeanAlreadyExists.
if delname:
    edit()
    startEdit()
    cd('/')
    if delname in [m.getName() for m in cmo.getMachines()]:
        delete(delname, 'Machine')
    save()
    activate(block='true')
    print('MACHINE_DROPPED ' + delname)
PYEOF
    chmod 600 "${work}/resize.py"
    local out
    out="$("${MWHOME}/oracle_common/common/bin/wlst.sh" "${work}/resize.py" 2>&1)"
    rm -rf "$work"
    printf '%s\n' "$out" | grep -E "MACHINE_COMMITTED|CLUSTER_RESIZED|MACHINE_DROPPED" | sed 's/^/  /'
    # Report the real result -- returning 0 unconditionally is how a failed
    # resize once got masked and provisioning charged ahead regardless.
    if printf '%s' "$out" | grep -q "CLUSTER_RESIZED"; then
        return 0
    fi
    warn "cluster resize FAILED:"
    printf '%s\n' "$out" | grep -iE "Exception|Error|STATE_DISTRIBUTING" | head -3 | sed 's/^/    /'
    return 1
}

# Report the patch level of every host, and whether they agree.
#
# A cluster running mixed patch levels is a real hazard and is otherwise
# invisible: each node resolves its OWN 'current' symlink, so two engines can be
# on different binaries with nothing to show for it until they behave
# differently. Reads only -- a mismatch is a warning, never a failure.
patch_levels() {
    local mw="${MWHOME}" sshu="${SSH_USER:-$(id -un)}" i host addr lvl cnt ref=""
    local drift=0
    for i in "${!H_NAME[@]}"; do
        host="${H_NAME[$i]}"; addr="${H_ADDR[$i]}"
        if [ "$i" -eq 0 ]; then
            lvl="$(readlink -f "$mw" 2>/dev/null)"; lvl="${lvl##*/}"
            cnt="$(grep -cE '^Patch  *[0-9]+' "$(readlink -f "$mw")/.blade-patch-manifest" 2>/dev/null || echo 0)"
        else
            lvl="$(ssh -o BatchMode=yes -o ConnectTimeout=8 "${sshu}@${addr}" \
                     "readlink -f '${mw}' 2>/dev/null" 2>/dev/null)"
            lvl="${lvl##*/}"
            cnt="$(ssh -o BatchMode=yes -o ConnectTimeout=8 "${sshu}@${addr}" \
                     "grep -cE '^Patch  *[0-9]+' \"\$(readlink -f '${mw}')/.blade-patch-manifest\" 2>/dev/null" 2>/dev/null)"
        fi
        [ -n "$lvl" ] || lvl="unreachable"
        [ -n "$cnt" ] || cnt=0
        printf '  %-12s %-16s %s interim patch(es)\n' "$host" "$lvl" "$cnt"
        if [ -z "$ref" ]; then ref="$lvl"
        elif [ "$lvl" != "$ref" ]; then drift=1; fi
    done
    if [ "$drift" = 1 ]; then
        warn "hosts are NOT on the same Oracle home — fix before trusting the cluster."
        return 1
    fi
    return 0
}

# JDK leg of patch (see do_patch below). The OCCAS leg is copy-and-switch-
# NOTHING; for the JDK the flip IS the patch -- there is no domain state inside
# a JDK for an out-of-place copy to protect. Every flip is asked, and rollback
# is one flip back. Sets _JDK_DID=1 when it migrated or flipped, so a JDK-only
# run counts as a complete patch run.
_JDK_DID=0
patch_jdk() {
    _JDK_DID=0
    [ "$(uname -s)" = "Linux" ] || return 0
    local base="${JAVA_BASE:-/opt/oracle/java}" link="${JAVA_BASE:-/opt/oracle/java}/current"
    local jh; jh="$(read_prop "$OCCAS_CONF" java.home)"
    [ -n "$jh" ] || return 0
    local ver want
    ver="$(read_prop "$OCCAS_CONF" occas.version)"
    [ -z "$ver" ] && ver="$(detect_occas_version "$MWHOME")"
    want="$(occas_jdk_major "$ver")"

    # Migration: a raw versioned java.home predates the link scheme. One flip
    # converts it; the units and engines pick the link up on their next re-run.
    if [ "$jh" != "$link" ] && [ -x "${jh}/bin/java" ]; then
        yesno "java.home is the versioned path ${jh} — switch to the ${link} link so JDK upgrades become a flip?" "Y" || return 0
        local realjh; realjh="$(readlink -f "$jh" 2>/dev/null || printf '%s' "$jh")"
        if [ "$DRY" = "on" ]; then
            log "${C_DIM}  [dry-run] ln -sfn ${realjh} ${link}; java.home=${link}${C_RESET}"
            return 0
        fi
        if { mkdir -p "$base" && ln -sfn "$realjh" "$link"; } 2>/dev/null \
          || { sudo mkdir -p "$base" && sudo ln -sfn "$realjh" "$link"; } 2>/dev/null; then
            set_conf_prop "$OCCAS_CONF" java.home "$link"
            JAVA_HOME_VAL="$link"
            _JDK_DID=1
            ok "${link} -> $(basename "$realjh"); java.home is now the link."
            log "  ${C_DIM}Re-run the boot-service steps (and re-provision the engines) so the units carry the link, then restart Node Manager.${C_RESET}"
        else
            warn "could not create ${link} — keeping ${jh}."
            return 0
        fi
        jh="$link"
    fi
    [ "$jh" = "$link" ] || return 0

    local cur; cur="$(readlink -f "$link" 2>/dev/null || true)"
    [ -n "$cur" ] && [ -x "${cur}/bin/java" ] || { warn "no usable JDK behind ${link}."; return 0; }
    local curmaj; curmaj="$(jdk_major "${cur}/bin/java")"
    [ -n "$curmaj" ] || return 0
    # Certification is a recommendation; the wizard let the user pick the major
    # and flips stay WITHIN it (changing majors is a wizard decision, not a
    # patch). Only below the certified floor is worth a warning.
    if [ -n "$want" ] && [ "$curmaj" -lt "$want" ] 2>/dev/null; then
        warn "current JDK is ${curmaj}, BELOW OCCAS ${ver}'s certified JDK ${want} — unlikely to run; re-run the occas phase to change majors."
    fi

    # Newest JDK of the RUNNING major that sort -V says is newer than the one
    # in use. Same basename in another dir is the same JDK -- not a candidate.
    local best="" d
    for d in "$base"/* /usr/lib/jvm/*; do
        [ -d "$d" ] && [ "${d##*/}" != "current" ] && [ -x "${d}/bin/java" ] || continue
        [ "${d##*/}" = "${cur##*/}" ] && continue
        [ "$(jdk_major "${d}/bin/java")" = "$curmaj" ] || continue
        [ "$(printf '%s\n%s\n' "${cur##*/}" "${d##*/}" | sort -V | tail -1)" = "${d##*/}" ] || continue
        if [ -z "$best" ] || [ "$(printf '%s\n%s\n' "${best##*/}" "${d##*/}" | sort -V | tail -1)" = "${d##*/}" ]; then
            best="$d"
        fi
    done

    if [ -z "$best" ] && jdk_dl_supported "$curmaj"; then
        # Detect "no new JDK" WITHOUT the ~200MB pull: Oracle's published .sha256
        # is a tiny, stable fingerprint. If it matches the sha we recorded on the
        # last download, the remote 'latest' hasn't changed — we're already current.
        local _rsha _ssha
        _rsha="$(jdk_remote_sha "$curmaj")"
        _ssha="$(read_prop "$OCCAS_CONF" jdk.latest.sha256)"
        if [ -n "$_rsha" ] && [ "$_rsha" = "$_ssha" ]; then
            ok "already on Oracle's latest JDK ${curmaj} ($(basename "$cur")) — checked remotely, no download."
        elif yesno "JDK: current -> $(basename "$cur"); no newer JDK ${curmaj} on this host. Download Oracle's latest into ${base}?" "Y"; then
            if download_jdk "$curmaj" "$base"; then
                # Record the remote sha so the next run can skip the download.
                if [ -n "$JDK_DL_SHA" ] && [ -f "$OCCAS_CONF" ]; then set_conf_prop "$OCCAS_CONF" jdk.latest.sha256 "$JDK_DL_SHA"; fi
                # Compare by version, not dir name: a distro build named
                # jdk-25.0.4-oracle-aarch64 is the SAME release as Oracle's jdk-25.0.4,
                # so flipping between them would churn the symlink for nothing.
                if [ "$(jdk_version "${JDK_DL_HOME}/bin/java")" = "$(jdk_version "${cur}/bin/java")" ]; then
                    ok "already on Oracle's latest (JDK $(jdk_version "${cur}/bin/java"))."
                else
                    best="$JDK_DL_HOME"
                fi
            fi
        fi
    fi
    [ -n "$best" ] || return 0

    yesno "JDK: current -> $(basename "$cur"); flip to $(basename "$best")?" "N" || return 0
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ln -sfn ${best} ${link}${C_RESET}"
        return 0
    fi
    ln -sfn "$best" "$link" 2>/dev/null || sudo ln -sfn "$best" "$link" 2>/dev/null \
        || { warn "could not flip ${link}."; return 0; }
    _JDK_DID=1
    local newver; newver="$(jdk_version "${best}/bin/java")"
    ok "${link} -> $(basename "$best")"
    log "  ${C_DIM}Nothing restarts itself — the JDK swap only takes hold on restart.${C_RESET}"
    log "  ${C_DIM}Rollback is one flip back:  ln -sfn ${cur} ${link}${C_RESET}"
    # Suggest restarts only for what already exists — a JDK flip must not push you
    # into first-time domain creation ('n' is Create & start, not a pure restart).
    [ -d "${DOMAINS_DIR}/${NM_DOMAIN}/config" ] && next_step n "restart Node Manager on JDK ${newver}"
    [ -d "${DOMAINS_DIR}/${DOMAIN}/config" ]    && next_step s "restart the AdminServer on JDK ${newver}"
    local _r _ne=0
    if [ "${#H_ROLE[@]}" -gt 0 ]; then
        for _r in "${H_ROLE[@]}"; do [ "$_r" = engine ] && _ne=$((_ne + 1)); done
    fi
    [ "$_ne" -gt 0 ] && next_step E "re-provision ${_ne} engine host(s) with JDK ${newver} + the flip"
    return 0
}

# Patch the Oracle home IN PLACE with OPatch (dashboard: patch).
#
# Oracle's eDelivery media ships buggy and the fixes come from My Oracle Support.
# opatch patches the LIVE, inventory-registered home ('current' -> <ver>): a raw
# copy of the home can't be patched — opatch rejects an unregistered ORACLE_HOME
# ("RawInventory gets null OracleHomeInfo"), and this OCCAS build has no tool to
# register one. Safety is opatch's own transactional apply + `rollback -id`, not a
# throwaway copy. The home must be idle (servers stopped) before patching.
#
# Patch source: a directory of downloaded Oracle patch .zip files. Every zip is
# unzipped and the OPatch patches are discovered from what unpacks — no hand-kept
# list. Numbered patches apply lowest-number-first; an OPatch-tool update (a zip
# that unpacks its own 'OPatch/' dir) applies first, since later patches may need
# the newer OPatch. A patch's directory (holding etc/config/inventory.xml) is
# named for its patch number, which is how they're ordered.
#
# Engines get the patched home by rsync: sync-occas.sh distribute <ver>. They are
# not patched individually — machine0's validated home is the source of truth.
do_patch() {
    local base="${OCCAS_BASE:-/opt/oracle/occas}" link="${MWHOME}"

    local real; real="$(readlink -f "$link" 2>/dev/null)"
    [ -n "$real" ] && [ -d "${real}/wlserver" ] || { warn "no Oracle home behind ${link} — install first."; return 1; }

    patch_jdk
    # Read java.home AFTER the JDK leg — migration may have just rewritten it.
    local jre; jre="$(read_prop "$OCCAS_CONF" java.home)"

    # opatch MUST run on the OCCAS-certified JDK, not the server JDK. A newer JDK
    # (e.g. 25) breaks opatch's PSU/composite XML parsing ("Unable to parse the xml
    # file") even though the servers run fine on it. If java.home is a different
    # major than certified, find a certified-major JDK — or fetch one — for opatch.
    local _wm; _wm="$(occas_jdk_major "$OCCAS_VERSION")"
    if [ -n "$_wm" ] && [ -x "${jre}/bin/java" ] && [ "$(jdk_major "${jre}/bin/java")" != "$_wm" ]; then
        local _cj="" _ln
        while IFS= read -r _ln; do
            [ "${_ln##*$'\t'}" = "$_wm" ] && { _cj="${_ln%%$'\t'*}"; break; }
        done < <(list_jdks)
        if [ -z "$_cj" ] && jdk_dl_supported "$_wm"; then
            info "opatch needs the certified JDK ${_wm} (java.home is $(jdk_major "${jre}/bin/java")); fetching it…"
            download_jdk "$_wm" "${JAVA_BASE:-/opt/oracle/java}" && _cj="$JDK_DL_HOME"
        fi
        if [ -n "$_cj" ] && [ -x "${_cj}/bin/java" ]; then
            jre="$_cj"; info "opatch will use the certified JDK ${_wm} at ${jre}."
        else
            warn "no certified JDK ${_wm} available for opatch — PSU/system patches may fail to parse on JDK $(jdk_major "${jre}/bin/java")."
        fi
    fi

    # --- where the downloaded patches live -----------------------------------
    local pdir; pdir="$(read_prop "$OCCAS_CONF" patch.dir)"; pdir="${pdir/#\~/$HOME}"
    pdir="${pdir:-${HOME}/occas-patches}"
    if [ -t 0 ] && [ "${ASSUME_YES:-0}" != 1 ]; then
        ask pdir "Patch directory (holds Oracle patch .zip files)" "$pdir"
    fi
    pdir="${pdir/#\~/$HOME}"
    [ -d "$pdir" ] || {
        warn "patch directory not found: ${pdir}"
        log  "  ${C_DIM}Download the OCCAS/WebLogic patch zips from My Oracle Support into it.${C_RESET}"
        return 1
    }
    [ "$DRY" = "on" ] || set_conf_prop "$OCCAS_CONF" patch.dir "$pdir"

    # Unzip every zip into a staging area, then discover what unpacked. Both the
    # stage and the patch dir are searched, so already-unzipped patches work too.
    local stage; stage="$(mktemp -d /tmp/blade-patch.XXXXXX)"
    local z
    for z in "$pdir"/*.zip; do
        [ -f "$z" ] || continue
        info "unzip $(basename "$z")"
        unzip -q -o "$z" -d "$stage" || { warn "unzip failed: $(basename "$z") — skipping."; continue; }
        # A patch zip carries OPatch metadata (etc/config/inventory.xml) or is an
        # OPatch tool update (OPatch/opatch). Anything else — product media, docs —
        # is not a patch; say so, so a wrong file dropped here isn't a silent no-op.
        unzip -l "$z" 2>/dev/null | grep -qE 'etc/config/inventory\.xml|OPatch/opatch|opatch_generic\.jar' \
            || warn "$(basename "$z") is not an OPatch patch (no patch metadata — looks like product media/docs); ignoring it."
    done

    # OPatch-tool update, two shipping formats: an 'OPatch/' dir to drop in (with an
    # 'opatch' launcher), or an 'opatch_generic.jar' installer to run. A normal patch
    # is a home carrying OPatch metadata at etc/config/inventory.xml; its directory
    # name is the patch number.
    local opdirs=() opjars=() pnum=() ppath=()
    local seen=" " d bn key f
    while IFS= read -r d; do
        [ -n "$d" ] && [ -f "${d}/opatch" ] && opdirs+=("$d")
    done < <(find "$stage" "$pdir" -maxdepth 4 -type d -name OPatch 2>/dev/null)
    while IFS= read -r f; do
        [ -n "$f" ] && opjars+=("$f")
    done < <(find "$stage" "$pdir" -maxdepth 4 -type f -name opatch_generic.jar 2>/dev/null)
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        d="$(dirname "$(dirname "$(dirname "$f")")")"   # …/etc/config/inventory.xml → patch home
        bn="$(basename "$d")"
        key="$(printf '%s' "$bn" | tr -cd '0-9')"; key="${key:-0}"
        case "$seen" in *" ${key} "*) continue ;; esac   # same patch found in stage AND pdir
        seen="${seen}${key} "
        pnum+=("$key"); ppath+=("$d")
    done < <(find "$stage" "$pdir" -type f -name inventory.xml -path '*/etc/config/inventory.xml' 2>/dev/null)

    # No INTERIM patches means nothing to patch — don't copy a whole home. An
    # OPatch tool update on its own doesn't change the product (it just updates the
    # patcher), and it's applied alongside real patches anyway; building a new home
    # for it is pointless and reads like a patch happened when none did.
    if [ "${#pnum[@]}" -eq 0 ]; then
        rm -rf "$stage"
        if [ "$(( ${#opdirs[@]} + ${#opjars[@]} ))" -gt 0 ]; then
            ok "No interim patches in ${pdir} — only an OPatch tool update, so nothing to patch."
            log "  ${C_DIM}An OPatch update applies together with real fixes; add OCCAS patch zips to ${pdir} to patch.${C_RESET}"
            return 0
        fi
        if [ "${_JDK_DID}" = "1" ]; then
            log "  ${C_DIM}No OCCAS/WebLogic patches found in ${pdir} — JDK-only run.${C_RESET}"
            return 0
        fi
        warn "no patches found in ${pdir} (looked for .zip files and unpacked patch homes)."
        return 1
    fi

    # Order the numbered patches lowest-first (the leading number sorts the line).
    local order=() pth
    if [ "${#pnum[@]}" -gt 0 ]; then
        local i
        while IFS= read -r pth; do order+=("$pth"); done < <(
            for i in "${!pnum[@]}"; do printf '%s\t%s\n' "${pnum[$i]}" "${ppath[$i]}"; done | sort -n | cut -f2-
        )
    fi

    # In-place: patch the LIVE, inventory-registered home. Out-of-place (cp -a a
    # copy, opatch it, flip 'current') can't work — opatch rejects an unregistered
    # copy with "RawInventory gets null OracleHomeInfo", and this OCCAS build ships
    # no tool to register one (opatch attachHome / FMW pasteBinary absent). opatch's
    # own `rollback -id` is the safety net the copy was trying to provide.
    local target="$real"
    local op="${target}/OPatch/opatch"

    info "Patch $(basename "$target") in place   ($(( ${#opdirs[@]} + ${#opjars[@]} )) OPatch update(s), ${#pnum[@]} patch(es), from ${pdir})"
    if [ "${#order[@]}" -gt 0 ]; then
        for pth in "${order[@]}"; do log "    $(basename "$pth")"; done
    fi

    if [ "$DRY" = "on" ]; then
        local d0 p0
        for d0 in ${opdirs[@]+"${opdirs[@]}"}; do log "${C_DIM}  [dry-run] replace ${target}/OPatch with the OPatch tool update${C_RESET}"; done
        for d0 in ${opjars[@]+"${opjars[@]}"}; do log "${C_DIM}  [dry-run] java -jar $(basename "$d0") -silent oracle_home=${target}  (OPatch tool update)${C_RESET}"; done
        for p0 in ${order[@]+"${order[@]}"}; do log "${C_DIM}  [dry-run] $(basename "$p0"): prereq CheckConflictAgainstOHWithDetail, then opatch apply -oh ${target}${C_RESET}"; done
        log "${C_DIM}  [dry-run] opatch lsinventory -oh ${target} > ${target}/.blade-patch-manifest${C_RESET}"
        rm -rf "$stage"
        return 0
    fi

    # opatch rewrites files in the home — it MUST be idle, or running JVMs break and
    # the patch can corrupt. Refuse while any WebLogic server or Node Manager is up.
    if pgrep -f 'weblogic.Server' >/dev/null 2>&1 || pgrep -f 'weblogic.NodeManager' >/dev/null 2>&1; then
        warn "WebLogic / Node Manager is running — an in-place patch needs the home idle."
        log  "  ${C_DIM}Stop it first: stop the AdminServer, then Node Manager, or: sudo systemctl stop weblogic nodemanager${C_RESET}"
        rm -rf "$stage"; return 1
    fi

    # Runs as the OWNER of the home (a legacy login-user install patches as that
    # user; opatch writes the inventory the same owner holds).
    local IU_USER; IU_USER="$(iu_owner_user "$target")"
    iu_adopt_dir "$stage" || { rm -rf "$stage"; return 1; }

    # OPatch-tool updates first — later patches may require the newer OPatch.
    # Dir format: drop the OPatch/ tree in. Jar format: run the opatch_generic.jar
    # installer (java -jar … -silent oracle_home=<OH>).
    local d
    for d in ${opdirs[@]+"${opdirs[@]}"}; do
        info "  OPatch tool update ← $(basename "$(dirname "$d")")"
        as_install_user sh -c "rm -rf '${target}/OPatch' && cp -a '${d}' '${target}/OPatch'" \
            || { warn "could not update OPatch in ${target}."; as_install_user rm -rf "$stage"; return 1; }
    done
    local _jb; _jb="${jre:+${jre}/bin/java}"; [ -x "$_jb" ] || _jb="$(java_bin)"
    for d in ${opjars[@]+"${opjars[@]}"}; do
        info "  OPatch tool update ← $(basename "$(dirname "$d")") (opatch_generic.jar)"
        local _oj
        if ! _oj="$(as_install_user sh -c "'${_jb}' -jar '${d}' -silent oracle_home='${target}'" 2>&1)"; then
            # The installer exits non-zero when OPatch is ALREADY current ("already
            # been installed") — that's a no-op, not a failure. Only stop on a real error.
            if printf '%s' "$_oj" | grep -qi 'already been installed'; then
                ok "  OPatch already current — nothing to update."
            else
                warn "OPatch update failed."; printf '%s\n' "$_oj" | strip_jdk_noise | grep -vE '^\s*$' | sed 's/^/    /' | tail -12
                as_install_user rm -rf "$stage"; return 1
            fi
        fi
    done

    # Numbered patches, lowest-first, in place. A failed conflict check changes
    # nothing for that patch; a failed apply is rolled back by opatch itself — but
    # EARLIER patches in the batch are already applied, so stop and report so you
    # can inspect (opatch lsinventory) before deciding to continue or roll back.
    local pd zb applied=0
    for pd in ${order[@]+"${order[@]}"}; do
        zb="$(basename "$pd")"
        info "  ${zb}: conflict check"
        local _cc
        if ! _cc="$(as_install_user sh -c "cd '${pd}' && ORACLE_HOME='${target}' '${op}' prereq CheckConflictAgainstOHWithDetail -ph '${pd}' -oh '${target}' ${jre:+-jre '${jre}'} -silent" 2>&1)"; then
            warn "${zb}: conflict check FAILED — stopping (${applied} patch(es) applied so far; ${zb} not applied)."
            printf '%s\n' "$_cc" | strip_jdk_noise | grep -vE '^\s*$' | sed 's/^/    /' | tail -18
            as_install_user rm -rf "$stage"; return 1
        fi
        info "  ${zb}: applying"
        # Explicit patch location (positional); capture the output so a failure
        # shows opatch's real reason instead of a bare "FAILED".
        local _ap
        if ! _ap="$(as_install_user sh -c "ORACLE_HOME='${target}' '${op}' apply '${pd}' -silent -oh '${target}' ${jre:+-jre '${jre}'}" 2>&1)"; then
            warn "${zb}: APPLY FAILED — stopping (opatch rolled ${zb} back; ${applied} earlier patch(es) remain applied)."
            printf '%s\n' "$_ap" | strip_jdk_noise | grep -vE '^\s*$' | sed 's/^/    /' | tail -18
            # opatch's "Unable to parse the xml file" is what a too-old OPatch says
            # about a PSU/system patch. If no OPatch updater was in the dir, that's
            # the likely cause — the patch's own OPatch update must apply first.
            if printf '%s' "$_ap" | grep -q 'Unable to parse the xml file'; then
                log "  ${C_DIM}\"Unable to parse the xml file\" on a PSU/system patch usually means a JDK mismatch (opatch fails on a newer JDK — OCCAS 8.3 is certified on 21) or an OPatch too old. blade now runs opatch on the certified JDK and applies an OPatch updater (p28186730_*.zip) first if present.${C_RESET}"
            fi
            as_install_user rm -rf "$stage"; return 1
        fi
        applied=$((applied + 1))
    done
    as_install_user rm -rf "$stage"

    as_install_user sh -c "ORACLE_HOME='${target}' '${op}' lsinventory -oh '${target}' ${jre:+-jre '${jre}'} > '${target}/.blade-patch-manifest' 2>&1" || true
    ok "Patched ${target} in place — ${applied} interim patch(es) applied."
    grep -cE "^Patch  *[0-9]+" "${target}/.blade-patch-manifest" 2>/dev/null \
        | sed 's/^/  interim patches now present: /'
    log "  ${C_DIM}The servers are down (patching needs them idle) — start them on the patched home: Node Manager, then the AdminServer.${C_RESET}"
    log "  ${C_DIM}Roll a patch back:  ${op} rollback -id <patch-number> -oh ${target}${C_RESET}"
    if [ "${#H_ROLE[@]}" -gt 0 ]; then
        local _ne=0 _r; for _r in "${H_ROLE[@]}"; do [ "$_r" = engine ] && _ne=$((_ne + 1)); done
        [ "$_ne" -gt 0 ] && log "  ${C_DIM}Push the patched home to the ${_ne} engine host(s):  ./sync-occas.sh ${NAME} distribute $(basename "$target")${C_RESET}"
    fi
    return 0
}

# Re-provision every engine host (dashboard: E). Repair after a rebuild.
do_provision_engines() {
    local nhosts="${#H_NAME[@]}" idx failed="" neng=0
    for idx in "${!H_NAME[@]}"; do [ "${H_ROLE[$idx]}" = "engine" ] && neng=$((neng + 1)); done
    if [ "$neng" -eq 0 ]; then
        ok "No other machines yet — this install is complete on ${H_NAME[0]:-this host} alone."
        log "  ${C_DIM}Add capacity with the 'Add a machine' row.${C_RESET}"
        return 0
    fi
    stage_boot_scripts "${DOMAINS_DIR}/${DOMAIN}" || return 1
    info "Re-provision ${neng} engine host(s) as ${SSH_USER:-$(id -un)}"
    for idx in $(seq 1 $((nhosts - 1))); do
        [ "${H_ROLE[$idx]}" = "engine" ] || continue
        provision_one_host "$idx" || failed="${failed} ${H_NAME[$idx]}"
    done
    rule
    if [ -n "$failed" ]; then
        warn "Hosts with issues:${failed} — fix and re-run (it resumes)."
        return 1
    fi
    [ "$DRY" = "on" ] || ok "All engine hosts provisioned and started."
    return 0
}

# The remote half of do_open_firewall. Same ports, same idempotence; no-ops when
# the far host has no firewalld.
do_open_firewall_remote() {
    local tgt="$1"
    local nmport="${NM_PORT:-5556}" sslport="${SSL_PORT:-7002}"
    local sipport="${SIP_PORT:-5061}"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ${tgt}: firewall-cmd --add-port ${nmport},${sslport},${sipport}${C_RESET}"
        return 0
    fi
    ssh -o BatchMode=yes "$tgt" "
        command -v firewall-cmd >/dev/null 2>&1 || exit 0
        sudo firewall-cmd --state >/dev/null 2>&1 || exit 0
        for p in ${nmport}/tcp ${sslport}/tcp ${sipport}/tcp ${sipport}/udp; do
            sudo firewall-cmd --permanent --add-port=\$p >/dev/null 2>&1 || true
        done
        sudo firewall-cmd --reload >/dev/null 2>&1 || true
    " 2>/dev/null
}

# ----------------------------------------------------------------------------
# Create + start the standalone Node Manager domain (nmdomain).
#
# Node Manager lives in its OWN basic WebLogic domain so the app/cluster domains
# can be clobbered and recreated (configure writes OverwriteDomain=true) without
# ever taking Node Manager down. This:
#   1. creates the basic domain from Oracle's wls.jar template (offline WLST),
#   2. sets Node Manager to bind ${NM_BIND} on ${NM_PORT} — always SSL, with
#      NM's own permanent blade-nm certificate (ensure_nm_cert),
#   3. starts Node Manager in the background and waits for it to listen.
# Enrolling app domains into this NM (nmEnroll) happens at configure/start time.
# Idempotent: an existing nmdomain is reconfigured + (re)started, not rebuilt.
# ----------------------------------------------------------------------------
do_nmdomain() {
    local mw="$MWHOME" nmdom="$NM_DOMAIN" bind="$NM_BIND" port="$NM_PORT"
    local auser="${ADMIN_USER:-weblogic}" mode="${START_MODE:-dev}"
    [ -n "$mw" ]    || { warn "occas.conf: missing oracle.home"; return 1; }
    [ -n "$nmdom" ] || { warn "occas.conf: missing nm.domain.name"; return 1; }
    [ -n "$port" ]  || { warn "occas.conf: missing nm.listen.port"; return 1; }
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    local tmpl="${mw}/wlserver/common/templates/wls/wls.jar"
    # Always an SSL listener with the dedicated blade-nm cert — there is no
    # plain option (that puts the NM password on the wire) and no env-identity
    # option (that couples the control plane to cert rotation).
    local secure="true"

    info "Node Manager domain '${nmdom}'  →  ${nmhome}"
    log  "  bind=${bind}  port=${port}  SSL with the blade-nm certificate  admin=${auser}"

    # Offline WLST: a basic domain whose only job is to host Node Manager.
    local py
    py="$(cat <<PYEOF
# -*- coding: utf-8 -*-
# WLST (offline) - basic WebLogic domain to host Node Manager only.
readTemplate('${tmpl}')
cd('/Security/base_domain/User/weblogic')
cmo.setName('${auser}')
cmo.setPassword('__PW__')
setOption('ServerStartMode', '${mode}')
setOption('OverwriteDomain', 'true')
writeDomain('${nmhome}')
closeTemplate()
exit()
PYEOF
)"

    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] offline WLST (password redacted):${C_RESET}"
        printf '%s\n' "$py" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] patch ${nmhome}/nodemanager/nodemanager.properties:${C_RESET}"
        log "      ListenAddress=${bind}"
        log "      ListenPort=${port}"
        log "      SecureListener=${secure}"
        log "${C_DIM}  [dry-run] start ${nmhome}/bin/startNodeManager.sh (background); wait for ${bind}:${port}${C_RESET}"
        return 0
    fi

    occas_installed "$mw" || { warn "OCCAS not installed at ${mw} — run the install step first."; return 1; }
    [ -f "$tmpl" ]        || { warn "WLS template not found: ${tmpl}"; return 1; }

    # An existing nmdomain is reconfigured/started as its OWNER (legacy trees
    # are the login user's); a fresh one is created as install.user.
    local IU_USER; IU_USER="$(iu_owner_user "$nmhome")"

    # 1. Create the domain (skip if it already exists — idempotent).
    if [ -d "${nmhome}/config" ]; then
        ok "nmdomain already exists at ${nmhome} — reconfiguring Node Manager, not rebuilding."
    else
        local pw; pw="$(get_admin_pw)" || return 1
        local wlst="${mw}/oracle_common/common/bin/wlst.sh"
        [ -f "$wlst" ] || { warn "wlst.sh not found: ${wlst}"; return 1; }
        local work; work="$(mktemp -d /tmp/nmdom.XXXXXX)"
        ( umask 077; printf '%s\n' "${py/__PW__/$pw}" > "${work}/nmdomain.py" )
        info "Creating basic domain via WLST..."
        local rc=0 jh; jh="$(read_prop "$OCCAS_CONF" java.home)"
        [ -n "$jh" ] && [ ! -d "$jh" ] && jh=""   # stale conf value → ambient JAVA_HOME
        iu_adopt_dir "$work" || { rm -rf "$work"; warn "could not hand ${work} to $(iu_name)."; return 1; }
        as_install_user bash -c "cd '${work}' && ${jh:+JAVA_HOME='${jh}'} '${wlst}' '${work}/nmdomain.py'" || rc=$?
        as_install_user rm -rf "$work"
        [ "$rc" -eq 0 ] || { warn "WLST failed creating nmdomain (rc=${rc})"; return 1; }
        ok "nmdomain created at ${nmhome}"
    fi

    # 2. Point Node Manager at all interfaces on our port/type. nodemanager.properties
    #    is plain key=value, so set_conf_prop updates it in place (appends if absent).
    local nmprops="${nmhome}/nodemanager/nodemanager.properties"
    as_install_user mkdir -p "$(dirname "$nmprops")"
    iu_set_conf_prop "$nmprops" ListenAddress  "$bind"
    iu_set_conf_prop "$nmprops" ListenPort     "$port"
    iu_set_conf_prop "$nmprops" SecureListener "$secure"
    # Use pure-Java process control: OCCAS ships no native Node Manager library
    # for every platform (e.g. aarch64), and the native one fails with
    # UnsatisfiedLinkError. Java-based control is portable and sufficient here.
    iu_set_conf_prop "$nmprops" NativeVersionEnabled false
    # MBean-mode start: NM builds each server's java line from its ServerStart
    # MBean in config.xml (emit_serverstart_block) instead of running
    # startWebLogic.sh — that is what makes Tuning-driven ServerStart.Arguments
    # actually govern the JVM. NOTE: this OCCAS Node Manager honors the
    # PREFIXED key 'weblogic.StartScriptEnabled'; the plain key is silently
    # ignored (cost hours to find — install-occas.sh commit 7428496b). Both are
    # written so a stock-NM future behaves identically.
    iu_set_conf_prop "$nmprops" weblogic.StartScriptEnabled false
    iu_set_conf_prop "$nmprops" StartScriptEnabled false
    # NM's own log + startup .out live OUTSIDE the domain (LOG_DIR, default
    # /var/log/weblogic) so they never clutter it. NM runs as the install user,
    # so the dir must be theirs; /var/log needs sudo to create. Fall back under
    # the domain only if we genuinely can't make the dir.
    local nmlogdir="${LOG_DIR:-/var/log/weblogic}/nodemanager"
    local _lsudo=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && _lsudo="sudo"
    if $_lsudo mkdir -p "$nmlogdir" 2>/dev/null; then
        local _lown; _lown="$(stat -c '%U:%G' "$nmhome" 2>/dev/null || true)"
        [ -n "$_lown" ] && $_lsudo chown "$_lown" "$nmlogdir" 2>/dev/null || true
    else
        warn "could not create ${nmlogdir} — NM logs stay under the domain."
        nmlogdir="${nmhome}/nodemanager"
    fi
    iu_set_conf_prop "$nmprops" LogFile "${nmlogdir}/nodemanager.log"
    # Mixed-state guard: an app domain created BEFORE MBean-mode has no
    # ServerStart MBeans — an MBean-mode NM would then boot its servers without
    # the SIP classpath (no SIP container, nothing obvious in the log). The
    # fresh-install ladder is safe (n runs before c; c writes ServerStart), so
    # this only fires on a re-run against an old domain.
    local appdom_cfg="${DOMAINS_DIR}/${DOMAIN:-}/config/config.xml"
    if [ -n "${DOMAIN:-}" ] && [ -f "$appdom_cfg" ] \
       && ! grep -q '<server-start>' "$appdom_cfg" 2>/dev/null; then
        warn "domain '${DOMAIN}' predates MBean-mode start (no ServerStart in config.xml) — re-run configure BEFORE starting servers, or they will boot without the SIP container."
    fi

    # Node Manager presents its OWN permanent certificate (alias blade-nm, see
    # ensure_nm_cert) — never the env identity, never WebLogic's demo cert. NM
    # is its OWN domain (not config-replicated), so its keystores live in the
    # nmdomain's config/certs -- placed now (the domain already exists from
    # step 1, so no writeDomain clobber). NM encrypts the plaintext passphrases
    # into nodemanager.properties on first start. Refuse rather than fall back:
    # there is no un-certificated Node Manager deployment.
    ensure_nm_cert || { warn "could not produce the Node Manager certificate — refusing to start an un-certificated Node Manager."; return 1; }
    local ksdir nmpw
    ksdir="${nmhome}/config/certs"
    place_nm_keystores "$ksdir" || { warn "could not place Node Manager keystores in ${ksdir}."; return 1; }
    nmpw="${BLADE_NM_KEYSTORE_PASSWORD:-}"; [ -z "$nmpw" ] && [ -f "$WLS_SECRET" ] && nmpw="$(read_prop "$WLS_SECRET" nm.keystore.passphrase)"
    [ -n "$nmpw" ] || { warn "nm.keystore.passphrase missing — cannot give Node Manager its identity."; return 1; }
    iu_set_conf_prop "$nmprops" KeyStores                        CustomIdentityAndCustomTrust
    iu_set_conf_prop "$nmprops" CustomIdentityKeyStoreFileName   "${ksdir}/nm-identity.p12"
    iu_set_conf_prop "$nmprops" CustomIdentityKeyStoreType       PKCS12
    iu_set_conf_prop "$nmprops" CustomIdentityKeyStorePassPhrase "$nmpw"
    iu_set_conf_prop "$nmprops" CustomIdentityAlias              blade-nm
    iu_set_conf_prop "$nmprops" CustomIdentityPrivateKeyPassPhrase "$nmpw"
    iu_set_conf_prop "$nmprops" CustomTrustKeyStoreFileName      "${ksdir}/nm-trust.p12"
    iu_set_conf_prop "$nmprops" CustomTrustKeyStoreType          PKCS12
    iu_set_conf_prop "$nmprops" CustomTrustKeyStorePassPhrase    "$nmpw"
    # The file holds plaintext passphrases until NM's first start encrypts them
    # in place — keep it owner-only.
    as_install_user chmod 600 "$nmprops" 2>/dev/null || true
    ok "Node Manager identity: ${ksdir}/nm-identity.p12 (alias blade-nm, permanent); trust: nm-trust.p12"
    ok "Node Manager bind set: ${bind}:${port} (SecureListener=true, native=off)"

    # The passphrase just written into nodemanager.properties is the one the boot
    # env (WLST_PROPERTIES) must use to open nm-trust.p12. Re-push it to every
    # host that already has a boot env, so a re-run here after a cert rotation
    # can't leave a node's next start on a stale passphrase. No-op on first setup.
    refresh_boot_envs

    # 3. Start Node Manager in the background. If it's already up, offer to
    #    restart it — that's how new domain enrollments / prop changes take effect
    #    (NM reads nodemanager.domains + nodemanager.properties at startup).
    if nm_listening "$port"; then
        if [ "${BLADE_NM_RESTART:-}" = "1" ] || yesno "Node Manager already running on :${port}. Restart it to apply config/enrollment changes?" "N"; then
            stop_nm || { warn "could not stop Node Manager — leaving it running."; return 1; }
        else
            ok "Node Manager left running on :${port}."
            return 0
        fi
    fi
    local nmlog="${nmlogdir}/nodemanager.out"
    info "Starting Node Manager: ${nmhome}/bin/startNodeManager.sh"
    # Node Manager launches every server, so IT sets their identity — it must
    # run as the install user (the redirect too: nmlog is in their domain).
    local pid
    pid="$(as_install_user sh -c "JAVA_HOME='${JAVA_HOME_VAL:-${JAVA_HOME:-}}' nohup '${nmhome}/bin/startNodeManager.sh' > '${nmlog}' 2>&1 & echo \$!")"
    local i=0
    while [ "$i" -lt 30 ]; do
        if nm_listening "$port"; then ok "Node Manager up (pid ${pid}), listening on ${bind}:${port}."; log "  log: ${nmlog}"; return 0; fi
        proc_alive "$pid" || { warn "Node Manager exited early — tail of ${nmlog}:"; tail -n 15 "$nmlog" 2>/dev/null | sed 's/^/    /'; return 1; }
        sleep 1; i=$((i + 1))
    done
    warn "Node Manager didn't reach listening on :${port} within 30s — check ${nmlog}."
    return 1
}
# The profile's JDK java binary, else bare 'java' from PATH.
java_bin() {
    local jh; jh="$(read_prop "$OCCAS_CONF" java.home)"
    if [ -n "$jh" ] && [ -x "${jh}/bin/java" ]; then printf '%s' "${jh}/bin/java"; else printf 'java'; fi
}

# Is OCCAS really installed at this MW_HOME (not just an empty/created dir)?
occas_installed() { [ -d "$1/wlserver" ] && [ -f "$1/inventory/registry.xml" ]; }

# OCCAS version from a real install's registry (same source as bootstrap.sh).
# Echoes e.g. "8.1", or "" if not resolvable.
detect_occas_version() {
    [ -f "$1/inventory/registry.xml" ] || return 0
    # '|| true': no version match must yield "" with success, not a non-zero
    # that set -e + pipefail would turn into an abort in unguarded callers.
    grep -oE 'name="Converged Application Server" version="[0-9]+\.[0-9]+' "$1/inventory/registry.xml" 2>/dev/null \
        | grep -oE '[0-9]+\.[0-9]+$' | head -1 || true
}

# Describe the effective JDK (arg java.home > $JAVA_HOME > PATH). Echoes a
# one-line description; returns 0 real JDK, 1 JRE-only, 2 none found.
jdk_describe() {
    local jh="$1" jbin jdir ver
    [ -z "$jh" ] && jh="${JAVA_HOME:-}"
    if [ -n "$jh" ] && [ -x "${jh}/bin/java" ]; then jbin="${jh}/bin/java"; jdir="$jh"
    elif command -v java >/dev/null 2>&1; then jbin="$(command -v java)"; jdir="$(cd "$(dirname "$jbin")/.." 2>/dev/null && pwd)" || jdir=""
    else echo "no JDK found (set JAVA_HOME or put one on PATH)"; return 2; fi
    # Parse the 'version' line specifically — NOT head -1: with _JAVA_OPTIONS set
    # the JVM prints a "Picked up _JAVA_OPTIONS:" notice to stderr as line 1.
    ver="$("$jbin" -version 2>&1 | grep -i version | head -1 | sed 's/"//g')"
    if [ -n "$jdir" ] && [ -x "${jdir}/bin/javac" ]; then echo "${ver}  (${jdir})"; return 0
    else echo "${ver}  (${jdir:-$jbin}) — JRE only, no javac"; return 1; fi
}

# Major version number reported by a java binary: 8, 11, 17, 21...
# Handles both the old "1.8.0_x" scheme and the modern "21.0.1" scheme.
jdk_major() {
    local raw
    # Read only the quoted version token, and only from a line that has one —
    # skips the "Picked up _JAVA_OPTIONS:" stderr notice the JVM prints when
    # _JAVA_OPTIONS is set (which a bare head -1 would parse into garbage).
    raw="$("$1" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1)" || raw=""
    case "$raw" in 1.*) raw="${raw#1.}" ;; esac   # 1.8.0_201 -> 8.0_201
    printf '%s' "${raw%%.*}"                       # 8.0_201 -> 8 ; 21.0.1 -> 21
}
# Full JDK version token (25.0.4, 21.0.8, 1.8.0_201 -> 8.0_201). Same parse as
# jdk_major but keeps the whole string, so two JDKs of the same release compare
# equal even when their install dirs are named differently (Oracle's own tarball
# unpacks to jdk-25.0.4, a distro build might be jdk-25.0.4-oracle-aarch64).
jdk_version() {
    local raw
    raw="$("$1" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1)" || raw=""
    case "$raw" in 1.*) raw="${raw#1.}" ;; esac
    printf '%s' "$raw"
}

# Recommended JDK major for an OCCAS release — Oracle's certification matrix.
# OCCAS is JDK-version-locked at RUNTIME (this is NOT the build JDK; build.sh
# wants 23+). EDIT this table to match the matrix for the releases you run:
# firm from our own builds are 8.1->11 and 8.3->21; 7.x->8 and 8.2->17 follow
# Oracle's published certs — verify against your release's docs.
occas_jdk_major() {
    case "$1" in
        8.3*|8.4*) echo 21 ;;
        8.2*)      echo 17 ;;
        8.0*|8.1*) echo 11 ;;   # 8.x certifies on 8 OR 11; we standardize on 11
        7.*)       echo 8  ;;
        *)         echo "" ;;   # unknown — caller falls back to "you pick"
    esac
}

# Find an installed JDK of major version $1. Echoes its home, or "" if none.
# Every installed JDK, one per line as "<home>\t<major>". Scans java.dir first
# (it's where downloads land), then the common Linux layouts, then the macOS
# JVM dir; skips the 'current' link (a real versioned home always stands for
# it) and dedups by resolved path so /usr/lib/jvm alias links don't repeat.
list_jdks() {
    local d jbin m r seen=""
    for d in "${JAVA_BASE:-/opt/oracle/java}"/* /usr/lib/jvm/* /usr/java/* /opt/java/* \
             /Library/Java/JavaVirtualMachines/*/Contents/Home; do
        [ -d "$d" ] || continue
        [ "${d##*/}" = "current" ] && continue
        jbin="${d}/bin/java"; [ -x "$jbin" ] || continue
        r="$(readlink -f "$d" 2>/dev/null || printf '%s' "$d")"
        case "$seen" in *"|${r}|"*) continue ;; esac
        seen="${seen}|${r}|"
        m="$(jdk_major "$jbin")"
        [ -n "$m" ] || continue
        printf '%s\t%s\n' "$d" "$m"
    done
    return 0
}

# Can we auto-download Oracle's JDK <major> on THIS host? Oracle serves no-login
# (No-Fee Terms) tarballs only for JDK 17+, only on Linux, only x64/aarch64.
# Quiet 0/1 guard so we offer the download only when it can actually succeed.
jdk_dl_supported() {
    [ "$(uname -s)" = "Linux" ] || return 1
    case "$(uname -m)" in x86_64|amd64|aarch64|arm64) : ;; *) return 1 ;; esac
    case "$1" in 17|21|22|23|24|25) return 0 ;; *) return 1 ;; esac
}

# Download + verify + unpack Oracle's NFTC JDK <major> into <dest> (default
# /usr/lib/jvm; sudo if it isn't writable). On success sets JDK_DL_HOME to the
# resulting JAVA_HOME and returns 0; otherwise warns and returns 1. All chatter
# goes to stderr so callers can run it inline without capturing stdout.
# The sha256 of Oracle's CURRENT 'latest' JDK <major> tarball, from the tiny
# .sha256 sidecar (a stable published fingerprint — NOT HTML scraping). Empty when
# unsupported/unreachable. Lets a caller detect "no new JDK" and skip the ~200MB
# pull by comparing this to the sha recorded on the last download.
jdk_remote_sha() {
    local want="$1" arch
    jdk_dl_supported "$want" || return 0
    case "$(uname -m)" in x86_64|amd64) arch="x64" ;; aarch64|arm64) arch="aarch64" ;; *) return 0 ;; esac
    curl -fsSL --max-time 15 "https://download.oracle.com/java/${want}/latest/jdk-${want}_linux-${arch}_bin.tar.gz.sha256" 2>/dev/null | tr -d '[:space:]'
}

JDK_DL_HOME=""
JDK_DL_SHA=""   # sha256 of the tarball the last download_jdk fetched
download_jdk() {
    JDK_DL_HOME=""; JDK_DL_SHA=""
    local want="$1" dest="${2:-/usr/lib/jvm}"
    jdk_dl_supported "$want" || {
        warn "Oracle only offers no-login downloads for JDK 17+ on Linux x64/aarch64;" >&2
        warn "JDK ${want} on $(uname -s)/$(uname -m) isn't available that way — install it manually." >&2
        return 1
    }
    local arch
    case "$(uname -m)" in x86_64|amd64) arch="x64" ;; aarch64|arm64) arch="aarch64" ;; esac
    local url="https://download.oracle.com/java/${want}/latest/jdk-${want}_linux-${arch}_bin.tar.gz"

    info "Oracle JDK ${want} (${arch}) — No-Fee Terms: https://www.oracle.com/java/technologies/downloads/license/" >&2
    local tmp; tmp="$(mktemp -d /tmp/blade-jdk.XXXXXX)" || return 1
    local tgz="${tmp}/jdk.tar.gz"
    info "downloading ${url}" >&2
    if ! curl -fL --retry 2 --progress-bar "$url" -o "$tgz" >&2; then
        warn "download failed: ${url}" >&2; rm -rf "$tmp"; return 1
    fi

    # Verify against Oracle's .sha256 sidecar (it holds just the hex digest).
    local exp got
    exp="$(curl -fsSL "${url}.sha256" 2>/dev/null | tr -d '[:space:]')"
    if [ -n "$exp" ]; then
        got="$(sha256sum "$tgz" | cut -d' ' -f1)"
        if [ "$exp" != "$got" ]; then
            warn "checksum mismatch — refusing to install (expected ${exp}, got ${got})" >&2
            rm -rf "$tmp"; return 1
        fi
        ok "checksum verified (sha256)" >&2
        JDK_DL_SHA="$exp"
    else
        warn "could not fetch Oracle's .sha256 — skipping verification" >&2
    fi

    # Oracle tarballs unpack to a versioned top dir, e.g. jdk-21.0.8 — read it.
    local top; top="$(tar tzf "$tgz" 2>/dev/null | head -1 | cut -d/ -f1)"
    [ -n "$top" ] || { warn "could not read tarball contents" >&2; rm -rf "$tmp"; return 1; }
    local home="${dest}/${top}"

    # /usr/lib/jvm is usually root-owned — use sudo only if we can't write it.
    local SUDO=""
    if [ ! -w "$dest" ] && [ "$(id -u)" -ne 0 ]; then
        if command -v sudo >/dev/null 2>&1; then SUDO="sudo"
        else warn "${dest} is not writable and sudo is unavailable — run as root." >&2; rm -rf "$tmp"; return 1; fi
    fi
    if [ -x "${home}/bin/java" ]; then
        ok "JDK already installed at ${home}" >&2
    else
        $SUDO mkdir -p "$dest" && $SUDO tar xzf "$tgz" -C "$dest" \
            || { warn "extract into ${dest} failed" >&2; rm -rf "$tmp"; return 1; }
    fi
    rm -rf "$tmp"
    [ -x "${home}/bin/java" ] || { warn "no bin/java under ${home} after extract" >&2; return 1; }
    ok "JDK ${want} ready at ${home}" >&2
    JDK_DL_HOME="$home"
    return 0
}

# Ensure a JDK of <major> exists under JAVA_BASE and echo its path. Reuses one
# that's already installed; otherwise downloads Oracle's latest of that major.
# All chatter goes to stderr so the path is the only thing on stdout.
ensure_jdk() {
    local maj="$1" line path=""
    while IFS= read -r line; do
        [ "${line##*$'\t'}" = "$maj" ] && { path="${line%%$'\t'*}"; break; }
    done < <(list_jdks)
    if [ -z "$path" ] && jdk_dl_supported "$maj"; then
        download_jdk "$maj" "${JAVA_BASE:-/opt/oracle/java}" >&2 && path="$JDK_DL_HOME"
    fi
    printf '%s' "$path"
}

# Point JAVA_BASE/<name> at a real JDK path (a JDK upgrade is then a link flip).
# Echoes the link on success, the raw path if the link couldn't be made.
link_jdk() {
    local real="$1" name="$2"
    local link="${JAVA_BASE:-/opt/oracle/java}/${name}"
    real="$(readlink -f "$real" 2>/dev/null || printf '%s' "$real")"
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] ln -sfn ${real} ${link}${C_RESET}" >&2; printf '%s' "$link"; return 0; fi
    if { mkdir -p "$(dirname "$link")" && ln -sfn "$real" "$link"; } 2>/dev/null \
       || { sudo mkdir -p "$(dirname "$link")" && sudo ln -sfn "$real" "$link"; } 2>/dev/null; then
        ok "${link} -> ${real}" >&2; printf '%s' "$link"
    else
        warn "could not create ${link} — using ${real} directly." >&2; printf '%s' "$real"
    fi
}

# ----------------------------------------------------------------------------
# Create the Linux install user + group that own OCCAS (defaults oracle:oinstall).
# Idempotent; needs root or passwordless sudo for the actual creates.
# ----------------------------------------------------------------------------
do_makeuser() {
    local user="${INSTALL_USER:-oracle}" grp="${INV_GRP:-oinstall}"
    local uid="${INSTALL_UID:-54321}" gid="${INSTALL_GID:-54321}"
    # install.uid/gid pin the NUMERIC ids so every host in the cluster agrees --
    # rsync -a carries numbers, not names (see phase_occas). Default 54321 is
    # Oracle's standard oracle/oinstall id; blank in the conf falls back to it.
    local gflag="" uflag=""
    [ -n "$gid" ] && gflag="-g ${gid}"
    [ -n "$uid" ] && uflag="-u ${uid}"
    info "Install user/group: ${user}${uid:+(${uid})}:${grp}${gid:+(${gid})}"
    [ "$(uname -s)" = "Linux" ] || { warn "user/group creation is Linux-only (host prep)."; return 0; }
    local SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"

    if [ "$DRY" = "on" ]; then
        getent group "$grp" >/dev/null 2>&1 \
            && log "${C_DIM}  [dry-run] group ${grp} already exists${C_RESET}" \
            || log "${C_DIM}  [dry-run] ${SUDO:+sudo }groupadd ${gflag} ${grp}${C_RESET}"
        id "$user" >/dev/null 2>&1 \
            && log "${C_DIM}  [dry-run] user ${user} already exists${C_RESET}" \
            || log "${C_DIM}  [dry-run] ${SUDO:+sudo }useradd ${uflag} -g ${grp} -m ${user}${C_RESET}"
        return 0
    fi

    # group
    if getent group "$grp" >/dev/null 2>&1; then
        ok "group '${grp}' already exists."
        # A pre-existing group with a DIFFERENT gid than the one pinned is exactly
        # the mismatch install.gid exists to prevent — say so rather than let the
        # engine rsync bake it in.
        local havegid; havegid="$(getent group "$grp" | cut -d: -f3)"
        [ -n "$gid" ] && [ "$havegid" != "$gid" ] \
            && warn "group '${grp}' is gid ${havegid} here, but install.gid=${gid}. Fix one or the other before provisioning engines."
    elif $SUDO groupadd $gflag "$grp"; then ok "created group '${grp}'${gid:+ (gid ${gid})}."
    else warn "could not create group '${grp}' (need root?)."; return 1; fi

    # user (+ ensure membership in the group)
    if id "$user" >/dev/null 2>&1; then
        ok "user '${user}' already exists."
        local haveuid; haveuid="$(id -u "$user")"
        [ -n "$uid" ] && [ "$haveuid" != "$uid" ] \
            && warn "user '${user}' is uid ${haveuid} here, but install.uid=${uid}. Fix one or the other before provisioning engines."
        if id -nG "$user" 2>/dev/null | tr ' ' '\n' | grep -qx "$grp"; then ok "user '${user}' is in '${grp}'."
        elif $SUDO usermod -aG "$grp" "$user"; then ok "added '${user}' to '${grp}'."
        else warn "could not add '${user}' to '${grp}'."; fi
    elif $SUDO useradd $uflag -g "$grp" -m "$user"; then ok "created user '${user}'${uid:+ (uid ${uid})} (primary group ${grp})."
    else warn "could not create user '${user}' (need root?)."; return 1; fi
    return 0
}

# ----------------------------------------------------------------------------
# Create the install dirs (MW_HOME + Oracle inventory) and chown them to the
# install user:group. Idempotent; needs root or passwordless sudo. A populated
# MW_HOME is left untouched (we don't recursively chown an existing install).
# ----------------------------------------------------------------------------
do_makedirs() {
    local mw="$MWHOME" inv="${INV_LOC:-/opt/oracle/oraInventory}"
    local user="${INSTALL_USER:-oracle}" grp="${INV_GRP:-oinstall}"
    [ -n "$mw" ] || { warn "occas.conf: missing oracle.home (MW_HOME)"; return 1; }
    info "Install dirs: ${mw}  +  ${inv}   (owner ${user}:${grp})"
    [ "$(uname -s)" = "Linux" ] || { warn "dir creation is Linux-only (host prep)."; return 0; }
    local SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"

    if [ "$DRY" = "on" ]; then
        if [ -d "${mw}/wlserver" ]; then log "${C_DIM}  [dry-run] MW_HOME populated — leave as-is${C_RESET}"
        else log "${C_DIM}  [dry-run] ${SUDO:+sudo }mkdir -p ${mw} && ${SUDO:+sudo }chown -R ${user}:${grp} ${mw}${C_RESET}"; fi
        log "${C_DIM}  [dry-run] ${SUDO:+sudo }mkdir -p ${inv} && ${SUDO:+sudo }chown -R ${user}:${grp} ${inv}${C_RESET}"
        return 0
    fi

    if [ "$mw" = "${OCCAS_BASE:-}/current" ]; then
        : # 'current' is the stable symlink the install step publishes — it must
          # NEVER be a real directory (mkdir'ing it breaks the install's ln -sfn).
          # Only its parent (OCCAS_BASE, owned just below) has to exist.
    elif [ -d "${mw}/wlserver" ]; then
        ok "MW_HOME already populated at ${mw} — leaving ownership as-is."
    elif $SUDO mkdir -p "$mw" && $SUDO chown -R "${user}:${grp}" "$mw"; then
        ok "created + chowned ${mw}."
    else
        warn "could not set up ${mw} (need root?)."; return 1
    fi
    # The install user must own the BASE dir, not just the home inside it:
    # publishing 'current' is a symlink written here, and patching copies a whole
    # home in beside it. Both are unprivileged operations only if this is owned.
    if [ -n "${OCCAS_BASE:-}" ]; then
        $SUDO mkdir -p "$OCCAS_BASE" && $SUDO chown "${user}:${grp}" "$OCCAS_BASE" \
            && ok "chowned ${OCCAS_BASE} (holds the versioned homes + the 'current' link)." \
            || warn "could not chown ${OCCAS_BASE} — the symlink flip and patching will need sudo."
    fi
    # Same deal for the JDKs: 'current' is a link written here and new JDKs
    # unpack in beside it -- unprivileged operations only if this is owned.
    if [ -n "${JAVA_BASE:-}" ]; then
        $SUDO mkdir -p "$JAVA_BASE" && $SUDO chown "${user}:${grp}" "$JAVA_BASE" \
            && ok "chowned ${JAVA_BASE} (holds the JDKs + the 'current' link)." \
            || warn "could not chown ${JAVA_BASE} — JDK flips will need sudo."
    fi
    if [ -n "${DOMAINS_DIR:-}" ]; then
        $SUDO mkdir -p "$DOMAINS_DIR" && $SUDO chown -R "${user}:${grp}" "$DOMAINS_DIR" \
            && ok "created + chowned ${DOMAINS_DIR} (domains live outside the Oracle home)." \
            || warn "could not set up ${DOMAINS_DIR}."
    fi
    # TLS keystores are NOT pre-created here any more: they live in the domain's
    # config/certs, which configure/nmdomain create and populate after WLST writes
    # the domain (pre-creating it would spawn the domain dir and trip the
    # overwrite guard). DOMAINS_DIR above already covers the parent ownership.
    if $SUDO mkdir -p "$inv" && $SUDO chown -R "${user}:${grp}" "$inv"; then
        ok "created + chowned ${inv}."
    else
        warn "could not set up ${inv} (need root?)."; return 1
    fi
    return 0
}

# Raise the open-files / process ulimits for the install user + root (dashboard: L).
# WebLogic wants >=4096 open files; the distro's 1024 SOFT default is a select()
# FD_SETSIZE relic (fds >= 1024 overflow that fixed bitmap), kept conservative to
# catch fd leaks — it's a soft cap meant to be raised for servers, not a ceiling.
# Boot-service NM/servers already get LimitNOFILE from their systemd unit; this
# drop-in covers the install, WLST, and the manually-started n/s runs, which
# inherit a login/sudo session's limit. It applies to NEW sessions only.
do_raise_limits() {
    [ "$(uname -s)" = "Linux" ] || { warn "ulimit tuning is Linux-only (host prep)."; return 0; }
    local u; u="$(read_prop "$OCCAS_CONF" install.user)"; u="${u:-oracle}"
    local drop="/etc/security/limits.d/99-blade-nofile.conf"
    # Hard = the kernel per-process ceiling (fs.nr_open, usually 1048576). Soft is
    # generous but deliberately NOT the ceiling: some fork-heavy tools close every
    # fd up to the soft limit, so an enormous soft makes them crawl.
    local hard; hard="$(cat /proc/sys/fs/nr_open 2>/dev/null || echo 1048576)"
    local soft=65536; [ "$soft" -gt "$hard" ] && soft="$hard"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] write ${drop}: ${u}/root nofile soft=${soft} hard=${hard}; ${u} nproc 65536${C_RESET}"
        return 0
    fi
    local SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"
    if printf '%s\n' \
        "# BLADE host prep — raise open files / processes for WebLogic (OCCAS)." \
        "${u} soft nofile ${soft}" \
        "${u} hard nofile ${hard}" \
        "root soft nofile ${soft}" \
        "root hard nofile ${hard}" \
        "${u} soft nproc 65536" \
        "${u} hard nproc 65536" \
        | $SUDO tee "$drop" >/dev/null && $SUDO chmod 644 "$drop"; then
        ok "wrote ${drop} — nofile soft=${soft} hard=${hard} for ${u} & root."
    else
        warn "could not write ${drop} (need root?)."; return 1
    fi
    log "  ${C_DIM}${u}'s sudo sessions — which run the install and the servers — get it immediately; a fresh preflight will confirm it. Your own login shell is unaffected.${C_RESET}"
    log "  ${C_DIM}Boot-service NM/servers already carry LimitNOFILE=65535 in their unit.${C_RESET}"
    return 0
}

# ----------------------------------------------------------------------------
# Fetch the OCCAS media from Oracle eDelivery (RUN: d).
#
# Headless, mirroring what Oracle's own generated wget.sh does. Two separate
# short-lived credentials are involved and NEITHER can be minted from a CLI:
#   * the per-file URLs carry a license-acceptance token, good ~8 hours
#   * each request needs the dialog's access token as a Bearer header, ~1 hour
# So there is a one-time browser step; after that this resumes and re-runs
# cheaply. No-op once the installer jar exists.
# ----------------------------------------------------------------------------

# Find a downloaded occas_generic.jar and point INSTALLER_JAR at it. Oracle nests
# the media, so search a few levels down rather than assuming a layout.
adopt_installer() {
    local found
    found="$(find "$DL_DIR" -maxdepth 4 -name occas_generic.jar 2>/dev/null | head -1)"
    [ -n "$found" ] || return 1
    INSTALLER_JAR="$found"
    return 0
}

do_download() {
    [ -n "$PROFILE_DIR" ] || { warn "no profile loaded."; return 1; }
    # Where the media lands: explicit conf, else next to the configured jar,
    # else a per-user default that is always writable.
    DL_DIR="$(read_prop "$OCCAS_CONF" download.dir)"; DL_DIR="${DL_DIR/#\~/$HOME}"
    if [ -z "$DL_DIR" ]; then
        # Media lives beside the install, not under anyone's $HOME — the OUI
        # later runs as the install user, who cannot read a 0700 home dir.
        [ -n "$INSTALLER_JAR" ] && DL_DIR="$(dirname "$INSTALLER_JAR")" \
            || DL_DIR="$(dirname "${OCCAS_BASE:-/opt/oracle/occas}")/media"
    fi
    URLS_FILE="${BLADE_HOME}/${NAME}.urls"

    # Already installed beats already downloaded. Rebuilding a domain on a box
    # that has the product is the common case, and there is no reason to send
    # someone back to eDelivery for media they will never open.
    if [ -d "${MWHOME}/wlserver" ]; then
        ok "OCCAS is already installed at ${MWHOME} — no media needed."
        return 0
    fi
    if [ -n "$INSTALLER_JAR" ] && [ -f "$INSTALLER_JAR" ]; then
        ok "Installer already present: ${INSTALLER_JAR} — nothing to download."
        return 0
    fi
    if adopt_installer; then
        ok "Installer already downloaded: ${INSTALLER_JAR}"
        set_conf_prop "$OCCAS_CONF" installer.jar "$INSTALLER_JAR"
        return 0
    fi

    if [ ! -f "$URLS_FILE" ]; then
        if [ "$DRY" = "on" ]; then
            log "${C_DIM}  [dry-run] no ${URLS_FILE} — would ask for Oracle's wget.sh${C_RESET}"
            return 0
        fi
        log ""
        log "Getting the OCCAS media takes a ONE-TIME browser step (Oracle's license click):"
        log "  1. sign in at https://edelivery.oracle.com and cart the OCCAS release"
        log "     (search 'Oracle Communications Converged Application Server'),"
        log "     pick the platform, accept the license"
        log "  2. click 'WGET Options' -> 'Download wget.sh'"
        log "     (browsing on another machine is fine — scp it to this box)"
        log "  3. in the same dialog, click 'Generate Token' -> Copy — you'll paste it here"
        local wsh=""
        [ -t 0 ] && ask wsh "Path to that wget.sh (Enter to stop)" ""
        [ -n "$wsh" ] || { warn "No wget.sh yet — do the browser step above, then re-run the download step; it resumes."; return 1; }
        wsh="${wsh/#\~/$HOME}"
        [ -f "$wsh" ] || { warn "Not found: ${wsh}"; return 1; }
        cp "$wsh" "$URLS_FILE" || { warn "could not save ${URLS_FILE}."; return 1; }
        ok "Saved as ${URLS_FILE} (inside the gitignored profile) — URLs are good for ~8 hours."
    fi

    # Accept Oracle's whole wget.sh or a bare list of URLs.
    local urls=() u f
    while IFS= read -r u; do urls+=("$u"); done \
        < <(grep -oE 'https://edelivery\.oracle\.com/osdc/softwareDownload\?[^"'"'"'[:space:]]+' "$URLS_FILE" | sort -u)
    [ "${#urls[@]}" -ge 1 ] || { warn "No eDelivery softwareDownload URLs found in ${URLS_FILE}."; return 1; }

    info "Download ${#urls[@]} file(s) from eDelivery -> ${DL_DIR}"
    if [ "$DRY" = "on" ]; then
        for u in "${urls[@]}"; do f="${u##*fileName=}"; f="${f%%&*}"; log "${C_DIM}  [dry-run] ${f}${C_RESET}"; done
        log "${C_DIM}  [dry-run] curl each URL with the Bearer access token, unzip into ${DL_DIR}${C_RESET}"
        return 0
    fi
    command -v curl  >/dev/null || { warn "curl not found."; return 1; }
    command -v unzip >/dev/null || { warn "unzip not found."; return 1; }
    if ! mkdir -p "$DL_DIR" 2>/dev/null || [ ! -w "$DL_DIR" ]; then
        # /opt/... needs root once; hand the dir to the invoker — downloads run
        # as the invoker, the install user only ever READS the media.
        if command -v sudo >/dev/null 2>&1 \
           && sudo mkdir -p "$DL_DIR" 2>/dev/null && sudo chown "$(id -un)" "$DL_DIR" 2>/dev/null; then
            ok "created ${DL_DIR} (owner $(id -un))."
        else
            warn "Can't write to ${DL_DIR} — downloading to ${HOME}/occas-media instead (the install step will stage a copy)."
            DL_DIR="${HOME}/occas-media"; mkdir -p "$DL_DIR" || { warn "Can't create ${DL_DIR}."; return 1; }
        fi
    fi

    local token="${BLADE_EDELIVERY_TOKEN:-}" dest zips=()
    for u in "${urls[@]}"; do
        f="${u##*fileName=}"; f="${f%%&*}"
        { [ -n "$f" ] && [ "$f" != "$u" ]; } || { warn "Could not parse fileName= from: ${u}"; return 1; }
        dest="${DL_DIR}/${f}"
        if [ -f "$dest" ] && unzip -tqq "$dest" >/dev/null 2>&1; then
            ok "${f} — already downloaded and intact."
        else
            if [ -z "$token" ]; then
                [ -t 0 ] || { warn "No access token — set \$BLADE_EDELIVERY_TOKEN (browser: WGET Options -> 'Generate Token')."; return 1; }
                ask token "Access token ('Generate Token' in the WGET Options dialog, valid ~1 h)" ""
                [ -n "$token" ] || { warn "No access token given."; return 1; }
            fi
            info "Fetching ${f} …"
            # Token goes in via --config on stdin so it stays out of 'ps'.
            # -A: Akamai in front of eDelivery sniffs the User-Agent — curl's
            # default and even custom Mozilla strings get 403; a wget UA (what
            # Oracle's own script sends) passes. Verified 2026-07-15.
            if ! curl -f -L --progress-bar -A "Wget/1.21" -C - -o "$dest" --config - "$u" <<EOF
header = "Authorization: Bearer ${token}"
EOF
            then
                warn "Download failed: ${f} — 401/403 means the access token (~1 h) or the URLs (~8 h) expired."
                warn "Re-run the download step with a fresh token; if it still fails, delete ${URLS_FILE} for a fresh wget.sh."
                return 1
            fi
            if ! unzip -tqq "$dest" >/dev/null 2>&1; then
                if head -c 1024 "$dest" | grep -qi "<html"; then
                    rm -f "$dest"
                    warn "eDelivery sent an HTML page instead of ${f} — token or URLs expired."; return 1
                fi
                warn "${dest} is not a valid zip (truncated?) — delete it and re-run."; return 1
            fi
        fi
        zips+=("$dest")
    done

    info "Unpacking into ${DL_DIR} …"
    local z unpacked=" "
    for z in "${zips[@]}"; do unzip -oq "$z" -d "$DL_DIR"; unpacked="${unpacked}${z} "; done
    # Oracle nests the media (V*.zip -> OCCAS<ver>GA.zip -> occas_generic.jar):
    # keep unpacking whatever zips fall out until the installer shows up.
    local inner found_new
    while ! find "$DL_DIR" -maxdepth 4 -name occas_generic.jar 2>/dev/null | grep -q .; do
        found_new=false
        while IFS= read -r inner; do
            case "$unpacked" in *" ${inner} "*) continue ;; esac
            info "Unpacking nested $(basename "$inner") …"
            unzip -oq "$inner" -d "$DL_DIR"; unpacked="${unpacked}${inner} "; found_new=true
        done < <(find "$DL_DIR" -maxdepth 4 -name '*.zip' 2>/dev/null)
        [ "$found_new" = true ] || break
    done

    if adopt_installer; then
        ok "Installer ready: ${INSTALLER_JAR}"
        set_conf_prop "$OCCAS_CONF" installer.jar "$INSTALLER_JAR"
        local v; v="$(installer_version "$INSTALLER_JAR" 2>/dev/null)"
        [ -n "$v" ] && { OCCAS_VERSION="$v"; set_conf_prop "$OCCAS_CONF" occas.version "$v"; ok "OCCAS version ${v}"; }
    else
        warn "No occas_generic.jar in the downloaded media — check what ${URLS_FILE} points at."
        return 1
    fi
}

# Drop the benign JVM/OUI warnings that JDK 24+ prints around Oracle's installer:
# it relaunches a child JVM we can't pass flags to, and its JNI use trips the
# "restricted method / native-access" notes (JEP 472) plus a "-mx deprecated"
# note. Filter EXACTLY those lines — progress, success, and any real error pass
# through untouched. `|| true` so the filter itself never fails the pipe; the
# installer's own exit status still governs success (via pipefail).
strip_jdk_noise() {
    grep -vE 'A restricted method in java\.lang\.System has been called|System::load has been called by|Use --enable-native-access=ALL-UNNAMED|Restricted methods will be blocked in a future release|-mx option is deprecated and may be removed' || true
}

# Point the stable 'current' symlink (<link>) at a versioned home (<target>), as
# the owner of the base dir. Robust two ways: it runs privileged so an oracle-owned
# base doesn't EACCES, and if a stray REAL directory sits at <link> (an earlier
# bug, or a mkdir'd path) it clears it first — otherwise `ln -sfn` would drop the
# link INSIDE that dir instead of replacing it. Never removes a real install.
publish_current_link() {
    local target="$1" link="$2"
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] ln -sfn ${target} ${link}${C_RESET}"; return 0; fi
    local IU_USER; IU_USER="$(iu_owner_user "$(dirname "$link")")"
    if [ -d "$link" ] && [ ! -L "$link" ]; then
        if [ -d "${link}/wlserver" ]; then
            warn "${link} is a real directory holding an install — refusing to replace it. Investigate."; return 1
        fi
        as_install_user rm -rf "$link" 2>/dev/null || sudo rm -rf "$link" 2>/dev/null \
            || { warn "could not clear the stray directory at ${link}."; return 1; }
    fi
    as_install_user ln -sfn "$target" "$link" 2>/dev/null \
        || sudo ln -sfn "$target" "$link" 2>/dev/null \
        || { warn "could not create ${link} — nothing will resolve the Oracle home."; return 1; }
    ok "${link} -> $(basename "$target")"
    return 0
}

# ----------------------------------------------------------------------------
# Step 1 — silent product install (java -jar <installer> -silent ...).
# Idempotent: a populated MW_HOME means it's done (safe on a shared filesystem).
# ----------------------------------------------------------------------------
do_install() {
    local mwhome installer inv_loc inv_grp itype
    mwhome="$(read_prop "$OCCAS_CONF" oracle.home)"
    installer="${BLADE_OCCAS_INSTALLER:-$(read_prop "$OCCAS_CONF" installer.jar)}"
    inv_loc="$(read_prop "$OCCAS_CONF" inventory.loc)"
    inv_grp="$(read_prop "$OCCAS_CONF" inventory.group)"; inv_grp="${inv_grp:-oinstall}"
    itype="$(read_prop "$OCCAS_CONF" install.type)"; itype="${itype:-Complete with Examples}"
    [ -n "$mwhome" ] || { warn "occas.conf: missing oracle.home"; return 1; }

    # oracle.home is the 'current' symlink. Install into the real versioned
    # directory beside it and publish it by pointing the link -- that is what
    # makes a later patch an atomic flip instead of an in-place edit.
    local real="${OCCAS_BASE}/${OCCAS_VER:-${OCCAS_VERSION:-8.3.0}}"
    if [ -d "${mwhome}/wlserver" ]; then
        ok "OCCAS already present at ${mwhome} — skipping install."; return 0
    fi
    if [ -d "${real}/wlserver" ]; then
        ok "OCCAS already present at ${real} — pointing ${mwhome} at it."
        publish_current_link "$real" "$mwhome" || return 1
        return 0
    fi
    mwhome="$real"
    [ -n "$installer" ] || { warn "occas.conf: missing installer.jar"; return 1; }
    [ -n "$inv_loc" ]   || { warn "occas.conf: missing inventory.loc"; return 1; }
    info "Silent install -> ${mwhome}  (installer: ${installer})"

    local rsp inv
    rsp="$(mktemp /tmp/occas-install.XXXXXX.rsp)"
    inv="$(mktemp /tmp/occas-oraInst.XXXXXX.loc)"
    cat > "$rsp" <<EOF
[ENGINE]
Response File Version=1.0.0.0.0

[GENERIC]
DECLINE_AUTO_UPDATES=true
ORACLE_HOME=${mwhome}
INSTALL_TYPE=${itype}
EOF
    cat > "$inv" <<EOF
inventory_loc=${inv_loc}
inst_group=${inv_grp}
EOF
    # Paths only, no secrets — and the OUI runs as the install user, who must
    # be able to read them (mktemp creates 0600, invoker-owned).
    chmod 644 "$rsp" "$inv"

    if [ "$DRY" = "on" ]; then
        local runas=""; iu_switching && runas="sudo -H -u $(iu_name) "
        log "${C_DIM}  [dry-run] response file:${C_RESET}"; sed 's/^/    /' "$rsp"
        log "${C_DIM}  [dry-run] oraInst.loc:${C_RESET}";   sed 's/^/    /' "$inv"
        log "${C_DIM}  [dry-run] ${runas}$(java_bin) -jar ${installer} -silent -responseFile <rsp> -invPtrLoc <loc> -ignoreSysPrereqs${C_RESET}"
        rm -f "$rsp" "$inv"; return 0
    fi
    if [ ! -f "$installer" ]; then rm -f "$rsp" "$inv"; warn "installer.jar not found: ${installer}"; return 1; fi
    # The install user cannot read into the invoker's $HOME (0700 on OL8+) —
    # where older profiles keep the media. Stage the jar beside the install
    # once and repoint the profile at the copy.
    if iu_switching && ! as_install_user test -r "$installer"; then
        local mdir staged
        mdir="$(dirname "${OCCAS_BASE:-/opt/oracle/occas}")/media"
        staged="${mdir}/$(basename "$installer")"
        info "Staging the installer where $(iu_name) can read it: ${staged}"
        if sudo mkdir -p "$mdir" && sudo cp "$installer" "$staged" \
           && sudo chmod 755 "$mdir" && sudo chmod 644 "$staged"; then
            set_conf_prop "$OCCAS_CONF" installer.jar "$staged"
            ok "staged; installer.jar now points at ${staged}."
            installer="$staged"
        else
            rm -f "$rsp" "$inv"; warn "could not stage ${installer} for $(iu_name)."; return 1
        fi
    fi
    # 2>&1 so the JDK warnings (on stderr) reach the filter; pipefail + the if
    # keep the installer's real exit as the pass/fail signal (grep never fails it).
    if as_install_user "$(java_bin)" -jar "$installer" -silent -responseFile "$rsp" -invPtrLoc "$inv" -ignoreSysPrereqs 2>&1 | strip_jdk_noise; then
        rm -f "$rsp" "$inv"; ok "Product installed at ${mwhome}"
    else
        rm -f "$rsp" "$inv"; warn "silent install failed"; return 1
    fi
    # Publish the freshly installed version through the stable link. Everything
    # downstream -- the domain, the units, Node Manager -- resolves this path, so
    # a patch later is a flip of this one symlink.
    local link="${OCCAS_BASE}/current"
    [ "$mwhome" != "$link" ] && { publish_current_link "$mwhome" "$link" || return 1; }
}

# Emit the WLST that adds the optional static test engine as a configured member
# of BEA_ENGINE_TIER_CLUST (a configured server doesn't inherit the dynamic
# template, so its sip/sips channels are added by hand). Arg: name:mach:listen:sip:sips
# The env's TLS source keystores live in its per-env folder, ~/.blade/<env>/,
# with the log and URL list — never scattered in the repo checkout. certs.dir in
# the conf overrides. This is the ONE definition install.sh, certs.sh, make-certs.sh,
# install-ssl.sh and deploy.sh must agree on — keep them in step.
certs_source_dir() {
    local d; d="$(read_prop "$OCCAS_CONF" certs.dir 2>/dev/null)"; d="${d/#\~/$HOME}"
    [ -n "$d" ] && { printf '%s' "$d"; return 0; }
    blade_certs_dir_for_conf "$DEPLOY_CONF"
}

# Generate (or import) the env's TLS source keystores into ~/.blade/<env> and
# persist the passphrases into the conf. Called BEFORE emit_tls_block, which
# reads those passphrases. There is deliberately NO demo-cert fallback anywhere
# in BLADE -- callers that require TLS fail instead of letting WebLogic drop back
# to its demo identity/trust. Returns 0 only when the source p12 exists.
ensure_certs_source() {
    [ "$DRY" = "on" ] && return 0
    local envname srcp12
    envname="$(basename "${DEPLOY_CONF%.conf}")"
    srcp12="$(certs_source_dir)/blade-identity.p12"
    if [ ! -f "$srcp12" ]; then
        if [ "${CERT_SOURCE:-generate}" = supply ]; then
            info "No certificates for '${envname}' yet — importing the supplied cert (the TLS certificate step) …"
            "${SCRIPT_DIR}/certs.sh" "$DEPLOY_CONF" import || warn "certificate import returned an error"
        else
            info "No certificates for '${envname}' yet — generating a self-signed CA (the TLS certificate step) …"
            ensure_tls_passphrases   # certs must be built with the passphrases the config records
            "${SCRIPT_DIR}/tls/make-certs.sh" "$DEPLOY_CONF" || warn "make-certs returned an error"
        fi
    fi
    [ -f "$srcp12" ]
}

# The Node Manager certificate is its OWN permanent PKI, separate from the env
# identity on purpose. The NM channel is a closed loop — the AdminServer's
# built-in NM client and our WLST are the only callers, the real authentication
# is the NM username/password, and hostname verification is off — so TLS here
# is confidentiality only: the ssh-host-key model. A self-signed ~100-year cert
# means rotating or replacing the real TLS identity (including a 90-day Let's
# Encrypt lease on the 'sup' path) can NEVER take down the control plane — and
# the control plane is exactly the channel you'd need to fix a botched rotation.
#
# Generates once into ~/.blade/<env>/: nm-identity.p12 (alias blade-nm),
# nm-cert.pem, nm-trust.p12. Also idempotently imports the cert into an
# existing blade-trust.p12 — the AdminServer's built-in NM client validates NM
# against the DOMAIN trust store, not nm-trust.p12 — and refreshes the app
# domain's placed copy. make-certs.sh / certs.sh import re-add the entry on
# every trust rebuild. Returns 0 only when nm-identity.p12 exists.
ensure_nm_cert() {
    [ "$DRY" = "on" ] && return 0
    local envname outdir nmpw keytool jh
    envname="$(basename "${DEPLOY_CONF%.conf}")"
    outdir="$(certs_source_dir)"
    # Its own passphrase, minted like the tls.* three — the NM step depends on
    # nothing from the TLS phase.
    nmpw="${BLADE_NM_KEYSTORE_PASSWORD:-}"
    [ -z "$nmpw" ] && [ -f "$WLS_SECRET" ] && nmpw="$(read_prop "$WLS_SECRET" nm.keystore.passphrase)"
    local _nm_regen=0
    if [ -z "$nmpw" ]; then
        nmpw="$(gen_pass)"
        write_secret "$WLS_SECRET" nm.keystore.passphrase "$nmpw" \
            || { warn "could not persist nm.keystore.passphrase."; return 1; }
        ok "generated the Node Manager keystore passphrase (saved to the config)"
        # A freshly minted passphrase can't open a keystore sealed under the old one,
        # so any existing nm-identity.p12 is orphaned (its passphrase is now lost).
        # Drop it and everything derived from it so they regenerate to match —
        # otherwise the export below fails with "could not export nm-cert.pem".
        # _nm_regen also tells the blade-trust step to swap out the stale blade-nm.
        _nm_regen=1
        rm -f "${outdir}/nm-identity.p12" "${outdir}/nm-cert.pem" "${outdir}/nm-trust.p12"
    fi
    jh="$(read_prop "$OCCAS_CONF" java.home)"
    local kt="${jh:+${jh}/bin/}keytool"
    command -v "$kt" >/dev/null 2>&1 || kt="keytool"
    command -v "$kt" >/dev/null 2>&1 || { warn "keytool not found (need a JDK) — cannot generate the Node Manager certificate."; return 1; }
    mkdir -p "$outdir"
    if [ ! -f "${outdir}/nm-identity.p12" ]; then
        info "Generating the permanent Node Manager certificate (self-signed, alias blade-nm, ~100 years)"
        "$kt" -genkeypair -alias blade-nm -keyalg RSA -keysize 2048 \
            -dname "CN=blade-nodemanager" -validity 36500 \
            -keystore "${outdir}/nm-identity.p12" -storetype PKCS12 \
            -storepass "$nmpw" -keypass "$nmpw" \
            || { warn "keytool failed generating nm-identity.p12."; return 1; }
    fi
    # (Re)export the cert and (re)build the one-entry trust store — cheap, and
    # self-healing if either derived file went missing.
    "$kt" -exportcert -rfc -alias blade-nm -keystore "${outdir}/nm-identity.p12" \
        -storepass "$nmpw" -file "${outdir}/nm-cert.pem" >/dev/null 2>&1 \
        || { warn "could not export nm-cert.pem."; return 1; }
    if ! "$kt" -list -alias blade-nm -keystore "${outdir}/nm-trust.p12" \
            -storepass "$nmpw" >/dev/null 2>&1; then
        rm -f "${outdir}/nm-trust.p12"
        "$kt" -importcert -noprompt -alias blade-nm -file "${outdir}/nm-cert.pem" \
            -keystore "${outdir}/nm-trust.p12" -storetype PKCS12 -storepass "$nmpw" >/dev/null \
            || { warn "could not build nm-trust.p12."; return 1; }
    fi
    chmod 600 "${outdir}/nm-identity.p12" "${outdir}/nm-trust.p12" 2>/dev/null || true
    # Existing installs: get the NM cert into the env trust store NOW (future
    # rebuilds keep it — make-certs.sh / certs.sh both import nm-cert.pem).
    local trpw="${BLADE_TRUST_PASSWORD:-}"
    [ -z "$trpw" ] && [ -f "$WLS_SECRET" ] && trpw="$(read_prop "$WLS_SECRET" tls.trust.passphrase)"
    if [ -f "${outdir}/blade-trust.p12" ] && [ -n "$trpw" ]; then
        # If the NM cert was regenerated, the blade-nm already in blade-trust.p12
        # (e.g. carried over by the cert import) is now stale — evict it so the new
        # cert is re-imported below instead of being skipped as "already present".
        [ "$_nm_regen" = 1 ] && "$kt" -delete -alias blade-nm \
            -keystore "${outdir}/blade-trust.p12" -storepass "$trpw" >/dev/null 2>&1 || true
        if ! "$kt" -list -alias blade-nm -keystore "${outdir}/blade-trust.p12" \
                -storepass "$trpw" >/dev/null 2>&1; then
            if "$kt" -importcert -noprompt -alias blade-nm -file "${outdir}/nm-cert.pem" \
                    -keystore "${outdir}/blade-trust.p12" -storepass "$trpw" >/dev/null; then
                ok "imported the NM cert into blade-trust.p12 (alias blade-nm)"
                # Refresh the app domain's placed copy so the AdminServer's NM
                # client trusts NM after its next restart; engines then get it
                # via config replication.
                local domcerts="${KEYSTORE_DIR:-${DOMAINS_DIR}/${DOMAIN}/config/certs}"
                if [ -d "$domcerts" ]; then
                    place_keystores "$domcerts" \
                        || warn "could not refresh ${domcerts} — re-place keystores before the AdminServer restart."
                fi
            else
                warn "could not import the NM cert into blade-trust.p12."
            fi
        fi
    fi
    [ -f "${outdir}/nm-identity.p12" ]
}

# Copy named keystores from ~/.blade/<env> (login-user owned) into a domain dir
# (install-user owned). The domain must already be WRITTEN -- WLST writeDomain
# would clobber anything placed beforehand. Returns 0 only when the first named
# keystore exists at the destination.
place_p12s() {  # $1 = destination dir, $2... = filenames in ~/.blade/<env>
    [ "$DRY" = "on" ] && return 0
    local dest="$1"; shift
    local envname srcdir own f srcs="" dsts=""
    local SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"
    envname="$(basename "${DEPLOY_CONF%.conf}")"
    srcdir="$(certs_source_dir)"
    for f in "$@"; do
        [ -f "${srcdir}/${f}" ] || { warn "no ${f} at ${srcdir} — run the certificate step first."; return 1; }
        srcs="${srcs} ${srcdir}/${f}"; dsts="${dsts} ${dest}/${f}"
    done
    # shellcheck disable=SC2086  # srcs/dsts are intentionally word-split
    if mkdir -p "$dest" 2>/dev/null && [ -w "$dest" ]; then
        cp ${srcs} "$dest/" && chmod 600 ${dsts} \
            || { warn "could not place keystores in ${dest}."; return 1; }
    else
        $SUDO mkdir -p "$dest" \
            && $SUDO cp ${srcs} "$dest/" \
            && $SUDO chmod 600 ${dsts} \
            || { warn "could not place keystores in ${dest} (needs sudo?)."; return 1; }
        # Own them like the domain they live in (the install user).
        own="$(stat -c '%U:%G' "$(dirname "$dest")" 2>/dev/null || true)"
        [ -n "$own" ] && $SUDO chown "$own" "$dest" ${dsts}
    fi
    ok "keystores placed in ${dest}"
    [ -f "${dest}/${1:-}" ]
}

# The env identity + trust pair, into a domain's config/certs. config/certs
# rides along with the domain's config sync, so managed servers on other hosts
# get the keystores at startup with no per-node push.
place_keystores() {  # $1 = destination dir (a domain's config/certs)
    place_p12s "$1" blade-identity.p12 blade-trust.p12
}

# The Node Manager pair (permanent blade-nm identity + its one-cert trust),
# into the nmdomain's config/certs. The nmdomain tree is rsynced verbatim to
# every engine host by provision_one_host, so these propagate for free.
place_nm_keystores() {  # $1 = destination dir (the nmdomain's config/certs)
    place_p12s "$1" nm-identity.p12 nm-trust.p12
}

# Per-server ServerStart, so Node Manager — in MBean-mode start
# (weblogic.StartScriptEnabled=false, written by do_nmdomain) — builds each
# server's java line from the ServerStart MBean in config.xml instead of
# sourcing setDomainEnv.sh. That is what lets the Tuning app drive JVM args
# PER SERVER (ServerStart.Arguments). The block carries what setDomainEnv used
# to supply: the SIP jars on ClassPath and the wlss/security flags in
# Arguments — without them an MBean-mode server boots with NO SIP container.
# Ported from install-occas.sh (commit 7428496b), live-proven on the ashburn
# cluster (SIP + the flowstate Coherence mesh intact). The heap here is only a
# baseline (server.mem.args); Tuning overwrites Arguments per server, and its
# extend/parse model preserves the SIP flags. Applied to the engine
# ServerTemplate (dynamic engines have no per-server MBean) and the
# AdminServer. The Oracle-home paths ride the 'current' symlink (MWHOME), so a
# patch flip never strands them; the NM hostname-verification flag lives here
# too because setUserOverrides.sh is not sourced on an MBean-mode start.
# Emit the ServerStart MBean (MBean-mode JVM args + SIP classpath) for ONE server:
# $1 = collection (ServerTemplates|Servers), $2 = name, $3 = memory args (heap).
# Split out of emit_serverstart_block so the static engine0 can reuse it with its
# OWN, lower heap ($3) — it shares the admin box with the AdminServer. The
# classpath and the non-heap flags are identical for every server.
emit_serverstart_one() {
    local coll="$1" nm="$2" mem="$3" OH="$MWHOME"
    local cp="${OH}/wlserver/server/lib/weblogic.jar:${OH}/wlserver/../oracle_common/modules/thirdparty/ant-contrib-1.0b3.jar:${OH}/wlserver/modules/features/oracle.wls.common.nodemanager.jar:${OH}/occas/server/lib/platform/oracle.sdp.occas.depended.jar:${OH}/wlserver/sip/server/lib/wlss-runtime-rest-proxy.jar:${OH}/wlserver/sip/server/lib/weblogic_sip.jar:${OH}/wlserver/common/derby/lib/derbytools.jar:${OH}/wlserver/common/derby/lib/derbyclient.jar:${OH}/wlserver/common/derby/lib/derby.jar:${OH}/wlserver/common/derby/lib/derbyshared.jar"
    local args="${mem} -da -javaagent:${OH}/wlserver/server/lib/debugpatch-agent.jar -Dwls.home=${OH}/wlserver/server -Dweblogic.home=${OH}/wlserver/server -Dwlss.maddr.enable=true -Dwlss.replication=on -Dwlss.callstate.manager.classname=com.bea.wcp.sip.replicatedstore.server.CoherenceCallStateManager -Dweblogic.security.SSL.minimumProtocolVersion=TLSv1.2 -Dweblogic.servlet.ClasspathServlet.disableSecureMode=false -Dweblogic.nodemanager.sslHostNameVerificationEnabled=false"
    cat <<PYSS
cd('/${coll}/${nm}')
set('MaxMessageSize', 100000000)
try:
    create('${nm}','ServerStart')
except:
    pass
cd('/${coll}/${nm}/ServerStart/${nm}')
set('ClassPath','${cp}')
set('Arguments','${args}')
PYSS
}

emit_serverstart_block() {
    local tmpl="${1}-template"
    local mem; mem="$(read_prop "$OCCAS_CONF" server.mem.args)"
    mem="${mem:--Xms512m -Xmx1024m -XX:MaxMetaspaceSize=1g}"
    # MaxMessageSize (set inside emit_serverstart_one) is a first-class config.xml
    # attribute -- NOT a -D JVM arg -- so it is honoured at startup independent of
    # how the JVM args are assembled, and it is the same attribute the Tuning app
    # edits (per-server, live). Raises the T3 cap from its 10 MB default so an engine
    # can pull the FSMAR custom App Router jar (~10.5 MB) from the AdminServer on every
    # AR load without MaxMessageSizeExceededException. Dynamic engines inherit the
    # ServerTemplate's ServerStart; the AdminServer gets its own.
    echo "# --- BLADE: per-server ServerStart (MBean-mode JVM args + SIP classpath) ---"
    emit_serverstart_one "ServerTemplates" "${tmpl}" "$mem"
    emit_serverstart_one "Servers" "AdminServer" "$mem"
}

# Emit the offline-WLST that puts the real certificate and the SIP channels onto
# the domain at CREATE time.
#
# This is the whole reason the TLS retrofit was painful: engine1..N are DYNAMIC,
# so there is no /Servers/<name> to configure after the fact -- their identity
# and channels come from the cluster's ServerTemplate. Writing it here means
# every engine, including ones added years later by raising the server count, is
# stamped identically and nothing has to be reached into afterwards.
#
# WebLogic's demo certificate is never left in place on a SIPS port: it is
# publicly known, so anyone can impersonate the server or decrypt a capture.
#
# NOTE the ...PassPhraseEncrypted attribute names: offline WLST rejects the plain
# ...PassPhrase setters while a domain is being created. The Encrypted variants
# accept plaintext and store it encrypted with the new domain's key.
# Emit offline-WLST that stamps the real CustomIdentity/CustomTrust keystores + an
# SSL child onto ONE bean path ($1 = e.g. /Servers/AdminServer or /Servers/engine0
# or /ServerTemplates/<tmpl>; $2 = the SSL child name). Shared by emit_tls_block
# (template + AdminServer) and emit_static_engine0_block (the static engine0), so
# the static engine gets exactly the same identity as every dynamic engine.
# Keystore paths are RELATIVE to the domain root (./config/certs), NOT absolute.
# WebLogic resolves a relative keystore path against the server's root (the domain
# home), so every managed server -- including engines that received config/certs by
# config replication onto a possibly different absolute path -- loads its own copy.
# $3 = the default SSL channel's ListenPort (default 7002). engine0 shares the
# admin box with the AdminServer, so it must NOT reuse 7002 — it passes its own.
emit_keystore_block() {
    local kspw trpw alias sslport
    kspw="$(read_prop "$WLS_SECRET" tls.keystore.passphrase)"
    trpw="$(read_prop "$WLS_SECRET" tls.trust.passphrase)"
    alias="${ID_ALIAS:-blade-identity}"
    sslport="${3:-${SSL_PORT:-7002}}"
    cat <<PYBLOCK
cd('${1}')
set('KeyStores','CustomIdentityAndCustomTrust')
set('CustomIdentityKeyStoreFileName','./config/certs/blade-identity.p12')
set('CustomIdentityKeyStoreType','PKCS12')
set('CustomIdentityKeyStorePassPhraseEncrypted','${kspw}')
set('CustomTrustKeyStoreFileName','./config/certs/blade-trust.p12')
set('CustomTrustKeyStoreType','PKCS12')
set('CustomTrustKeyStorePassPhraseEncrypted','${trpw}')
# Offline, a Server has no SSL child until one is created (the ServerTemplate
# ships with one). create() on an existing child errors, so guard both ways.
try:
    create('${2}','SSL')
except:
    pass
cd('${1}/SSL/${2}')
set('Enabled','true')
set('ListenPort',${sslport})
set('ServerPrivateKeyAlias','${alias}')
set('ServerPrivateKeyPassPhraseEncrypted','${kspw}')
# No hostname verification on the servers' outbound SSL. Inside the VCN,
# machines get dialed by whatever address is configured (private IP, OCI
# metadata FQDN) and the identity cert's SAN list can't cover them all — the
# AdminServer's own Node Manager client dies on exactly that (SSLKeyException,
# SSLWLSHostnameVerifier). Trust in our CA is the authentication; name pinning
# adds nothing between our own boxes.
set('HostnameVerificationIgnored','true')
PYBLOCK
}

emit_tls_block() {
    local tmpl="${1}-template"
    local kspw trpw
    kspw="$(read_prop "$WLS_SECRET" tls.keystore.passphrase)"
    trpw="$(read_prop "$WLS_SECRET" tls.trust.passphrase)"
    [ -n "$kspw" ] && [ -n "$trpw" ] || { warn "TLS passphrases missing from the config."; return 1; }

    echo "# --- BLADE: real certificate + SIP channels (no demo certs) ---"
    emit_keystore_block "/ServerTemplates/${tmpl}" "${tmpl}"
    emit_keystore_block "/Servers/AdminServer" "AdminServer"

    # Dynamic-server shape, set at CREATE time so a rebuild keeps it.
    #
    # ServerNameStartingIndex=1: the DYNAMIC range is engine1..N on machine1..N.
    # machine0/engine0 (and the AdminServer) are a STATIC pair on the admin box —
    # NOT dynamic members — created by emit_static_engine0_block, so the dynamic
    # calculation never lands a second engine on machine0. This also kills the
    # off-by-one that put engine1 on machine0.
    #
    # MachineNameMatchExpression is the engine machines ONLY (machine1,machine2,…);
    # machine0 is deliberately excluded. Empty on a single-box install — then the
    # dynamic set is empty and the static engine0 is the whole tier.
    #
    # MaximumDynamicServerCount is a high fixed CEILING (default 1000), not the
    # machine count: the actual running engines follow the match expression, so
    # "add a machine" just extends the expression — no count resize, no rebuild.
    #
    # CalculatedListenPorts=false gives every engine the template's ports verbatim
    # (5060/5061/8001) — one engine per machine, which is what "add a machine" is.
    #
    # The DynamicServers child is named after the server prefix in the domain this
    # template builds. Try the cluster name too rather than fail the whole domain
    # build if a future template names it differently.
    cat <<PYBLOCK
for _dsn in ['${prefix:-engine}','${1}']:
    try:
        cd('/Clusters/${1}/DynamicServers/' + _dsn)
        set('ServerNameStartingIndex',${SRV_START_INDEX:-1})
        set('CalculatedListenPorts','$([ "${DYN_CALC_PORTS:-false}" = true ] && echo true || echo false)')
        set('MachineNameMatchExpression','${match}')
        set('MaximumDynamicServerCount',${DYN_MAX:-1000})
        break
    except:
        pass
PYBLOCK

    # Plain SIP: on by default, exactly as OCCAS builds a domain. Turning it off
    # is the deliberate SIPS-only posture.
    #
    # HttpEnabledForThisProtocol=false / OutboundEnabled=true on both channels
    # match Oracle's own occas/v12n/scripts/wlst/setup-occas-server.py. An
    # HTTP-enabled SIP channel and an outbound-disabled one are both wrong, and
    # neither shows up until traffic behaves oddly.
    cat <<PYBLOCK
cd('/ServerTemplates/${tmpl}/NetworkAccessPoints/sip')
set('Enabled','$([ "$SIP_PLAIN" = false ] && echo false || echo true)')
set('ListenPort',${SIP_PLAIN_PORT:-5060})
set('HttpEnabledForThisProtocol','false')
set('OutboundEnabled','true')
PYBLOCK

    if [ "$SIP_TLS" = "true" ]; then
        cat <<PYBLOCK
cd('/ServerTemplates/${tmpl}/NetworkAccessPoints/sips')
set('Enabled','true')
set('ListenPort',${SIP_PORT:-5061})
set('HttpEnabledForThisProtocol','false')
set('OutboundEnabled','true')
set('TwoWaySSLEnabled','$([ "$SIP_TWOWAY" = true ] && echo true || echo false)')
set('ClientCertificateEnforced','$([ "$SIP_TWOWAY" = true ] && echo true || echo false)')
PYBLOCK
    else
        cat <<PYBLOCK
cd('/ServerTemplates/${tmpl}/NetworkAccessPoints/sips')
set('Enabled','false')
PYBLOCK
    fi
}

# Emit offline-WLST that creates the STATIC engine0 on the admin box (machine0),
# a configured member of the cluster next to the AdminServer. $1 = cluster name.
#
# Why static: machine0/engine0/AdminServer are deliberately OUTSIDE the dynamic
# template (the template is engine1..N on machine1..N). A configured server
# inherits NOTHING from the ServerTemplate, so its SIP channels, SSL identity and
# ServerStart must be stamped by hand HERE, at create time — there is no
# /Servers/engine0 to reach into afterward. It joins the cluster so it replicates
# SIP call state (Coherence flowstate mesh) with the dynamic engines. It runs a
# LOWER heap (engine0.mem.args) than the dynamic engines because it shares the box
# with the AdminServer.
#
# Offline reference attributes use assign() (the documented offline command for
# Cluster/Machine membership), not set(). REVIEW the dry-run output of this block
# on the real OCCAS before running it live — the sip/sips NAP attributes on a
# static server are the fiddly part.
emit_static_engine0_block() {
    local cluster="${1}"
    local e0="${prefix:-engine}0"
    local machine="${H_NAME[0]:-machine0}"
    local addr="${H_ADDR[0]}"
    local e0mem; e0mem="$(read_prop "$OCCAS_CONF" engine0.mem.args)"
    e0mem="${e0mem:--Xms256m -Xmx768m -XX:MaxMetaspaceSize=512m}"
    echo "# --- BLADE: STATIC engine0 on ${machine} (admin box), member of ${cluster} ---"
    cat <<PYE0
cd('/')
try:
    create('${e0}','Server')
except:
    pass
try:
    assign('Server','${e0}','Cluster','${cluster}')
except:
    pass
try:
    assign('Server','${e0}','Machine','${machine}')
except:
    pass
cd('/Servers/${e0}')
set('ListenAddress','${addr}')
set('ListenPort',${ENGINE_HTTP_PORT:-8001})
# Plain SIP channel (5060) — same shape the ServerTemplate gets, created by hand.
try:
    create('sip','NetworkAccessPoint')
except:
    pass
cd('/Servers/${e0}/NetworkAccessPoints/sip')
set('Protocol','sip')
set('ListenAddress','${addr}')
set('Enabled','$([ "$SIP_PLAIN" = false ] && echo false || echo true)')
set('ListenPort',${SIP_PLAIN_PORT:-5060})
set('HttpEnabledForThisProtocol','false')
set('OutboundEnabled','true')
PYE0
    if [ "$SIP_TLS" = "true" ]; then
        cat <<PYE0S
cd('/Servers/${e0}')
try:
    create('sips','NetworkAccessPoint')
except:
    pass
cd('/Servers/${e0}/NetworkAccessPoints/sips')
set('Protocol','sips')
set('ListenAddress','${addr}')
set('Enabled','true')
set('ListenPort',${SIP_PORT:-5061})
set('HttpEnabledForThisProtocol','false')
set('OutboundEnabled','true')
set('TwoWaySSLEnabled','$([ "$SIP_TWOWAY" = true ] && echo true || echo false)')
set('ClientCertificateEnforced','$([ "$SIP_TWOWAY" = true ] && echo true || echo false)')
PYE0S
    else
        cat <<PYE0S
cd('/Servers/${e0}')
try:
    create('sips','NetworkAccessPoint')
except:
    pass
cd('/Servers/${e0}/NetworkAccessPoints/sips')
set('Enabled','false')
PYE0S
    fi
    # Same SSL identity as the AdminServer/template, but on a DISTINCT default SSL
    # port (ENGINE_SSL_PORT, default 8002) — engine0 shares the box with the
    # AdminServer, which owns 7002. Then a LOWER-heap ServerStart.
    emit_keystore_block "/Servers/${e0}" "${e0}" "${ENGINE_SSL_PORT:-8002}"
    emit_serverstart_one "Servers" "${e0}" "$e0mem"
}

# Admin password: env > the config > prompt (skipped under dry-run).
get_admin_pw() {
    local v="${BLADE_WLS_PASSWORD:-}"
    [ -z "$v" ] && [ -f "$WLS_SECRET" ] && v="$(read_prop "$WLS_SECRET" admin.password)"
    if [ -z "$v" ] && [ "$DRY" != "on" ]; then
        # The cursor-newline must go to stderr: callers capture this function's
        # stdout via $(get_admin_pw), and a stray newline there prepends to the
        # password (breaking e.g. the WLST setPassword('…') literal).
        read -rs -p "  Admin password for the new domain: " v || v=""; echo >&2
        [ -n "$v" ] || { warn "no password provided."; return 1; }
    fi
    printf '%s' "$v"
}

# Turn on BLADE's FSMAR as the domain's SIP Application Router. The Config Wizard
# writes an empty <app-router/> into config/custom/sipserver.xml; the FSMAR is THE
# router for BLADE (the project is built around it), so default it on — a new domain
# routes through it out of the box instead of the stock DefaultApplicationRouter.
# Engines pull blade-fsmar.jar from the AdminServer at AR load (the template's
# MaxMessageSize covers the >10 MB transfer). Idempotent; stays editable in the
# Admin Console / Tuning app. The element names match a live sipserver.xml.
enable_custom_approuter() {
    local sipxml="${DOMAINS_DIR}/${DOMAIN}/config/custom/sipserver.xml" jar="${1:-blade-fsmar.jar}"
    as_install_user test -f "$sipxml" || { warn "sipserver.xml not found — FSMAR App Router not enabled."; return 0; }
    as_install_user grep -q 'use-custom-app-router>true' "$sipxml" && return 0   # already on
    as_install_user perl -0777 -i -pe \
        's{<app-router>\s*</app-router>}{<app-router>\n    <use-custom-app-router>true</use-custom-app-router>\n    <custom-app-router-jar-file-name>'"$jar"'</custom-app-router-jar-file-name>\n  </app-router>}s' \
        "$sipxml" \
        && ok "FSMAR enabled as the App Router (useCustomAppRouter=true, ${jar})" \
        || warn "could not enable the FSMAR App Router in sipserver.xml."
}

# ----------------------------------------------------------------------------
# Step 2 — dynamic-cluster domain from Oracle's template, parameterized.
# Writes with OverwriteDomain=true (the template's default) — clobbers an
# existing domain dir of the same name.
# ----------------------------------------------------------------------------
do_configure() {
    local mwhome domain mode auser prefix match dcount static chk
    mwhome="$(read_prop "$OCCAS_CONF" oracle.home)"
    domain="$(read_prop "$OCCAS_CONF" domain.name)"
    mode="$(read_prop "$OCCAS_CONF" server.start.mode)";   mode="${mode:-dev}"
    auser="$(read_prop "$OCCAS_CONF" admin.username)";     auser="${auser:-weblogic}"
    prefix="$(read_prop "$OCCAS_CONF" server.name.prefix)"
    match="$(read_prop "$OCCAS_CONF" machine.match.expression)"
    # DYNAMIC ceiling (fixed, default 1000). match may be EMPTY on a single-box
    # install — then the dynamic set is empty and the static engine0 is the tier —
    # so match is NOT required.
    local dynmax; dynmax="$(read_prop "$OCCAS_CONF" dynamic.server.max)"; dynmax="${dynmax:-1000}"
    for chk in mwhome domain prefix; do
        [ -n "${!chk}" ] || { warn "occas.conf: missing $chk (required for configure)"; return 1; }
    done

    local machines=() i=1 m
    while :; do
        m="$(read_prop "$OCCAS_CONF" "machine.${i}")"; [ -n "$m" ] || break
        machines+=("$m"); i=$((i + 1))
    done
    [ "${#machines[@]}" -ge 1 ] || { warn "occas.conf: no machine.N entries"; return 1; }

    local pw; pw="$(get_admin_pw)" || return 1

    info "Configure domain '${domain}' (${mode}) — static engine0 + dynamic engine1..N"
    log  "  prefix=${prefix}  dynamic machines=${match:-<none, single-box>}  ceiling=${dynmax}"

    local props name addr port type idx=1
    props="ADMIN_USERNAME=${auser}
ADMIN_PASSWORD=__PW__
ServerNamePrefix=${prefix}
MachineNameMatchExpression=${match}
MaximumDynamicServerCount=${dynmax}"
    for m in "${machines[@]}"; do
        IFS=: read -r name addr port type <<< "$m"
        [ -n "$name" ] && [ -n "$addr" ] && [ -n "$port" ] && [ -n "$type" ] \
            || { warn "bad machine entry '${m}' (want name:addr:port:type)"; return 1; }
        log "    ${idx}. ${name}  nm=${addr}:${port} (${type})"
        props="${props}
Machine${idx}Name=${name}
Machine${idx}NodemanagerListenPort=${port}
Machine${idx}NodemanagerListenAddress=${addr}
Machine${idx}NodemanagerNMType=${type}"
        idx=$((idx + 1))
    done
    local tmpl_dir="${mwhome}/occas/common/templates/scripts/wlst"
    local src_py="${tmpl_dir}/occas-replicated-dynamiccluster.py"

    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] stage ${src_py} + generated .properties${C_RESET}"
        log "${C_DIM}  [dry-run] generated .properties (password redacted):${C_RESET}"
        printf '%s\n' "$props" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] sed .py: domainName='${domain}', domainDir='${DOMAINS_DIR}/${domain}', ServerStartMode='${mode}'${C_RESET}"
        log "${C_DIM}  [dry-run] inject TLS/SIP WLST before writeDomain (passphrases redacted):${C_RESET}"
        emit_tls_block "BEA_ENGINE_TIER_CLUST" 2>/dev/null \
            | sed -E "s/(PassPhrase','')[^']*/\1<REDACTED>/" | sed 's/^/    /' \
            || log "${C_DIM}    (TLS block unavailable — passphrases not generated yet)${C_RESET}"
        log "${C_DIM}  [dry-run] setWLSEnv + java weblogic.WLST occas-replicated-dynamiccluster.py${C_RESET}"
        return 0
    fi

    # The template writes with OverwriteDomain=true — an existing domain dir of
    # this name is CLOBBERED. Make that an explicit, confirmed choice.
    local domdir="${DOMAINS_DIR}/${domain}"
    if [ -d "$domdir" ]; then
        warn "domain '${domain}' already exists at ${domdir}"
        yesno "Overwrite it? This CLOBBERS the existing domain." "N" || { warn "configure aborted — domain left intact."; return 1; }
    fi

    [ -f "$src_py" ] || { warn "template not found: ${src_py} (install OCCAS first)"; return 1; }
    local setwls="${mwhome}/wlserver/server/bin/setWLSEnv.sh"
    [ -f "$setwls" ] || { warn "setWLSEnv.sh not found: ${setwls}"; return 1; }

    # Stage in a temp workdir (the .py reads the .properties by relative name).
    local work; work="$(mktemp -d /tmp/occas-cfg.XXXXXX)"
    cp "$src_py" "${work}/occas-replicated-dynamiccluster.py"
    # Jython aborts on any non-ASCII byte unless the file declares an encoding
    # (PEP 263). The stock Oracle template has none, and the injected TLS/SIP
    # comments use em-dashes etc. — declare UTF-8 on line 1 so it can't recur.
    { printf '%s\n' '# -*- coding: utf-8 -*-'; cat "${work}/occas-replicated-dynamiccluster.py"; } > "${work}/.py.hdr" \
        && mv "${work}/.py.hdr" "${work}/occas-replicated-dynamiccluster.py"
    printf '%s\n' "${props/__PW__/$pw}" > "${work}/occas-replicated-dynamiccluster.properties"
    chmod 600 "${work}/occas-replicated-dynamiccluster.properties"

    # Oracle's template computes domainDir from beaHome + user_projects/domains.
    # Point it at DOMAINS_DIR instead: a domain inside the Oracle home cannot
    # survive a patch symlink flip (it would swing onto the copy's snapshot).
    # Neutralize the dynamic-cluster ceiling: setMaxDynamicClusterSize is a no-op
    # per the RE'd OCCAS code, and the WLS setter rejects INT_MAX outright. Comment
    # the call out so only setMaximumDynamicServerCount (the real count) governs.
    sed "s/^domainName=.*/domainName='${domain}'/; \
         s|^domainDir=.*|domainDir='${DOMAINS_DIR}/${domain}'|; \
         s/^dynServers\.setMaxDynamicClusterSize/#no-ceiling: &/; \
         s/setOption('ServerStartMode', '[^']*')/setOption('ServerStartMode', '${mode}')/" \
        "${work}/occas-replicated-dynamiccluster.py" > "${work}/.py.tmp" \
        && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"

    # BLADE never ships a demo-cert domain: the certs must exist BEFORE writeDomain
    # so emit_tls_block can bake their path + passphrases into config.xml. Generate
    # (the 'g' step) if missing; the p12 FILES are placed into config/certs AFTER
    # writeDomain (below) -- placing them first would let writeDomain clobber them.
    # If the certs can't be produced (e.g. supply mode with nothing configured),
    # refuse rather than silently fall back to WebLogic's demo identity.
    if ! ensure_certs_source; then
        rm -rf "$work"
        warn "TLS certificates are not ready for '${NAME}' — refusing to build a demo-cert domain."
        warn "Do STEP 4 (TLS) first — generate a CA, or supply your own certificate — then configure."
        return 1
    fi
    # TLS goes in FIRST so the template already carries the real certificate before
    # any server is written from it.
    if ! emit_tls_block "BEA_ENGINE_TIER_CLUST" > "${work}/tls.block" 2>/dev/null; then
        rm -rf "$work"
        warn "TLS passphrases missing — refusing to build a demo-cert domain. Do STEP 4 (TLS) first."
        return 1
    fi
    chmod 600 "${work}/tls.block"
    awk 'NR==FNR { blk = blk $0 ORS; next }
         /OverwriteDomain/ && !ins { printf "%s", blk; ins = 1 }
         { print }' \
        "${work}/tls.block" "${work}/occas-replicated-dynamiccluster.py" \
        > "${work}/.py.tmp" && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"
    log "  TLS: real certificate on the server template; sip=$([ "$SIP_PLAIN" = false ] && echo off || echo on):${SIP_PLAIN_PORT:-5060} sips=$([ "$SIP_TLS" = true ] && echo on:${SIP_PORT:-5061} || echo off)"

    # Per-server ServerStart (MBean-mode JVM args + SIP classpath), injected the
    # same way so the template and AdminServer already exist when it runs. This
    # is the config.xml half of MBean-mode start; do_nmdomain writes the
    # nodemanager.properties half (weblogic.StartScriptEnabled=false).
    emit_serverstart_block "BEA_ENGINE_TIER_CLUST" > "${work}/serverstart.block"
    awk 'NR==FNR { blk = blk $0 ORS; next }
         /OverwriteDomain/ && !ins { printf "%s", blk; ins = 1 }
         { print }' \
        "${work}/serverstart.block" "${work}/occas-replicated-dynamiccluster.py" \
        > "${work}/.py.tmp" && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"
    log "  ServerStart: MBean-mode JVM args + SIP classpath on the template and AdminServer"

    # STATIC engine0 on machine0 (the admin box), a configured member of the
    # cluster. Spliced LAST so the cluster, machine0, the template and the
    # AdminServer already exist when it runs. This is what makes machine0/engine0
    # a static pair OUTSIDE the dynamic template (engine1..N).
    emit_static_engine0_block "BEA_ENGINE_TIER_CLUST" > "${work}/engine0.block"
    awk 'NR==FNR { blk = blk $0 ORS; next }
         /OverwriteDomain/ && !ins { printf "%s", blk; ins = 1 }
         { print }' \
        "${work}/engine0.block" "${work}/occas-replicated-dynamiccluster.py" \
        > "${work}/.py.tmp" && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"
    log "  Static engine0: configured cluster member on ${H_NAME[0]:-machine0} (admin box), lower heap"

    local jh rc=0; jh="$(read_prop "$OCCAS_CONF" java.home)"
    # The domain lands in the install user's DOMAINS_DIR, so WLST runs as them.
    iu_wlst_run "$work" occas-replicated-dynamiccluster.py "$mwhome" "$jh" || rc=$?
    as_install_user rm -rf "$work"
    [ "$rc" -eq 0 ] || { warn "configure failed (WLST rc=${rc})"; return 1; }
    ok "Domain '${domain}' written under ${DOMAINS_DIR}/"
    # Verify the injected TLS keystores actually persisted (offline WLST can silently
    # drop server sets). Without them the servers fall back to demo certs. The SIP
    # classpath no longer rides config.xml -- the start step derives it from
    # setDomainEnv and hands it to nmStart -- so we don't check <server-start> here.
    local _cfg="${DOMAINS_DIR}/${domain}/config/config.xml"
    if as_install_user test -f "$_cfg"; then
        as_install_user grep -q 'custom-identity-key-store-file-name' "$_cfg" \
            || warn "config.xml has NO keystores — the injected TLS did not persist; servers would fall back to demo certs."
    fi
    # Now the domain dir exists, drop the keystores into its config/certs (the
    # exact path emit_tls_block baked into the template). config/certs replicates
    # to the engines on start, so no per-node push. Certs are guaranteed present
    # (ensure_certs_source above), so a failure here is a real placement error.
    place_keystores "${KEYSTORE_DIR}" \
        || warn "keystores not placed — the AdminServer will fail to load its identity until the HTTPS/SIP-TLS step runs."
    # Heap/metaspace for NM-launched servers rides ServerStart.Arguments
    # (emit_serverstart_block above); this hook covers hand-run start scripts.
    write_user_overrides "${DOMAINS_DIR}/${domain}"
    # Route through the FSMAR out of the box — it is BLADE's App Router.
    enable_custom_approuter
    # Enroll the new app domain into the standalone Node Manager so it can start
    # the AdminServer/engines. No-op-with-hint if the NM domain isn't built yet.
    register_domain_with_nm "$domain" "${DOMAINS_DIR}/${domain}" || true
    warn "Next: start Node Manager (so it sees this domain), then start the AdminServer."
}

# Host prerequisites for the install/configure steps. Real checks on the Linux
# install target; advisory-only on the Mac you build the profile from.
do_preflight() {
    local mwhome inv_loc inv_grp installer os
    mwhome="$(read_prop "$OCCAS_CONF" oracle.home)"
    inv_loc="$(read_prop "$OCCAS_CONF" inventory.loc)"
    inv_grp="$(read_prop "$OCCAS_CONF" inventory.group)"; inv_grp="${inv_grp:-oinstall}"
    installer="$(read_prop "$OCCAS_CONF" installer.jar)"
    os="$(uname -s)"
    # PF_NEED is the aggregate; the _pf_* flags remember WHICH category failed
    # so the footer prescribes only the fixes that apply.
    PF_NEED=""
    local _pf_jdk="" _pf_grp="" _pf_sudo="" _pf_dirs="" _pf_tmpl="" _pf_nofile=""

    info "Preflight — host prerequisites (install user, group, dirs, Java)"
    log  "  MW_HOME: ${mwhome}    inventory: ${inv_loc}    group: ${inv_grp}"
    log ""

    # JDK — the silent installer runs `java -jar` and needs a full JDK (not a
    # JRE), version-matched to the OCCAS release. Prefer the profile's java.home.
    local jhome jdesc jrc cfgver want jmajor
    jhome="$(read_prop "$OCCAS_CONF" java.home)"
    jdesc="$(jdk_describe "$jhome")" && jrc=0 || jrc=$?
    case "$jrc" in
        0) ok "JDK: ${jdesc}" ;;
        1) warn "JDK: ${jdesc} — the installer needs a full JDK."; PF_NEED="yes"; _pf_jdk="yes" ;;
        *) warn "JDK: ${jdesc} — the installer needs one."; PF_NEED="yes"; _pf_jdk="yes" ;;
    esac
    # Validate the JDK major against the OCCAS release (version from the conf, or
    # detected from a real install). This is the runtime JDK, not the build JDK.
    cfgver="$(read_prop "$OCCAS_CONF" occas.version)"
    [ -z "$cfgver" ] && cfgver="$(detect_occas_version "$mwhome")"
    want="$(occas_jdk_major "$cfgver")"
    jmajor=""; [ -n "$jhome" ] && [ -x "${jhome}/bin/java" ] && jmajor="$(jdk_major "${jhome}/bin/java")"
    # Certification is a recommendation, not a gate: newer majors are known to
    # run and the wizard let the user choose one. Only BELOW the certified
    # major is a real problem worth blocking on.
    if [ -n "$want" ] && [ -n "$jmajor" ]; then
        if [ "$jmajor" = "$want" ]; then ok "JDK ${jmajor} matches OCCAS ${cfgver}'s certification."
        elif [ "$jmajor" -gt "$want" ] 2>/dev/null; then
            ok "JDK ${jmajor} — newer than OCCAS ${cfgver}'s certified JDK ${want}; your choice stands."
        else
            warn "JDK is ${jmajor}, BELOW OCCAS ${cfgver}'s certified JDK ${want} — unlikely to run."; PF_NEED="yes"; _pf_jdk="yes"
        fi
    else
        log "  ${C_DIM}match the JDK to the OCCAS release per Oracle's certification matrix.${C_RESET}"
    fi

    # JDK missing or the wrong major? At this point PF_NEED reflects only the JDK
    # checks above (the host checks run below), so a set PF_NEED means the JDK is
    # the problem. If we can fetch the one OCCAS wants, offer it here too — same
    # path as the wizard — and write it back into the profile's java.home.
    if [ -n "$PF_NEED" ] && jdk_dl_supported "$want" \
       && yesno "Download JDK ${want} from Oracle into ${JAVA_BASE} and set it as this profile's java.home?" "Y"; then
        if download_jdk "$want" "$JAVA_BASE"; then
            # Record the <java.dir>/current LINK, not the versioned path, so a
            # later JDK upgrade is a flip (raw path only if the ln fails).
            local _jlink="${JAVA_BASE}/current"
            ln -sfn "$JDK_DL_HOME" "$_jlink" 2>/dev/null \
                || sudo ln -sfn "$JDK_DL_HOME" "$_jlink" 2>/dev/null || true
            if [ "$(readlink "$_jlink" 2>/dev/null)" = "$JDK_DL_HOME" ]; then
                set_conf_prop "$OCCAS_CONF" java.home "$_jlink"
                ok "java.home set to ${_jlink} -> $(basename "$JDK_DL_HOME") in ${OCCAS_CONF#${SCRIPT_DIR}/}"
            else
                set_conf_prop "$OCCAS_CONF" java.home "$JDK_DL_HOME"
                ok "java.home set to ${JDK_DL_HOME} in ${OCCAS_CONF#${SCRIPT_DIR}/}"
            fi
            PF_NEED=""; _pf_jdk=""   # the JDK prerequisite is now satisfied
        fi
    fi

    if [ "$os" = "Darwin" ]; then
        warn "macOS — skipping user/group/dir checks (host prep is for the Linux install target)."
    else
        if getent group "$inv_grp" >/dev/null 2>&1; then ok "group '${inv_grp}' exists"
        else warn "group '${inv_grp}' missing"; PF_NEED="yes"; _pf_grp="yes"; fi
        # Identity: the write steps (install/configure/starts) run AS the
        # install user — the invoker only needs a way to become them.
        local iu_u; iu_u="$(iu_name)"
        if [ "$(id -un)" = "$iu_u" ]; then
            ok "running as the install user '${iu_u}'"
        elif ! id "$iu_u" >/dev/null 2>&1; then
            warn "install user '${iu_u}' missing"; PF_NEED="yes"; _pf_grp="yes"
        elif sudo -n -H -u "$iu_u" true 2>/dev/null; then
            ok "write steps will run as '${iu_u}' via sudo (you are $(id -un))"
        elif command -v sudo >/dev/null 2>&1; then
            # sudo -n can't tell "needs a password" from "not allowed" — an
            # interactive run may still work, so advise instead of failing.
            log "  ${C_DIM}sudo to '${iu_u}' may prompt for a password (fine interactively; NOPASSWD needed for unattended runs).${C_RESET}"
        else
            warn "no sudo on this host — the install/configure steps must run as '${iu_u}'"; PF_NEED="yes"; _pf_sudo="yes"
        fi
        # Who the writability checks below are FOR: the identity the steps use.
        local pf_as; if iu_switching; then pf_as="$iu_u"; else pf_as="$(id -un)"; fi
        # MW_HOME: present means already installed; else parent must be writable.
        if [ -d "${mwhome}/wlserver" ]; then ok "OCCAS already installed at ${mwhome}"
        elif [ -d "$mwhome" ] && as_install_user test -w "$mwhome"; then ok "MW_HOME writable by ${pf_as}: ${mwhome}"
        elif [ -d "$(dirname "$mwhome")" ] && as_install_user test -w "$(dirname "$mwhome")"; then ok "MW_HOME parent writable by ${pf_as} (dir will be created)"
        else warn "MW_HOME not writable by '${pf_as}': ${mwhome}"; PF_NEED="yes"; _pf_dirs="yes"; fi
        if [ -d "$inv_loc" ] && as_install_user test -w "$inv_loc"; then ok "inventory dir writable by ${pf_as}: ${inv_loc}"
        elif [ -d "$(dirname "$inv_loc")" ] && as_install_user test -w "$(dirname "$inv_loc")"; then ok "inventory parent writable by ${pf_as} (dir will be created)"
        else warn "inventory location not writable by '${pf_as}': ${inv_loc}"; PF_NEED="yes"; _pf_dirs="yes"; fi
        # Media + JDK must be READABLE by the install user — anything parked
        # under the invoker's 0700 home dir is not.
        if iu_switching && ! occas_installed "$mwhome" && [ -n "$installer" ] && [ -f "$installer" ]; then
            if as_install_user test -r "$installer"; then ok "installer readable by ${pf_as}"
            else log "  ${C_DIM}installer not readable by ${pf_as} — the install step will stage a copy where ${pf_as} can read it.${C_RESET}"; fi
        fi
        if iu_switching && [ -x "${jhome}/bin/java" ] && ! as_install_user test -x "${jhome}/bin/java"; then
            warn "JDK ${jhome} not usable by '${pf_as}' (under a private home?) — put it under $(dirname "${OCCAS_BASE:-/opt/oracle/occas}")/java"
            PF_NEED="yes"; _pf_jdk="yes"
        fi

        # Capacity + OS advisories. The silent install runs with -ignoreSysPrereqs,
        # so Oracle WON'T flag these — we surface them here rather than let an
        # install fail cryptically halfway. Advisory (warn), not hard blockers.
        local memkb memgb swapkb nofile freekb freegb
        memkb="$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null)"; memgb=$(( ${memkb:-0} / 1024 / 1024 ))
        if [ "${memkb:-0}" -ge 4194304 ]; then ok "RAM: ${memgb} GiB"
        else warn "RAM: ${memgb} GiB — OCCAS wants ~4 GiB+; installs and servers may thrash."; fi
        swapkb="$(awk '/SwapTotal/{print $2}' /proc/meminfo 2>/dev/null)"
        [ "${swapkb:-0}" -gt 0 ] && ok "swap configured" || log "  ${C_DIM}no swap (ok on a big-RAM box).${C_RESET}"
        # The install + servers run as the INSTALL USER via sudo, so measure THAT
        # user's effective limit — not this login shell's, which is irrelevant and
        # never gets raised. A fresh sudo session reads limits.d immediately, so
        # after the auto-fix below the very next preflight sees the new value —
        # no logout/login, no reboot.
        local _pfu; _pfu="$(read_prop "$OCCAS_CONF" install.user)"; _pfu="${_pfu:-oracle}"
        if [ "$(id -un)" = "$_pfu" ]; then nofile="$(ulimit -n 2>/dev/null || echo 0)"
        elif id "$_pfu" >/dev/null 2>&1 && command -v sudo >/dev/null 2>&1; then
            nofile="$(sudo -n -u "$_pfu" bash -c 'ulimit -n' 2>/dev/null || echo 0)"
        else nofile="$(ulimit -n 2>/dev/null || echo 0)"; fi   # user not created yet
        if [ "$nofile" = unlimited ] || { [ "$nofile" -ge 4096 ] 2>/dev/null; }; then
            ok "open-files limit (${_pfu}): ${nofile}"
        else
            warn "open-files limit for '${_pfu}' is ${nofile} — WebLogic wants ≥4096."
            _pf_nofile=low
        fi
        freekb="$(df -Pk "$(dirname "$mwhome")" 2>/dev/null | awk 'NR==2{print $4}')"; freegb=$(( ${freekb:-0} / 1024 / 1024 ))
        if [ "${freekb:-0}" -ge 10485760 ]; then ok "disk free where MW_HOME goes: ${freegb} GiB"
        else warn "only ${freegb} GiB free where MW_HOME goes — a full OCCAS install needs ~10 GiB."; fi
        if command -v rpm >/dev/null 2>&1; then
            rpm -q libaio >/dev/null 2>&1 && ok "libaio present" \
                || warn "libaio not installed — Oracle installs often need it (sudo dnf install -y libaio)."
        fi
    fi

    # Installer jar — only a fresh 'install' needs it. Moot once OCCAS is there.
    if occas_installed "$mwhome"; then ok "OCCAS installed at ${mwhome} — no installer jar needed."
    elif [ -n "$installer" ] && [ -f "$installer" ]; then ok "installer jar present: ${installer}"
    else warn "installer jar not found: ${installer:-<unset>} (needed for step 1, on the install box)"; fi

    # Node Manager domain prerequisites (only checkable once OCCAS is installed).
    local nmtmpl="${mwhome}/wlserver/common/templates/wls/wls.jar"
    local nmport; nmport="$(read_prop "$OCCAS_CONF" nm.listen.port)"; nmport="${nmport:-5556}"
    if occas_installed "$mwhome"; then
        [ -f "$nmtmpl" ] && ok "WLS basic template present (for the nmdomain): ${nmtmpl#${mwhome}/}" \
                         || { warn "WLS basic template missing: ${nmtmpl} — the Node Manager step needs it."; PF_NEED="yes"; _pf_tmpl="yes"; }
    fi
    if nm_listening "$nmport"; then ok "Node Manager already listening on :${nmport}."
    else log "  ${C_DIM}Node Manager port :${nmport} is free (it'll start with the Node Manager step).${C_RESET}"; fi

    local pf_user; pf_user="$(read_prop "$OCCAS_CONF" install.user)"; pf_user="${pf_user:-oracle}"
    log ""
    if [ -n "$PF_NEED" ]; then
        warn "Prerequisites missing — fixes for what failed above:"
        if [ -n "$_pf_grp" ]; then
            log "    'u'  Create install user & group   (${pf_user}:${inv_grp}; uses sudo for you)"
            log "  ${C_DIM}         …or as root: groupadd ${inv_grp}; useradd -g ${inv_grp} -m ${pf_user}${C_RESET}"
            next_step u "create the install user & group (${pf_user}:${inv_grp})"
        fi
        if [ -n "$_pf_sudo" ]; then
            log "    grant $(id -un) sudo (NOPASSWD covers unattended runs), or run ./install.sh as '${pf_user}'."
        fi
        if [ -n "$_pf_dirs" ]; then
            log "    'm'  Create install dirs & chown   (${mwhome} + ${inv_loc}; uses sudo for you)"
            log "  ${C_DIM}         …or as root: mkdir -p ${mwhome} ${inv_loc}; chown -R ${pf_user}:${inv_grp} ${mwhome} ${inv_loc}${C_RESET}"
            next_step m "create the install dirs & chown them (${mwhome} + ${inv_loc})"
        fi
        if [ -n "$_pf_jdk" ]; then
            log "    pick a usable JDK: re-run the wizard's OCCAS phase (it lists what's installed)."
        fi
        if [ -n "$_pf_tmpl" ]; then
            log "    the wlserver template is missing from ${mwhome} — re-run the Install OCCAS step."
            next_step i "install OCCAS (lays down the wlserver template)"
        fi
        log  "  Then re-run Preflight ('p')."
        next_step p "re-run Preflight to re-check"
    elif [ "$os" != "Darwin" ]; then
        ok "Preflight looks good — ready for step 1 (install)."
    fi

    # Open-files limit: raise it AUTOMATICALLY when low — a one-time, idempotent
    # limits.d drop-in, no prompt and no menu row. It applies on the NEXT login
    # (this session keeps the old value, reported above), so once it's written
    # preflight just notes it's set and never raises the topic again.
    if [ "$_pf_nofile" = low ]; then log ""; do_raise_limits || true; fi

    # Remember the outcome so the dashboard's Preflight row shows a ✓ once it has
    # passed (build_menu_rows reads this — it must NOT re-run preflight, which now
    # has a side effect). Persist it to the profile so a fresh launch reflects the
    # last run; editing any phase rewrites the conf (save_profile truncates), which
    # drops this key and forces a re-run — automatic, correct invalidation.
    [ -n "$PF_NEED" ] && PF_OK=0 || PF_OK=1
    if [ "$DRY" != on ] && [ -f "$OCCAS_CONF" ]; then set_conf_prop "$OCCAS_CONF" preflight.passed "$PF_OK"; fi
}

# Register an app domain with the standalone Node Manager (nmdomain) so that
# nmConnect/nmStart can find it. Idempotent — updates nodemanager.domains, which
# Node Manager reads at (re)start. Falls back to the conf when globals are unset.
register_domain_with_nm() {
    local domname="$1" domhome="$2"
    local mw="${MWHOME:-$(read_prop "$OCCAS_CONF" oracle.home)}"
    local nmdom="${NM_DOMAIN:-$(read_prop "$OCCAS_CONF" nm.domain.name)}"
    [ -n "$mw" ] && [ -n "$nmdom" ] || { warn "cannot register: missing oracle.home / nm.domain.name"; return 1; }
    local nmfile="${DOMAINS_DIR}/${nmdom}/nodemanager/nodemanager.domains"
    if [ ! -d "$(dirname "$nmfile")" ]; then
        warn "Node Manager domain '${nmdom}' not set up yet — set up the Node Manager first."
        return 1
    fi
    # The enrollment file belongs to the nmdomain's owner.
    local IU_USER; IU_USER="$(iu_owner_user "${DOMAINS_DIR}/${nmdom}")"
    iu_set_conf_prop "$nmfile" "$domname" "$domhome"
    ok "enrolled ${domname} → ${domhome} in ${nmdom}'s nodemanager.domains"
    # Registering the PATH is only half of enrollment; NM also has to accept our
    # credentials for this domain. Skipped when the domain is running, since this
    # is an offline edit and WebLogic would overwrite it on shutdown.
    if ! pgrep -f "weblogic.Name=.*${domname}" >/dev/null 2>&1; then
        local apw="${BLADE_WLS_PASSWORD:-}"
        [ -z "$apw" ] && [ -f "$WLS_SECRET" ] && apw="$(read_prop "$WLS_SECRET" admin.password)"
        set_domain_nm_credentials "$domhome" "$domname" "${ADMIN_USER:-weblogic}" "$apw" || true
    fi
    # Node Manager reads nodemanager.domains ONCE, at startup. A running NM has
    # therefore not seen what we just wrote, and every later nmConnect for this
    # domain fails with "no domain" — which is what an unattended install hits
    # between 'c' and 's'. Telling the user to go restart it by hand leaves a
    # half-done operation behind, so finish it here.
    if nm_listening "${NM_PORT:-$(read_prop "$OCCAS_CONF" nm.listen.port)}"; then
        info "Restarting Node Manager so it picks up the '${domname}' enrollment …"
        restart_nm || { warn "could not restart Node Manager — stop, then start Node Manager, or the AdminServer start will fail."; return 1; }
    fi
    return 0
}

# Write a server's boot identity.
#
# In PRODUCTION mode WebLogic asks for the boot username/password on stdin — and
# Node Manager has redirected stdin, so the server dies immediately with
# BEA-090782 ("the System Console to read the password securely was not found").
# The fix is this file; WebLogic encrypts it in place on first boot.
# 0600 via umask, and the password never reaches a command line.
write_boot_properties() {
    local domhome="$1" server="$2" auser="$3" pw="$4"
    local dir="${domhome}/servers/${server}/security"
    [ -n "$pw" ] || { warn "no admin password — cannot write boot.properties for ${server}."; return 1; }
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] write ${dir}/boot.properties (0600)${C_RESET}"
        return 0
    fi
    local IU_USER; IU_USER="$(iu_owner_user "$domhome")"   # write as the domain's owner
    as_install_user mkdir -p "$dir" || { warn "could not create ${dir}."; return 1; }
    printf 'username=%s\npassword=%s\n' "$auser" "$pw" | iu_write "${dir}/boot.properties" 600 \
        || { warn "could not write ${dir}/boot.properties."; return 1; }
    ok "wrote boot identity for ${server}."
}

# Set a domain's Node Manager credentials to the admin credentials.
#
# Needed because Node Manager is STANDALONE here. NM authenticates a connection
# for domain X against X's own config/nodemanager/nm_password.properties, and the
# domain template seeds that with a hash we do not know — so nmConnect comes back
# "Access to domain 'X' for user 'weblogic' denied" even though the enrollment
# and the SSL handshake are both fine. (The old per-domain-NM layout never hit
# this: NM lived inside the domain and used its credentials by construction.)
#
# Offline WLST, so it must run with the domain STOPPED — which is where it is
# called from: right after configure writes the domain.
set_domain_nm_credentials() {
    local domhome="$1" domname="$2" auser="$3" pw="$4"
    local mw="${MWHOME:-$(read_prop "$OCCAS_CONF" oracle.home)}"
    [ -n "$pw" ] || { warn "no admin password — cannot set Node Manager credentials for '${domname}'."; return 1; }
    local setwls="${mw}/wlserver/server/bin/setWLSEnv.sh"
    [ -f "$setwls" ] || { warn "setWLSEnv.sh not found: ${setwls}"; return 1; }
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] offline WLST: set NodeManagerUsername/PasswordEncrypted on ${domname}${C_RESET}"
        return 0
    fi
    local work; work="$(mktemp -d /tmp/blade-nmcred.XXXXXX)"
    cat > "${work}/nmcred.py" <<PYEOF
# -*- coding: utf-8 -*-
readDomain('${domhome}')
cd('/SecurityConfiguration/${domname}')
set('NodeManagerUsername', '${auser}')
set('NodeManagerPasswordEncrypted', '${pw}')
updateDomain()
closeDomain()
PYEOF
    chmod 600 "${work}/nmcred.py"
    local jh rc=0; jh="$(read_prop "$OCCAS_CONF" java.home)"
    # updateDomain() writes into the domain — run as its owner.
    local IU_USER; IU_USER="$(iu_owner_user "$domhome")"
    iu_wlst_run "$work" nmcred.py "$mw" "$jh" >/dev/null 2>&1 || rc=$?
    as_install_user rm -rf "$work"
    [ "$rc" -eq 0 ] || { warn "could not set Node Manager credentials for '${domname}' (WLST rc=${rc})."; return 1; }
    ok "Node Manager credentials set on '${domname}' (user ${auser})."
}

# WLST_PROPERTIES needed to nmConnect to OUR Node Manager, echoed to stdout.
#
# NM's listener is SSL and presents the BLADE identity (blade-identity.p12), so
# WLST must trust the BLADE CA — WLST trusts only the JDK cacerts by default,
# which don't contain it. That surfaces as a bare PKIX "unable to find valid
# certification path", nowhere near the actual cause.
#
# There is no demo path: NM presents its own permanent blade-nm certificate
# (do_nmdomain), so we trust the nmdomain's nm-trust.p12 — that one cert and
# nothing else. If it's missing we warn and emit no trust store — the nmConnect
# then fails with a real error instead of quietly leaning on the public demo
# CA. Always returns 0 (callers assign it directly under `set -e`). Hostname
# verification is off: the cert names no host at all (CN=blade-nodemanager),
# and the units and interactive runs both nmConnect to 'localhost'.
#
# Back-compat: an nmdomain configured before the dedicated NM cert has only
# blade-trust.p12 — fall back to it so 'n' can be re-run THROUGH a working
# install.sh against the old layout.
nm_wlst_props() {
    local common="-Dweblogic.security.SSL.ignoreHostnameVerification=true"
    local nmdom ksdir nmpw
    nmdom="${NM_DOMAIN:-$(read_prop "$OCCAS_CONF" nm.domain.name)}"; nmdom="${nmdom:-nmdomain}"
    ksdir="${DOMAINS_DIR}/${nmdom}/config/certs"   # NM's own domain, where do_nmdomain placed its identity/trust
    nmpw="${BLADE_NM_KEYSTORE_PASSWORD:-}"; [ -z "$nmpw" ] && [ -f "$WLS_SECRET" ] && nmpw="$(read_prop "$WLS_SECRET" nm.keystore.passphrase)"
    if [ -f "${ksdir}/nm-trust.p12" ] && [ -n "$nmpw" ]; then
        printf '%s' "-Dweblogic.security.TrustKeyStore=CustomTrust -Dweblogic.security.CustomTrustKeyStoreFileName=${ksdir}/nm-trust.p12 -Dweblogic.security.CustomTrustKeyStoreType=PKCS12 -Dweblogic.security.CustomTrustKeyStorePassPhrase=${nmpw} ${common}"
        return 0
    fi
    local trpw="${BLADE_TRUST_PASSWORD:-}"; [ -z "$trpw" ] && [ -f "$WLS_SECRET" ] && trpw="$(read_prop "$WLS_SECRET" tls.trust.passphrase)"
    if [ -f "${ksdir}/blade-trust.p12" ] && [ -n "$trpw" ]; then
        printf '%s' "-Dweblogic.security.TrustKeyStore=CustomTrust -Dweblogic.security.CustomTrustKeyStoreFileName=${ksdir}/blade-trust.p12 -Dweblogic.security.CustomTrustKeyStoreType=PKCS12 -Dweblogic.security.CustomTrustKeyStorePassPhrase=${trpw} ${common}"
        return 0
    fi
    warn "Node Manager trust: ${ksdir}/nm-trust.p12 or nm.keystore.passphrase missing — re-run the Node Manager step to (re)configure it (demo certs are not used)." >&2
    printf '%s' "$common"
    return 0
}

# Stop Node Manager and start it again from its own domain. Used after an
# enrollment; also available as its own step.
restart_nm() {
    local mw="${MWHOME:-$(read_prop "$OCCAS_CONF" oracle.home)}"
    local nmdom="${NM_DOMAIN:-$(read_prop "$OCCAS_CONF" nm.domain.name)}"
    local port="${NM_PORT:-$(read_prop "$OCCAS_CONF" nm.listen.port)}"; port="${port:-5556}"
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    [ -d "$nmhome" ] || { warn "nmdomain not found: ${nmhome}"; return 1; }
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] restart Node Manager (${nmhome})${C_RESET}"; return 0; fi
    local IU_USER; IU_USER="$(iu_owner_user "$nmhome")"   # run as the tree's owner

    # A restart must fully CYCLE the process before returning. The caller
    # (register_domain_with_nm, inside the AdminServer start) connects to NM the
    # instant this returns; if the OLD NM is still shutting down, that nmConnect
    # is severed mid-nmStart ("Unexpected end of stream") and the half-launched
    # server is left FAILED_NOT_RESTARTABLE. systemd's ExecStop is
    # stopNodeManager.sh -- a JVM that can take ~10s to land its TERM -- so a bare
    # `systemctl restart` (or a port check right after it) can report "up" while a
    # shutdown is still in flight. So: stop, wait for the listener to actually
    # DROP, then start and wait for it to come back.
    # Prefer the boot service when it's installed and owns this nmdomain: that
    # keeps systemd's idea of the process and ours from diverging.
    local svc_systemd=0
    if command -v systemctl >/dev/null 2>&1 \
       && grep -qsF -- "$nmhome" /etc/systemd/system/nodemanager.service; then
        svc_systemd=1
    fi
    if [ "$svc_systemd" = 1 ]; then
        sudo systemctl stop nodemanager.service 2>/dev/null || true
    else
        stop_nm || true
    fi
    # Confirm the old NM is GONE (listener released) before starting a new one --
    # this is the step that closes the race the port check alone missed.
    local j=0
    while nm_listening "$port" && [ "$j" -lt 30 ]; do sleep 1; j=$((j + 1)); done
    nm_listening "$port" && warn "old Node Manager still holding :${port} after 30s — starting anyway."
    if [ "$svc_systemd" = 1 ]; then
        sudo systemctl start nodemanager.service 2>/dev/null || { warn "systemctl start nodemanager.service failed."; return 1; }
    else
        # Match do_nmdomain: NM's .out lives in LOG_DIR (created there when the NM
        # was set up). Fall back under the domain only if that dir isn't present.
        local nmlogdir="${LOG_DIR:-/var/log/weblogic}/nodemanager"
        [ -d "$nmlogdir" ] || nmlogdir="${nmhome}/nodemanager"
        local nmlog="${nmlogdir}/nodemanager.out"
        as_install_user sh -c "JAVA_HOME='${JAVA_HOME_VAL:-${JAVA_HOME:-}}' nohup '${nmhome}/bin/startNodeManager.sh' > '${nmlog}' 2>&1 &"
    fi
    local i=0
    while [ "$i" -lt 40 ]; do
        nm_listening "$port" && { ok "Node Manager restarted, listening on :${port}."; return 0; }
        sleep 1; i=$((i + 1))
    done
    warn "Node Manager did not come back on :${port} within 40s."
    return 1
}

# Write <domain>/bin/setUserOverrides.sh. NM-launched servers do NOT source it:
# Node Manager runs MBean-mode (weblogic.StartScriptEnabled=false), building the
# java line from each server's ServerStart MBean (emit_serverstart_block), which
# carries the same server.mem.args baseline and the NM hostname-verification
# flag. This hook remains for anyone running startWebLogic.sh BY HAND — a
# hand-run server still gets sane memory (the OCCAS dev default -Xmx512m
# -XX:MaxMetaspaceSize=256m OOMs on Metaspace when the admin EAR deploys) and
# the same flags. Tune with server.mem.args in occas.conf; idempotent.
write_user_overrides() {
    local domhome="$1" mem
    [ -d "${domhome}/bin" ] || return 0
    local IU_USER; IU_USER="$(iu_owner_user "$domhome")"   # write as the domain's owner
    mem="$(read_prop "$OCCAS_CONF" server.mem.args)"
    mem="${mem:--Xms512m -Xmx1024m -XX:MaxMetaspaceSize=1g}"
    iu_write "${domhome}/bin/setUserOverrides.sh" 755 <<EOF
# BLADE - generated by install.sh. NM-launched servers do NOT run this: Node
# Manager starts servers MBean-mode from their config.xml ServerStart MBeans
# (Tuning drives the JVM args there). This hook covers HAND-RUN start scripts
# only — startWebLogic.sh sources setDomainEnv.sh, which sources this, so a
# manual start still gets the same memory baseline (the OCCAS dev default
# -Xmx512m -XX:MaxMetaspaceSize=256m OOMs on Metaspace when the admin EAR
# deploys) and flags. Change server.mem.args in occas.conf and re-run
# configure (or 's') to update.
USER_MEM_ARGS="${mem}"
export USER_MEM_ARGS
# The AdminServer's built-in Node Manager client verifies each machine's NM
# certificate hostname; VCN-internal addresses (private IPs, OCI metadata
# FQDNs) don't reliably match the identity cert's SANs, and our CA trust is
# the real authentication. This is Oracle's documented switch for the
# AdminServer->NM path; the servers' own SSL is covered by
# HostnameVerificationIgnored in config.xml.
JAVA_OPTIONS="\${JAVA_OPTIONS} -Dweblogic.nodemanager.sslHostNameVerificationEnabled=false"
export JAVA_OPTIONS
EOF
    log "  ${C_DIM}wrote setUserOverrides.sh — server memory: ${mem}${C_RESET}"
}

# Start or stop the AdminServer via Node Manager. action = start | kill.
nm_admin() {
    local action="$1" oh="$2" dom="$3" auser="$4"
    local domhome="${DOMAINS_DIR}/${dom}"
    local nmport="${NM_PORT:-$(read_prop "$OCCAS_CONF" nm.listen.port)}"; nmport="${nmport:-5556}"
    local nmtype="${NM_TYPE:-$(read_prop "$OCCAS_CONF" nm.type)}"; nmtype="${nmtype:-ssl}"
    local verb="Starting"; [ "$action" = "kill" ] && verb="Stopping"
    if [ "$DRY" = "on" ]; then
        [ "$action" = "start" ] && log "${C_DIM}  [dry-run] enroll ${dom} → ${domhome} in nodemanager.domains${C_RESET}"
        log "${C_DIM}  [dry-run] nmConnect ${auser}@localhost:${nmport} (${nmtype}); nm${action} AdminServer${C_RESET}"
        return 0
    fi
    [ -d "$domhome" ] || { warn "app domain not found: ${domhome} — create it first (configure)."; return 1; }
    nm_listening "$nmport" || { warn "Node Manager isn't listening on :${nmport} — start the Node Manager first."; return 1; }
    # Everything below touches or runs against the EXISTING domain — as its owner.
    local IU_USER; IU_USER="$(iu_owner_user "$domhome")"
    # Starting needs the domain enrolled (no-op if already) + adequate launch memory.
    [ "$action" = "start" ] && { register_domain_with_nm "$dom" "$domhome" || true; write_user_overrides "$domhome"; }
    # NM credentials = the admin creds (env > the config > misc/.nmsecret).
    # The .nmsecret fallback is resolved HERE, not in the piped script: under
    # `bash -s` its $(dirname "$0") is the CWD, not misc/, and the checkout
    # isn't readable by the install user anyway.
    local pw="${BLADE_WLS_PASSWORD:-}"
    [ -z "$pw" ] && [ -f "$WLS_SECRET" ] && pw="$(read_prop "$WLS_SECRET" admin.password)"
    [ -z "$pw" ] && [ -f "${SCRIPT_DIR}/misc/.nmsecret" ] && pw="$(read_prop "${SCRIPT_DIR}/misc/.nmsecret" NM_PASSWORD)"
    # Production mode dies on a missing boot identity (BEA-090782).
    [ "$action" = "start" ] && write_boot_properties "$domhome" "AdminServer" "$auser" "$pw"
    info "${verb} AdminServer for '${dom}' via Node Manager localhost:${nmport} (${nmtype})"
    # NM's listener is SSL; without a truststore WLST fails with a bare PKIX error.
    local wlstp; wlstp="$(nm_wlst_props)"
    # Runs as the install user, who cannot read the repo checkout — so the
    # script goes over stdin (bash -s), and the environment travels WITH it:
    # a sudo env argv would put the password where ps can read it.
    # The piped script can't prompt (its stdin IS the script) — prompt here,
    # where the terminal still is, exactly as start-admin-nm.sh used to.
    if [ -z "$pw" ] && [ -t 0 ]; then
        read -rs -p "  Node Manager password for ${auser}: " pw || pw=""; echo
    fi
    [ -z "$pw" ] && { warn "no admin password (env / the config / misc/.nmsecret) — cannot nmConnect; aborting the ${action}."; return 1; }
    { printf 'export MW_HOME=%q DOMAIN_NAME=%q DOMAIN_HOME=%q ADMIN_SERVER=%q NM_ACTION=%q\n' \
             "$oh" "$dom" "$domhome" "AdminServer" "$action"
      printf 'export NM_HOST=localhost NM_PORT=%q NM_USER=%q NM_TYPE=%q NM_PASSWORD=%q\n' \
             "$nmport" "$auser" "$nmtype" "$pw"
      printf 'export WLST_PROPERTIES=%q\n' "$wlstp"
      cat "${SCRIPT_DIR}/misc/start-admin-nm.sh"
    } | as_install_user bash -s || warn "start-admin-nm returned an error"
}
start_admin() { nm_admin start "$@"; }

# Stop the AdminServer (+ any of the domain's servers). nmKill is unreliable with
# pure-Java Node Manager (NativeVersionEnabled=false, required on aarch64) when a
# server is script-launched with a child JVM, so we stop at the OS level.
# Read-only cluster health check. Catches the failure classes that otherwise only
# show up as a dead boot service: an unresolved JDK, an SELinux-unlabeled domain
# (block-volume trap), a missing NM log dir, a custom App Router jar larger than the
# T3 MaxMessageSize, a node whose NM/engine isn't running. Runs each node's checks
# locally on the admin (index 0) and over ssh elsewhere.
# Boot-time proof for the SSL identity keystore: decrypt config.xml's stored
# passphrase with the domain's OWN crypto service (SerializedSystemIni) and load
# blade-identity.p12 with it -- exactly what a server does at startup. This
# catches a silent, boot-fatal drift the plaintext secret cannot: when the p12
# and ~/.blade/<env>.conf agree but config.xml carries a STALE encrypted
# passphrase (a from-scratch rebuild can leave config.xml stamped from an older
# passphrase value), the server dies in startup with a cryptic Coherence
# "keystore password was incorrect" NPE, not an SSL error. Runs offline, on the
# admin box, as the domain owner. The cleartext passphrase lives only inside the
# JVM; only the already-on-disk ciphertext and the keystore path leave config.xml.
# Echoes one word-prefixed line: OK <entries> | FAILS <msg> | SKIP <why>.
verify_domain_keystore() {
    local domhome="$1" cfg="${domhome}/config/config.xml"
    local mw="${MWHOME:-$(read_prop "$OCCAS_CONF" oracle.home)}"; mw="${mw:-/opt/oracle/occas/current}"
    as_install_user test -x "${mw}/wlserver/server/bin/setWLSEnv.sh" 2>/dev/null \
        || { printf 'SKIP no setWLSEnv.sh under %s' "$mw"; return 0; }
    local enc ksf
    enc="$(as_install_user grep -om1 '<custom-identity-key-store-pass-phrase-encrypted>[^<]*' "$cfg" 2>/dev/null | sed 's/.*>//')"
    ksf="$(as_install_user grep -om1 '<custom-identity-key-store-file-name>[^<]*' "$cfg" 2>/dev/null | sed 's/.*>//')"
    [ -n "$enc" ] && [ -n "$ksf" ] || { printf 'SKIP no custom identity keystore in config.xml'; return 0; }
    local jh; jh="$(read_prop "$OCCAS_CONF" java.home)"
    local work; work="$(mktemp -d /tmp/blade-ksv.XXXXXX)" || { printf 'SKIP mktemp failed'; return 0; }
    # Unquoted heredoc: the shell interpolates the domain home, the ciphertext and
    # the keystore path. weblogic.WLST is just the Jython launcher here -- no
    # readDomain (offline WLST refuses the decrypted passphrase getter anyway); the
    # domain's crypto service does the decrypt straight from SerializedSystemIni.dat.
    cat > "${work}/ksverify.py" <<PYEOF
# -*- coding: utf-8 -*-
from weblogic.security.internal import SerializedSystemIni
from weblogic.security.internal.encryption import ClearOrEncryptedService
from java.security import KeyStore
from java.io import FileInputStream, File
dh = '${domhome}'
try:
    pw = ClearOrEncryptedService(SerializedSystemIni.getEncryptionService(dh)).decrypt('${enc}')
    p = '${ksf}'
    if not File(p).isAbsolute():
        p = dh + '/' + p.replace('./', '', 1)
    ks = KeyStore.getInstance('PKCS12')
    ks.load(FileInputStream(p), pw)
    print('KSV_OK ' + str(ks.size()))
except Exception, e:
    print('KSV_FAIL ' + str(e))
PYEOF
    chmod 600 "${work}/ksverify.py"
    local out
    out="$(iu_wlst_run "$work" ksverify.py "$mw" "$jh" 2>/dev/null | grep -oE 'KSV_(OK|FAIL) .*')"
    as_install_user rm -rf "$work"
    case "$out" in
        'KSV_OK '*)   printf 'OK %s' "${out#KSV_OK }" ;;
        'KSV_FAIL '*) printf 'FAILS %s' "${out#KSV_FAIL }" ;;
        *)            printf 'SKIP keystore probe produced no result' ;;
    esac
}

do_verify() {
    local sshu="${SSH_USER:-$(id -un)}" domhome="${DOMAINS_DIR}/${DOMAIN}" i ok_n=0 bad_n=0
    _vok()  { ok "    $1";   ok_n=$((ok_n + 1)); }
    _vbad() { warn "    $1"; bad_n=$((bad_n + 1)); }
    # $1 = host index; $2.. = command. Local for the admin (0), ssh otherwise.
    _vrun() { local idx="$1"; shift
        if [ "$idx" -eq 0 ]; then bash -c "$*" 2>/dev/null
        else ssh -o BatchMode=yes -o ConnectTimeout=8 "${sshu}@${H_ADDR[$idx]}" "$*" 2>/dev/null; fi; }

    info "Verifying '${DOMAIN}' across ${#H_NAME[@]} machine(s)…"

    # --- domain/config sanity (config.xml + sipserver.xml on the admin) ---
    log "  ${C_BOLD}config${C_RESET}"
    local cfg="${domhome}/config/config.xml" mms=""
    if as_install_user test -f "$cfg" 2>/dev/null; then
        mms="$(as_install_user grep -om1 '<max-message-size>[0-9]*' "$cfg" 2>/dev/null | grep -o '[0-9]*' | head -1)"
        [ -n "$mms" ] && _vok "MaxMessageSize=${mms}" \
            || _vbad "no <max-message-size> — T3 default 10 MB; a >10 MB App Router jar will fail the fetch"
        local jar="${domhome}/approuter/blade-fsmar.jar" jsz=""
        as_install_user test -f "$jar" 2>/dev/null && jsz="$(as_install_user stat -c %s "$jar" 2>/dev/null)"
        if [ -n "$jsz" ] && [ -n "$mms" ] && [ "$jsz" -ge "$mms" ]; then
            _vbad "FSMAR jar ${jsz} B >= MaxMessageSize ${mms} B — AR fetch will blow the T3 cap"
        elif [ -n "$jsz" ]; then _vok "FSMAR jar ${jsz} B fits under MaxMessageSize"; fi
        as_install_user grep -q 'use-custom-app-router>true' "${domhome}/config/custom/sipserver.xml" 2>/dev/null \
            && _vok "FSMAR is the active App Router" || _vbad "custom App Router OFF — routing on the DefaultAR"
        # Prove the SSL identity boots: decrypt config.xml's keystore passphrase
        # and open blade-identity.p12 (a ~15s offline WLST call). A stale config.xml
        # passphrase boots to a cryptic Coherence keystore NPE, so catch it here.
        local ksv; ksv="$(verify_domain_keystore "$domhome")"
        case "$ksv" in
            'OK '*)    _vok "SSL identity: config.xml passphrase opens blade-identity.p12 (${ksv#OK } entr$([ "${ksv#OK }" = 1 ] && echo y || echo ies))" ;;
            'FAILS '*) _vbad "SSL identity: config.xml passphrase does NOT open blade-identity.p12 — server will fail startup (${ksv#FAILS })" ;;
            *)         log "    ${C_DIM}SSL identity: keystore check skipped (${ksv#SKIP })${C_RESET}" ;;
        esac
    else
        _vbad "config.xml not found (${cfg}) — domain not built on this host"
    fi

    # --- per-machine runtime checks ---
    for i in "${!H_NAME[@]}"; do
        log "  ${C_BOLD}${H_NAME[$i]}${C_RESET} ${C_DIM}(${H_ADDR[$i]}, ${H_ROLE[$i]})${C_RESET}"
        if ! _vrun "$i" "true"; then _vbad "unreachable"; continue; fi
        [ "$(_vrun "$i" "sudo systemctl is-active nodemanager.service")" = active ] \
            && _vok "Node Manager active" || _vbad "Node Manager NOT active"
        _vrun "$i" "test -x /opt/oracle/java/current/bin/java && echo y" | grep -qx y \
            && _vok "JDK link resolves" || _vbad "/opt/oracle/java/current/bin/java missing"
        _vrun "$i" "test -d ${LOG_DIR:-/var/log/weblogic}/nodemanager && echo y" | grep -qx y \
            && _vok "NM log dir present" || _vbad "${LOG_DIR:-/var/log/weblogic}/nodemanager missing"
        case "$(_vrun "$i" "sudo ls -Z ${DOMAINS_DIR}/${NM_DOMAIN}/bin/startNodeManager.sh 2>/dev/null")" in
            *unlabeled_t*) _vbad "domain scripts SELinux unlabeled_t — run 'restorecon -R'" ;;
            *:*)           _vok "SELinux label ok" ;;
        esac
    done
    rule
    [ "$bad_n" -eq 0 ] && ok "verify: ${ok_n} checks passed, no issues." \
                       || warn "verify: ${bad_n} issue(s) found (${ok_n} ok)."
    return 0
}

stop_admin() {
    local oh="$1" dom="$2" auser="${3:-weblogic}"
    local domhome="${DOMAINS_DIR}/${dom}"
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] graceful WLST shutdown of AdminServer (OS-signal only a hung remainder)${C_RESET}"; return 0; fi
    [ -d "$domhome" ] || { warn "app domain not found: ${domhome}."; return 1; }
    [ -n "$(_server_pids_for "$domhome")" ] || { ok "no running servers for '${dom}'."; return 0; }
    # A controlled shutdown, NOT a signal: connect to the AdminServer and shutdown()
    # so it drains and stops its services cleanly. Over t3s with the domain trust
    # store (the AdminServer presents the blade identity); we OS-signal only a
    # server still up afterwards (hung).
    local pw; pw="${BLADE_WLS_PASSWORD:-}"; [ -z "$pw" ] && [ -f "$WLS_SECRET" ] && pw="$(read_prop "$WLS_SECRET" admin.password)"
    if [ -n "$pw" ]; then
        local url; url="$(_wls_adminurl)"
        # t3s needs the blade CA trusted, else a bare PKIX error. Same store the
        # servers load (blade-trust.p12 in the domain's config/certs).
        local wlp=""
        case "$url" in t3s://*)
            local trustpw; trustpw="${BLADE_TRUST_PASSWORD:-}"; [ -z "$trustpw" ] && [ -f "$WLS_SECRET" ] && trustpw="$(read_prop "$WLS_SECRET" tls.trust.passphrase)"
            wlp="-Dweblogic.security.SSL.ignoreHostnameVerification=true -Dweblogic.security.TrustKeyStore=CustomTrust -Dweblogic.security.CustomTrustKeyStoreFileName=${KEYSTORE_DIR}/blade-trust.p12 -Dweblogic.security.CustomTrustKeyStoreType=PKCS12 -Dweblogic.security.CustomTrustKeyStorePassPhrase=${trustpw}"
            ;;
        esac
        info "Shutting down AdminServer for '${dom}' — graceful WLST shutdown (${url}) …"
        local work; work="$(mktemp -d /tmp/blade-shut.XXXXXX)"
        cat > "${work}/shutdown.py" <<PYEOF
# -*- coding: utf-8 -*-
try:
    connect('${auser}', '${pw}', '${url}')
    try:
        shutdown('AdminServer', 'Server', force='false', block='true')
        print('AdminServer shut down cleanly.')
    except Exception, se:
        # Shutting down the server we are connected to severs the connection --
        # expected; the controlled shutdown still proceeds.
        print('shutdown issued (connection dropped, expected): ' + str(se))
except Exception, e:
    print('could not connect to the AdminServer: ' + str(e))
    exit(exitcode=1)
PYEOF
        chmod 600 "${work}/shutdown.py"
        local jh; jh="$(read_prop "$OCCAS_CONF" java.home)"
        iu_wlst_run "$work" shutdown.py "${MWHOME:-$oh}" "$jh" "$wlp" || warn "graceful shutdown WLST returned an error (see above)."
        as_install_user rm -rf "$work"
        # Give the controlled shutdown time to finish before resorting to a signal.
        local i=0
        while [ "$i" -lt 40 ]; do
            [ -z "$(_server_pids_for "$domhome")" ] && { ok "AdminServer stopped (graceful)."; return 0; }
            sleep 1; i=$((i + 1))
        done
        warn "AdminServer still up ${i}s after graceful shutdown — OS-stopping the remainder."
    else
        warn "no admin password (env / config) — can't do a graceful WLST shutdown; using a signal."
    fi
    kill_domain_procs "$domhome"
}

# Synchronously kill the JVMs belonging to a domain (matched by domain home in
# their cmdline — never a blind pkill). Waits for exit, escalates to SIGKILL.
# PIDs (space-separated) of this domain's running WebLogic servers on this box.
_server_pids_for() {
    local home="$1" p cmd out=""
    command -v pgrep >/dev/null 2>&1 || return 0
    for p in $(pgrep -f weblogic.Name 2>/dev/null || true); do
        cmd="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)"
        case "$cmd" in *"$home"*) out="${out} ${p}" ;; esac
    done
    printf '%s' "${out# }"
}

kill_domain_procs() {
    local home="$1" p pids n=0 i=0
    command -v pgrep >/dev/null 2>&1 || { warn "no pgrep — can't OS-stop servers."; return 1; }
    # Signal as the domain's owner (see stop_nm) — EPERM is a silent no-op kill.
    local IU_USER; IU_USER="$(iu_owner_user "$home")"
    pids="$(_server_pids_for "$home")"
    [ -n "$pids" ] || { ok "no running servers for $(basename "$home")."; return 0; }
    for p in $pids; do as_install_user kill "$p" 2>/dev/null && n=$((n + 1)); done
    ok "signaled ${n} server process(es) for $(basename "$home") — waiting for exit…"
    while [ "$i" -lt 20 ]; do
        local alive=0; for p in $pids; do proc_alive "$p" && alive=1; done
        [ "$alive" = 0 ] && { ok "servers stopped."; return 0; }
        sleep 1; i=$((i + 1))
    done
    warn "servers still up after ${i}s — SIGKILL."
    for p in $pids; do as_install_user kill -9 "$p" 2>/dev/null || true; done
    return 0
}

# Reset: stop the app domain's servers, un-enroll it from NM, delete it, AND
# delete this profile's configuration (.conf/<name>/) — so the next install
# starts from a clean slate with nothing left behind. The stable nmdomain is
# left running. Sets PROFILE_GONE so the dashboard drops back to the picker.
do_remove_domain() {
    local oh="$1" dom="$2" auser="${3:-weblogic}"
    local domhome="${DOMAINS_DIR}/${dom}"
    [ -n "$dom" ] || { warn "no domain name."; return 1; }
    local have_dom=0;  [ -d "$domhome" ] && have_dom=1
    local have_prof=0; [ -n "${BLADE_CONF:-}" ] && [ -f "$BLADE_CONF" ] && have_prof=1
    if [ "$have_dom" = 0 ] && [ "$have_prof" = 0 ]; then
        ok "domain '${dom}' and its profile already gone — nothing to remove."; return 0
    fi
    # The uninstall ladder sets KEEP_PROFILE so an iterate-fast reinstall can reuse
    # the profile's config + secrets; interactive 'r' clears it (removes both).
    local profnote="; rm ${BLADE_CONF}"; local proflabel=" AND profile '${NAME}'"
    if [ "${KEEP_PROFILE:-0}" = 1 ]; then profnote=" (keeping the profile config)"; proflabel=""; fi
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] stop AdminServer; kill stray JVMs; un-enroll ${dom}; rm -rf ${domhome}; remove weblogic.service (if it points here)${profnote}${C_RESET}"
        [ "$have_dom" = 1 ] && remove_domain_systemd_unit "$domhome" weblogic.service
        remove_engine_systemd_units "$domhome" "${DOMAINS_DIR}/${NM_DOMAIN}"
        return 0
    fi
    yesno "Remove domain '${dom}'${proflabel}? Stops its servers, DELETES ${domhome}, removes its weblogic.service unit${proflabel:+, and erases the profile config + secrets at ${BLADE_CONF}}." "N" \
        || { warn "kept '${dom}'${proflabel} — nothing removed."; return 1; }
    # Safety net before anything irreversible (skippable with --no-backup).
    [ "${BACKUP:-1}" = 1 ] && [ "$have_dom" = 1 ] && do_backup || true
    if [ "$have_dom" = 1 ]; then
        stop_admin "$oh" "$dom" "$auser" || true
        kill_domain_procs "$domhome"
        local nmdom="${NM_DOMAIN:-$(read_prop "$OCCAS_CONF" nm.domain.name)}"
        local nmfile="${DOMAINS_DIR}/${nmdom}/nodemanager/nodemanager.domains"
        local IU_USER   # retargeted per tree: the enrollment file is the
                        # NM domain's, the deletion below is the app domain's
        if [ -f "$nmfile" ] && grep -q "^${dom}=" "$nmfile"; then
            IU_USER="$(iu_owner_user "${DOMAINS_DIR}/${nmdom}")"
            local tmp; tmp="$(mktemp)" && { grep -v "^${dom}=" "$nmfile" || true; } > "$tmp" \
                && iu_write "$nmfile" 644 < "$tmp" && rm -f "$tmp" \
                && ok "un-enrolled '${dom}' from ${nmdom} (restart Node Manager (stop, then start) to apply)."
        fi
        remove_domain_systemd_unit "$domhome" weblogic.service
        # Engines first: once $domhome is gone the guard below can't match.
        remove_engine_systemd_units "$domhome" "${DOMAINS_DIR}/${NM_DOMAIN}"
        IU_USER="$(iu_owner_user "$domhome")"
        as_install_user rm -rf "$domhome" && ok "removed ${domhome}."
    fi
    # Delete the profile last: the domain teardown above reads OCCAS_CONF, which
    # lives inside the profile directory we're about to remove. Skipped when the
    # uninstall ladder asked to keep it (KEEP_PROFILE) for a fast reinstall.
    if [ "$have_prof" = 1 ] && [ "${KEEP_PROFILE:-0}" != 1 ]; then
        rm -f "$BLADE_CONF" "$BLADE_LOG" "$URLS_FILE" && { ok "removed profile '${NAME}' (${BLADE_CONF})."; PROFILE_GONE=1; }
    elif [ "$have_prof" = 1 ]; then
        ok "kept profile '${NAME}' (${BLADE_CONF}) — reinstall with: ./install.sh ${NAME} install"
    fi
}

# Reset: stop Node Manager, remove its systemd unit (if installed), and delete
# the standalone nmdomain — so a reinstall recreates it from Oracle's template.
# Independent of the app domain/profile: app domains enrolled in this NM are
# untouched on disk, but must be re-enrolled (run 'c', or 'n' then start) after
# the NM is rebuilt. Handy when iterating on install quirks.
do_remove_nmdomain() {
    local mw="$MWHOME" nmdom="$NM_DOMAIN"
    [ -n "$nmdom" ] || { warn "no nm.domain.name."; return 1; }
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    if [ "$DRY" = "on" ]; then
        stop_nm || true
        remove_domain_systemd_unit "$nmhome" nodemanager.service
        log "${C_DIM}  [dry-run] rm -rf ${nmhome}${C_RESET}"
        return 0
    fi
    if [ ! -d "$nmhome" ]; then
        ok "Node Manager domain '${nmdom}' not present — checking for a matching systemd unit only."
        remove_domain_systemd_unit "$nmhome" nodemanager.service
        return 0
    fi
    yesno "Remove Node Manager domain '${nmdom}' at ${nmhome}? Stops NM, DELETES the domain, and removes its nodemanager.service unit if that unit points here." "N" \
        || { warn "kept '${nmdom}' — nothing removed."; return 1; }
    stop_nm || true
    remove_domain_systemd_unit "$nmhome" nodemanager.service
    local IU_USER; IU_USER="$(iu_owner_user "$nmhome")"
    as_install_user rm -rf "$nmhome" && ok "removed ${nmhome}."
}

# --- uninstall the rest of STEP 1 (deinstall product, dirs, user, repo) -----
# These are the inverses of do_install / do_makedirs / do_makeuser plus the repo
# clone itself, so a machine can be returned all the way to pre-BLADE state. Each
# confirms (yesno "N") and honours dry-run, mirroring do_remove_domain/nmdomain.

# Deinstall the OCCAS product with Oracle's own deinstaller (inverse of 'i'). The
# deinstaller detaches ORACLE_HOME from the central inventory and removes the
# software; run it as the install user since the Oracle home is owned by them. If
# it's missing, the 'md' row (remove dirs) is the blunt fallback — but that leaves
# a stale inventory entry, so prefer this.
do_deinstall() {
    local mw="$MWHOME" user="${INSTALL_USER:-oracle}"
    [ -n "$mw" ] || { warn "no oracle.home (MW_HOME)."; return 1; }
    local deinst="${mw}/oui/bin/deinstall.sh"
    if [ ! -d "${mw}/wlserver" ]; then ok "no OCCAS product at ${mw} — nothing to deinstall."; return 0; fi
    local SUDO=""; [ "$(id -u)" -ne 0 ] && [ "$(id -un)" != "$user" ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo -u ${user}"
    if [ "$DRY" = "on" ]; then
        if [ -f "$deinst" ]; then log "${C_DIM}  [dry-run] ${SUDO:+${SUDO} }${deinst} -silent${C_RESET}"
        else log "${C_DIM}  [dry-run] deinstaller absent — would fall back to the 'Remove install dirs' row for ${mw}${C_RESET}"; fi
        return 0
    fi
    if [ ! -f "$deinst" ]; then
        warn "Oracle deinstaller not found at ${deinst} — use the 'Remove install dirs' row to delete ${mw} (that leaves a stale central-inventory entry)."
        return 1
    fi
    yesno "Deinstall the OCCAS product at ${mw}? Runs Oracle's deinstaller (detaches the central inventory and removes the software)." "N" \
        || { warn "kept the OCCAS product at ${mw}."; return 1; }
    if $SUDO "$deinst" -silent 2>&1 | strip_jdk_noise; then ok "deinstalled the OCCAS product at ${mw}."
    else warn "Oracle deinstaller returned an error — you may need the 'Remove install dirs' row."; return 1; fi
}

# Remove the install dirs (inverse of 'm'): MW_HOME + the central inventory dir.
do_remove_dirs() {
    local mw="$MWHOME"
    local inv; inv="$(read_prop "$OCCAS_CONF" inventory.loc)"; inv="${inv:-${INV_LOC:-}}"
    [ -n "$mw" ] || { warn "no oracle.home (MW_HOME)."; return 1; }
    local SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"
    if [ ! -d "$mw" ] && { [ -z "$inv" ] || [ ! -d "$inv" ]; }; then
        ok "install dirs already gone — nothing to remove."; return 0
    fi
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] ${SUDO:+sudo }rm -rf ${mw}${inv:+ ${inv}}${C_RESET}"; return 0
    fi
    yesno "Delete install dirs? rm -rf ${mw}${inv:+ and ${inv}}. Removes the OCCAS software tree and central inventory." "N" \
        || { warn "kept ${mw}."; return 1; }
    if [ -d "$mw" ]; then $SUDO rm -rf "$mw" && ok "removed ${mw}."; fi
    if [ -n "$inv" ] && [ -d "$inv" ]; then $SUDO rm -rf "$inv" && ok "removed ${inv}."; fi
}

# Remove the install user & group (inverse of 'u'). userdel -r takes the home dir
# with it, so run this AFTER the dirs row if MW_HOME lives under that home. Guarded
# hard — this is a real OS account that may not be OCCAS-only.
do_remove_usergrp() {
    local user="${INSTALL_USER:-oracle}" grp="${INV_GRP:-oinstall}"
    [ "$(uname -s)" = "Linux" ] || { warn "user/group removal is Linux-only."; return 0; }
    local SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"
    local has_u=0; id "$user" >/dev/null 2>&1 && has_u=1
    local has_g=0; getent group "$grp" >/dev/null 2>&1 && has_g=1
    if [ "$has_u" = 0 ] && [ "$has_g" = 0 ]; then ok "user '${user}' and group '${grp}' already gone."; return 0; fi
    if [ "$DRY" = "on" ]; then
        [ "$has_u" = 1 ] && log "${C_DIM}  [dry-run] ${SUDO:+sudo }userdel -r ${user}${C_RESET}"
        [ "$has_g" = 1 ] && log "${C_DIM}  [dry-run] ${SUDO:+sudo }groupdel ${grp}${C_RESET}"
        return 0
    fi
    [ "$(id -un)" = "$user" ] && { warn "refusing to delete '${user}' — that's the account you're running as."; return 1; }
    yesno "Delete OS user '${user}' (userdel -r, removes its home) and group '${grp}'? This affects the whole machine, not just OCCAS." "N" \
        || { warn "kept user '${user}' / group '${grp}'."; return 1; }
    if [ "$has_u" = 1 ]; then
        $SUDO userdel -r "$user" 2>/dev/null && ok "removed user '${user}'." || warn "could not fully remove '${user}' (still logged in or running processes?)."
    fi
    if [ "$has_g" = 1 ]; then
        $SUDO groupdel "$grp" 2>/dev/null && ok "removed group '${grp}'." || warn "could not remove group '${grp}' (still a primary group for another user?)."
    fi
}

# Delete the LOCAL BLADE repo clone (inverse of 'git clone'). NEVER touches the
# GitHub remote. install.sh is running from inside this tree, so we detach the rm to
# a background shell that fires after we exit, then set REPO_GONE so the dashboard
# drops out cleanly instead of redrawing from a directory that's about to vanish.
do_remove_repo() {
    local dir="$SCRIPT_DIR"
    { [ -n "$dir" ] && [ -d "$dir" ]; } || { warn "can't locate the repo dir."; return 1; }
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] rm -rf ${dir}  (local clone only — GitHub remote untouched)${C_RESET}"; return 0
    fi
    yesno "Delete the LOCAL BLADE repo clone at ${dir}? Removes install.sh and everything here. Your GitHub remote is NOT affected." "N" \
        || { warn "kept the repo at ${dir}."; return 1; }
    # Detach so this process finishes before its own script tree is unlinked.
    nohup sh -c "sleep 1; rm -rf '$dir'" >/dev/null 2>&1 &
    ok "removing ${dir} (local clone) — BLADE will exit now. GitHub is untouched."
    REPO_GONE=1
}

# ============================================================================
# Deploy is deploy.sh's job — not install.sh's. App deployment (one artifact, or
# --all in dependency order) lives entirely in deploy.sh, which reads THIS profile
# (~/.blade/<env>/profile.conf) and drives the wlst engine (misc/deploy-wls.sh).
# The two helpers below stay because they compute values install.sh writes INTO
# the profile for deploy.sh to read: _wls_adminurl -> wls.adminurl, and
# _test_target -> wls.targets.test.
# ============================================================================

# Authoritative AdminServer t3/t3s URL from the live domain config (the server
# often binds the host IP, not localhost). Falls back to deploy.conf, then
# localhost. TLS everywhere: when the AdminServer's <ssl> block is enabled,
# prefer t3s on the SSL port — mandatory once TLS is on (install.sh row 't')
# with tls.only (the plaintext port is disabled then).
_wls_adminurl() {
    local cfg="${DOMAINS_DIR}/${DOMAIN}/config/config.xml" addr="" port="" blk
    local scheme="t3" sslblk sslon="" sslport=""
    if [ -f "$cfg" ]; then
        blk="$(awk '/<server>/{b=""} {b=b"\n"$0} /<\/server>/{ if (b ~ /<name>AdminServer<\/name>/){print b; exit} }' "$cfg")"
        addr="$(printf '%s' "$blk" | grep -om1 '<listen-address>[^<]*' | sed 's/.*>//')"
        port="$(printf '%s' "$blk" | grep -om1 '<listen-port>[0-9]*'  | sed 's/.*>//')"
        sslblk="$(printf '%s' "$blk" | awk '/<ssl>/,/<\/ssl>/')"
        sslon="$(printf '%s' "$sslblk" | grep -om1 '<enabled>[^<]*' | sed 's/.*>//')"
        sslport="$(printf '%s' "$sslblk" | grep -om1 '<listen-port>[0-9]*' | sed 's/.*>//')"
    fi
    if [ "$sslon" = "true" ]; then
        scheme="t3s"; port="${sslport:-7002}"
    elif [ -z "$addr" ]; then
        # No live config readable — honor the deploy conf's scheme choice.
        case "$(read_prop "$DEPLOY_CONF" wls.adminurl)" in t3s://*) scheme="t3s" ;; esac
    fi
    [ -n "$addr" ] || addr="$(read_prop "$DEPLOY_CONF" wls.adminurl | sed -E 's#^[a-z0-9]+://([^:/]+).*#\1#')"
    # NEVER localhost — the AdminServer is reached over the network, not the
    # loopback. If config/conf gave nothing or a loopback, use the host's own
    # routable name/IP.
    case "$addr" in ""|localhost|127.*|::1) addr="$(hostname -f 2>/dev/null || hostname)" ;; esac
    [ -n "$addr" ] || addr="$(hostname)"
    if [ -z "$port" ]; then
        [ "$scheme" = "t3s" ] && port="7002" || port="7001"
    fi
    printf '%s://%s:%s' "$scheme" "$addr" "$port"
}

# The engine on THIS (admin) box -- the STATIC engine0, always index 0. The
# dynamic range starts at 1, but the deploy/test target is the local static engine.
_test_target() {
    local pfx
    pfx="$(read_prop "$OCCAS_CONF" server.name.prefix)"; pfx="${pfx:-engine}"
    printf '%s0' "$pfx"
}

# ============================================================================
# Unattended subcommands, status, backup, firewall — headless siblings of the
# dashboard. They reuse the same dispatch_row workers, so behaviour never drifts
# from the interactive menu.
# ============================================================================

# Tee this (non-interactive) run to .conf/<name>/blade.log so a developer's
# "it didn't work on my laptop" is diagnosable afterwards. The interactive TUI is
# deliberately NOT teed — it would fill the log with terminal escape codes.
start_logging() {
    local what="$1"
    [ -n "${BLADE_LOG:-}" ] || return 0
    mkdir -p "$BLADE_HOME" 2>/dev/null || true
    local lf="$BLADE_LOG"
    exec > >(tee -a "$lf") 2>&1
    LOGGING=1
    log ""
    log "===== blade ${BLADE_VERSION} · ${what} · $(date '+%Y-%m-%d %H:%M:%S') · profile '${NAME}' ====="
}

# Unattended runs can leave partial state if Ctrl-C'd mid-install; say so and
# point at the recovery path instead of dying silently.
trap_interrupt() {
    trap 'echo; warn "interrupted — state may be partial. Clean up with: ./install.sh '"'"''"${NAME}"''"'"' uninstall"; exit 130' INT
}

# True if a JVM for THIS domain's AdminServer is running (matched by domain home
# in the cmdline, like misc/stop-admin-os.sh — never a blind pgrep).
admin_running() {
    local domhome="${DOMAINS_DIR}/${DOMAIN}" p cmd
    command -v pgrep >/dev/null 2>&1 || return 1
    for p in $(pgrep -f 'weblogic.Name=AdminServer' 2>/dev/null || true); do
        cmd="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)"
        case "$cmd" in *"$domhome"*) return 0 ;; esac
    done
    return 1
}

# Unattended install: the full ladder (install → certs → domain → start) in
# order, no menu. TLS 'g' runs before 'n'/'c' so certs exist first. Each worker is idempotent and
# skips when its target already exists, so this is safe to re-run. Boot services
# The boot services (e/w) and the engine hosts (E) ARE part of the ladder: an
# install that does not survive a reboot is not finished, and that is exactly how
# an engine host once sat dead for eight days. On a laptop with no systemctl the
# service steps warn and move on, and E is a no-op for a single-host profile, so
# the ladder stays useful there too. Order matters — s brings the AdminServer up,
# which the engines need before they can boot.
run_install_ladder() {
    load_profile
    info "Unattended install of profile '${NAME}' → ${MWHOME:-?}"
    yesno "Install OCCAS + Node Manager + cluster domain for '${NAME}' now?" "Y" \
        || { warn "aborted."; return 1; }
    local id cert_step=g
    [ "${CERT_SOURCE:-generate}" = supply ] && cert_step=sup
    for id in u m dl i "$cert_step" n c f s e w o; do
        rule; info "install step '${id}'"
        dispatch_row "$id"
    done
    rule
    ok "install complete for '${NAME}'."
    log "  verify with:  ./install.sh ${NAME} status"
}

# Unattended uninstall. Default tears down just the app + NM domains and KEEPS
# the profile, so 'install' can immediately rebuild — the iterate-fast loop.
# --purge also deinstalls the product, removes the dirs, and the user/group (and
# drops the profile). Never touches the local repo clone (that stays a manual,
# interactive-only 'repo' row).
run_uninstall_ladder() {
    load_profile
    local ids
    if [ "$PURGE" = 1 ]; then ids="r b di md ug"; else KEEP_PROFILE=1; ids="r b"; fi
    info "Unattended uninstall of profile '${NAME}' — will run: ${ids}"
    [ "$PURGE" = 1 ] && log "  ${C_DIM}--purge: also deinstall product, remove dirs, remove user/group, drop profile.${C_RESET}" \
                     || log "  ${C_DIM}keeping the profile so './install.sh ${NAME} install' can rebuild.${C_RESET}"
    yesno "Proceed with uninstall (${ids})?" "N" || { warn "aborted."; return 1; }
    local id
    for id in $ids; do
        rule; info "teardown step '${id}'"
        dispatch_row "$id"
    done
    rule
    ok "uninstall complete for '${NAME}'."
}

# One-shot health snapshot — the first thing to run when a dev says "it's broken".
do_status() {
    load_profile
    local mw="$MWHOME" dom="$DOMAIN" nmdom="$NM_DOMAIN"
    local nmport="${NM_PORT:-5556}"
    local nmfile="${DOMAINS_DIR}/${nmdom}/nodemanager/nodemanager.domains"
    info "BLADE ${BLADE_VERSION} · status of profile '${NAME}'"
    log  "  MW_HOME: ${mw:-—}    OCCAS: ${OCCAS_VERSION:-—}    domain: ${dom:-—}"
    rule
    # _st "label" <predicate cmd...> — runs the predicate safely under set -e.
    _st() { local lbl="$1"; shift; if "$@" >/dev/null 2>&1; then printf '   %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$lbl"; else printf '   %s✗%s %s\n' "$C_RED" "$C_RESET" "$lbl"; fi; }
    _st "install user '${INSTALL_USER:-oracle}' exists"        id "${INSTALL_USER:-oracle}"
    _st "OCCAS product installed at ${mw}"                     occas_installed "$mw"
    _st "Node Manager domain '${nmdom}' present"               test -d "${DOMAINS_DIR}/${nmdom}"
    _st "Node Manager listening on :${nmport}"                 nm_listening "$nmport"
    _st "app domain '${dom}' present"                          test -d "${DOMAINS_DIR}/${dom}"
    _st "app domain '${dom}' enrolled in Node Manager"         grep -q "^${dom}=" "$nmfile"
    _st "AdminServer process running"                          admin_running
    log ""
    log "  ${C_BOLD}Patch level per host${C_RESET}"
    patch_levels || true
    _st "nodemanager.service installed"                        grep -qsF "${DOMAINS_DIR}/${nmdom}" /etc/systemd/system/nodemanager.service
    _st "weblogic.service installed"                           grep -qsF "${DOMAINS_DIR}/${dom}" /etc/systemd/system/weblogic.service
    unset -f _st
    rule
    log "  admin URL: $(_wls_adminurl 2>/dev/null || true)"
    log "  session log: ${BLADE_LOG:-—}"
}

# Snapshot the profile (configs + secrets) and the domain's config tree to a tgz
# BEFORE a teardown, so a fat-fingered uninstall is recoverable. Kept OUTSIDE the
# profile dir (under .conf/.backups/) so removing the profile doesn't take the
# backups with it. Best-effort: never blocks the operation it precedes.
do_backup() {
    { [ -n "${BLADE_CONF:-}" ] && [ -f "$BLADE_CONF" ]; } || { warn "no profile config — nothing to back up."; return 1; }
    local bdir="${BLADE_HOME}/.backups"
    local dest="${bdir}/${NAME}-$(date '+%Y%m%d-%H%M%S').tgz"
    local domhome="${DOMAINS_DIR}/${DOMAIN:-}"
    local -a items=("$BLADE_CONF")
    [ -d "${domhome}/config" ] && items+=("${domhome}/config")
    if [ "${#items[@]}" -eq 0 ]; then warn "nothing to back up yet."; return 0; fi
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] tar czf ${dest}  (profile conf/secrets + ${domhome}/config)${C_RESET}"; return 0; fi
    mkdir -p "$bdir" || { warn "could not create ${bdir}."; return 1; }
    # The domain config holds 0600 install-user files the invoker can't read;
    # a mixed archive (their config + our profile) needs root, then handed back.
    if tar czf "$dest" "${items[@]}" 2>/dev/null; then
        ok "backup → ${dest#${SCRIPT_DIR}/}"
    elif command -v sudo >/dev/null 2>&1 \
         && sudo tar czf "$dest" "${items[@]}" 2>/dev/null && sudo chown "$(id -un)" "$dest"; then
        ok "backup → ${dest#${SCRIPT_DIR}/} (via sudo — the domain config is the install user's)"
    else
        rm -f "$dest"; warn "backup failed (continuing)."; return 1
    fi
}

# Open the ports OCCAS needs on firewalld. Server installs need this; laptops
# usually have no firewalld and it no-ops. Idempotent.
# ============================================================================
# nginx edge reverse proxy
# ----------------------------------------------------------------------------
# Render + own /etc/nginx/nginx.conf for the front-door box. Faithful to the
# hand-built config, parameterised from the profile, with the two things that
# are easy to get wrong baked in permanently:
#   * WebSocket: Upgrade/Connection are hop-by-hop, so nginx drops them unless
#     re-set per request. Without them the Configurator/WebRTC handshake reaches
#     WebLogic as a plain GET → 302 to login → the browser reports a bad
#     handshake. The map + the two proxy_set_header lines fix it.
#   * Backend is the box's ROUTABLE address, never 127.0.0.1: the AdminServer
#     SSL listener binds its ListenAddress, and localhost never reaches it.
# naxsi is optional (on|off|auto): the includes appear only when the compiled
# module's rule files are present, so this also renders on a vanilla nginx.
# ============================================================================
render_nginx_conf() {
    local admin_sn="$1" apps_sn="$2" backend="$3" admin_port="$4" apps_port="$5" \
          fullchain="$6" privkey="$7" maxbody="$8" naxsi="$9"
    if [ "$naxsi" = auto ]; then [ -f /etc/nginx/naxsi_core.rules ] && naxsi=on || naxsi=off; fi
    local core_inc="" learn_inc="" block_inc=""
    if [ "$naxsi" = on ]; then
        core_inc="    include /etc/nginx/naxsi_core.rules;"
        learn_inc="            include /etc/nginx/naxsi-learn.rules;"
        block_inc="            include /etc/nginx/naxsi-block.rules;"
    fi

    # The proxy body shared by both vhosts. $1=scheme(https|http) $2=port
    # $3=optional WAF include line. Reads $backend from the enclosing scope.
    _emit_location() {
        local scheme="$1" port="$2" waf="$3"
        [ -n "$waf" ] && printf '%s\n' "$waf"
        if [ "$scheme" = https ]; then
            printf '            proxy_pass https://%s:%s; proxy_ssl_verify off; proxy_ssl_server_name on;\n' "$backend" "$port"
        else
            printf '            proxy_pass http://%s:%s;\n' "$backend" "$port"
        fi
        cat <<EOF
            proxy_set_header Host \$host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto https;
            proxy_redirect https://\$host:${port}/ https://\$host/; proxy_redirect http://\$host:80/ https://\$host/; proxy_redirect http://\$host/ https://\$host/;
            proxy_http_version 1.1;
            proxy_set_header Upgrade \$http_upgrade;
            proxy_set_header Connection \$connection_upgrade;
EOF
    }

    cat <<EOF
# Generated by install.sh — the BLADE edge reverse proxy. Do not hand-edit;
# re-run the nginx row to regenerate. Backend ${backend} is this box's WebLogic.
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log;
pid /run/nginx.pid;
include /usr/share/nginx/modules/*.conf;
events { worker_connections 1024; }
http {
    log_format main '\$remote_addr - \$remote_user [\$time_local] "\$request" \$status \$body_bytes_sent "\$http_referer" "\$http_user_agent"';
    access_log /var/log/nginx/access.log main;
    sendfile on;
    tcp_nopush on;
    keepalive_timeout 65;
    types_hash_max_size 4096;
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
EOF
    [ -n "$core_inc" ] && printf '%s\n' "$core_inc"
    cat <<EOF

    # WebSocket upgrade: Upgrade/Connection are hop-by-hop; re-set per request or
    # nginx drops them and the handshake never reaches 101 Switching Protocols.
    map \$http_upgrade \$connection_upgrade {
        default upgrade;
        ''      close;
    }

    ssl_certificate     ${fullchain};
    ssl_certificate_key ${privkey};
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:10m;
    server_tokens off;

    server {
        listen 80 default_server;
        return 301 https://\$host\$request_uri;
    }
    server {
        listen 443 ssl http2 default_server;
        server_name _;
        return 444;
    }
EOF
    if [ -n "$admin_sn" ]; then
        cat <<EOF
    server {
        listen 443 ssl http2;
        server_name ${admin_sn};
        client_max_body_size ${maxbody};
        location / {
EOF
        _emit_location https "$admin_port" "$learn_inc"
        cat <<'EOF'
        }
        location /RequestDenied { internal; return 403; }
    }
EOF
    fi
    if [ -n "$apps_sn" ]; then
        cat <<EOF
    server {
        listen 443 ssl http2;
        server_name ${apps_sn};
        location / {
EOF
        _emit_location http "$apps_port" "$block_inc"
        cat <<'EOF'
        }
        location /RequestDenied { internal; return 403; }
    }
EOF
    fi
    printf '%s\n' "}"
    unset -f _emit_location
}

# Render the config from the profile, validate it OFF to the side, and only then
# swap it in and reload. A validate-first swap keeps the on-disk file always-good
# (nginx -t loads the SSL certs, so missing certs fail here, not at reload).
do_install_nginx() {
    command -v nginx >/dev/null 2>&1 || { warn "nginx is not installed on this box — install it (with the naxsi module if you use the WAF) first."; return 1; }
    local admin_sn apps_sn backend admin_port apps_port fullchain privkey maxbody naxsi
    admin_sn="$(read_prop "$DEPLOY_CONF" nginx.server_name.admin)"
    apps_sn="$(read_prop "$DEPLOY_CONF" nginx.server_name.apps)"
    backend="$(read_prop "$DEPLOY_CONF" nginx.backend.addr)"
    [ -n "$backend" ] || backend="$(hostname -I 2>/dev/null | awk '{print $1}')"
    admin_port="$(read_prop "$DEPLOY_CONF" nginx.admin.port)"; admin_port="${admin_port:-${SSL_PORT:-7002}}"
    apps_port="$(read_prop "$DEPLOY_CONF" nginx.apps.port)"; apps_port="${apps_port:-8001}"
    fullchain="$(read_prop "$DEPLOY_CONF" nginx.tls.fullchain)"
    privkey="$(read_prop "$DEPLOY_CONF" nginx.tls.privkey)"
    maxbody="$(read_prop "$DEPLOY_CONF" nginx.client.max.body.size)"; maxbody="${maxbody:-500m}"
    naxsi="$(read_prop "$DEPLOY_CONF" nginx.naxsi)"; naxsi="${naxsi:-auto}"

    { [ -n "$admin_sn" ] || [ -n "$apps_sn" ]; } || { warn "no nginx server-names set — fill in the nginx phase first."; return 1; }
    [ -n "$backend" ] || { warn "could not determine the backend address (hostname -I empty) — set nginx.backend.addr."; return 1; }
    { [ -n "$fullchain" ] && [ -n "$privkey" ]; } || { warn "TLS cert paths are required (nginx.tls.fullchain / nginx.tls.privkey)."; return 1; }

    local text; text="$(render_nginx_conf "$admin_sn" "$apps_sn" "$backend" "$admin_port" "$apps_port" "$fullchain" "$privkey" "$maxbody" "$naxsi")"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] write /etc/nginx/nginx.conf:${C_RESET}"
        printf '%s\n' "$text" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] nginx -t && systemctl reload nginx${C_RESET}"
        return 0
    fi
    local sudo=""; [ "$(id -u)" != 0 ] && command -v sudo >/dev/null 2>&1 && sudo="sudo"
    local tmp; tmp="$(mktemp)" || { warn "mktemp failed."; return 1; }
    printf '%s\n' "$text" > "$tmp"
    if ! $sudo nginx -t -c "$tmp" >/dev/null 2>&1; then
        warn "rendered nginx config failed validation — NOT installed:"
        $sudo nginx -t -c "$tmp" 2>&1 | sed 's/^/    /'
        rm -f "$tmp"; return 1
    fi
    local stamp; stamp="$(date +%Y%m%d)"
    [ -f /etc/nginx/nginx.conf ] && $sudo cp -a /etc/nginx/nginx.conf "/etc/nginx/nginx.conf.bak.${stamp}" 2>/dev/null || true
    if ! $sudo install -m 0644 -o root -g root "$tmp" /etc/nginx/nginx.conf; then
        warn "could not write /etc/nginx/nginx.conf (need sudo?)."; rm -f "$tmp"; return 1
    fi
    rm -f "$tmp"
    if ! $sudo nginx -t >/dev/null 2>&1; then
        warn "installed config failed in-place validation — restoring backup."
        [ -f "/etc/nginx/nginx.conf.bak.${stamp}" ] && $sudo cp -a "/etc/nginx/nginx.conf.bak.${stamp}" /etc/nginx/nginx.conf 2>/dev/null
        return 1
    fi
    if $sudo systemctl reload nginx 2>/dev/null; then
        ok "nginx reloaded — edge config live (${admin_sn:-—}${apps_sn:+, ${apps_sn}})."
    else
        warn "config installed but 'systemctl reload nginx' failed — start it: sudo systemctl enable --now nginx"
    fi
    [ "$naxsi" = off ] && log "${C_DIM}  naxsi includes omitted (no compiled module / rule files) — WAF is off.${C_RESET}"
    return 0
}

do_open_firewall() {
    command -v firewall-cmd >/dev/null 2>&1 || { ok "no firewalld here — nothing to open."; return 0; }
    firewall-cmd --state >/dev/null 2>&1 || { ok "firewalld not running — nothing to open."; return 0; }
    local nmport adminport sslport sip siptls
    nmport="$(read_prop "$OCCAS_CONF" nm.listen.port)"; nmport="${nmport:-5556}"
    adminport="$(read_prop "$DEPLOY_CONF" wls.adminurl | sed -E 's#.*:([0-9]+).*#\1#')"; adminport="${adminport:-7001}"
    sslport="$(read_prop "$DEPLOY_CONF" tls.ssl.port)"; sslport="${sslport:-7002}"
    siptls="$(read_prop "$DEPLOY_CONF" sip.tls.enabled)"
    sip="$(read_prop "$DEPLOY_CONF" sip.tls.port)"
    local ports="${nmport} ${adminport} ${sslport}"
    { [ "$siptls" = "true" ] && [ -n "$sip" ]; } && ports="${ports} ${sip}"
    local sudo=""; [ "$(id -u)" != 0 ] && command -v sudo >/dev/null 2>&1 && sudo="sudo"
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] ${sudo:+sudo }firewall-cmd --permanent --add-port=${ports// /,}/tcp; --reload${C_RESET}"; return 0; fi
    local p ok_any=0
    for p in $ports; do
        $sudo firewall-cmd --permanent --add-port="${p}/tcp" >/dev/null 2>&1 && { ok "opened ${p}/tcp"; ok_any=1; } || warn "could not open ${p}/tcp"
    done
    [ "$ok_any" = 1 ] && { $sudo firewall-cmd --reload >/dev/null 2>&1 && ok "firewalld reloaded." || warn "firewall reload failed."; }
}

# ============================================================================
# Entry — only when executed directly, so the script can be sourced (e.g. for
# tests) to load its functions without running the interactive flow.
# ============================================================================
if [ "${BASH_SOURCE[0]}" != "${0}" ]; then return 0 2>/dev/null || true; fi

if [ -z "$NAME" ]; then
    # No name given: list every profile (new + legacy layout), pick or create.
    profiles=()
    while IFS= read -r _p; do [ -n "$_p" ] && profiles+=("$_p"); done < <(list_profile_names)
    if [ "${#profiles[@]}" -eq 0 ]; then
        info "No profiles yet — name one and fill in the phases."
        NAME=""
        dashboard
    else
        printf '\e[2J\e[H'; banner
        printf '  %sselect a profile%s\n\n' "$C_DIM" "$C_RESET"
        n=1; for p in "${profiles[@]}"; do log "  [$n] $p"; n=$((n+1)); done
        log "  [c] create a new profile"
        read -r -p "  choose: " pick || pick="c"
        case "$pick" in
            c|"") NAME=""; dashboard ;;
            *[!0-9]*) die "invalid choice" ;;
            *) NAME="${profiles[$((pick-1))]:-}"; [ -n "$NAME" ] || die "no such profile"; dashboard ;;
        esac
    fi
else
    set_paths
    # Subcommands: 'wizard'/'preflight' prep then drop into the dashboard; the
    # rest (install/uninstall/status/backup) are headless and DON'T open the menu.
    _need_profile() { [ -f "$OCCAS_CONF" ] || die "no profile '${NAME}' yet — create it first: ./install.sh ${NAME}"; }
    case "$JUMP" in
        wizard)    run_wizard ;;
        preflight) _need_profile; do_preflight ;;
        install)   _need_profile; start_logging install;   trap_interrupt; run_install_ladder;   exit 0 ;;
        uninstall) _need_profile; start_logging uninstall; trap_interrupt; run_uninstall_ladder; exit 0 ;;
        status)    _need_profile; do_status; exit 0 ;;
        backup)    _need_profile; load_profile; do_backup; exit $? ;;
        ""|menu|dashboard) : ;;
        *) die "unknown command '${JUMP}' — try: wizard, preflight, install, uninstall, status, backup" ;;
    esac
    dashboard
fi
