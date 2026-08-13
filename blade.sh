#!/usr/bin/env bash
# ============================================================================
# blade.sh - Guided installer/configurator for BLADE (OCCAS + BLADE).
#
# One interview builds a PROFILE — a directory under .conf/<name>/ holding the
# two config files the rest of the tooling reads (plus their secrets):
#
#   .conf/<name>/occas.conf     silent install + domain + patching
#   .conf/<name>/deploy.conf    ./deploy.sh, ./tls/* (deploy + TLS)
#   .conf/<name>/occas.secret   admin password
#   .conf/<name>/deploy.secret  wls password + TLS passphrases
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
#   - set up TLS  (RUN: g/t; tls/make-certs.sh, tls/install-ssl.sh)
#   - UNINSTALL, in reverse-of-install order (each row confirms first):
#       remove app domain + profile (RUN: r) · remove Node Manager domain (RUN: b)
#       deinstall the OCCAS product (RUN: di) · remove install dirs (RUN: md)
#       remove install user & group (RUN: ug) · delete the LOCAL repo clone (RUN: repo)
# Build with ./build.sh and deploy with ./deploy.sh <profile> afterwards.
#
# Usage:
#   ./blade.sh                 pick a profile (or create one), then the dashboard
#   ./blade.sh <name>          open profile <name> in the dashboard
#   ./blade.sh <name> wizard      run the full linear interview first
#   ./blade.sh <name> preflight   run host-prerequisite checks first
#   ./blade.sh <name> install     unattended install (STEP 1-4), no menu
#   ./blade.sh <name> uninstall   unattended teardown (app+NM domains)
#                                   add --purge to also remove product/dirs/user
#   ./blade.sh <name> status      one-shot health snapshot of the profile
#   ./blade.sh <name> backup      snapshot profile + domain config to a tgz
#   flags: -y/--yes (assume yes)  -n/--dry-run  --no-backup  --purge
#   ./blade.sh -v | --version     print the BLADE version
#   ./blade.sh -h
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF_BASE="${SCRIPT_DIR}/.conf"

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
# ask VAR "label" "default"   — Enter accepts the default.
ask() {
    local __v="$1" __l="$2" __d="${3:-}" __in
    if [ -n "$__d" ]; then read -r -p "  ${__l} [${__d}]: " __in || __in=""
    else read -r -p "  ${__l}: " __in || __in=""; fi
    [ -z "$__in" ] && __in="$__d"
    printf -v "$__v" '%s' "$__in"
}
# yesno "label" "Y|N"  — returns 0 for yes. Default shown in caps.
yesno() {
    local __l="$1" __d="${2:-Y}" __in __hint
    if [ "${ASSUME_YES:-0}" = 1 ]; then log "  ${__l} ${C_DIM}[--yes]${C_RESET}"; return 0; fi
    [ "$__d" = "Y" ] && __hint="Y/n" || __hint="y/N"
    read -r -p "  ${__l} [${__hint}]: " __in || __in=""
    [ -z "$__in" ] && __in="$__d"
    case "$__in" in [Yy]*) return 0 ;; *) return 1 ;; esac
}
# ask_secret VAR "label"  — hidden, confirmed; empty is allowed (skips).
ask_secret() {
    local __v="$1" __l="$2" __a __b
    read -rs -p "  ${__l}: " __a || __a=""; echo
    if [ -z "$__a" ]; then printf -v "$__v" '%s' ""; return 0; fi
    read -rs -p "  confirm: " __b || __b=""; echo
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
    local file="$1" key="$2"
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
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

# --- args ---------------------------------------------------------------------
# Version tracks pom.xml's <revision>, so a dev's bug report pins to a build.
BLADE_VERSION="$(sed -n 's/.*<revision>\(.*\)<\/revision>.*/\1/p' "${SCRIPT_DIR}/pom.xml" 2>/dev/null | head -1)"
BLADE_VERSION="${BLADE_VERSION:-3.0.1}"
case "${1:-}" in
    -h|--help)            sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -v|--version|version) printf 'BLADE %s\n' "$BLADE_VERSION"; exit 0 ;;
esac
# A leading FLAG is not a profile name. './blade.sh --dry-run' used to create a
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
OCCAS_CONF=""; DEPLOY_CONF=""; OCCAS_SECRET=""; DEPLOY_SECRET=""
# Set by do_remove_domain after it deletes the active profile, so the dashboard
# loops know to drop out instead of redrawing a profile that no longer exists.
PROFILE_GONE=0
# Set by do_remove_repo after it schedules deletion of the local clone (blade.sh
# included): the dashboard loops drop out so we exit before the tree disappears.
REPO_GONE=0
set_paths() {
    PROFILE_DIR="${CONF_BASE}/${NAME}"
    OCCAS_CONF="${PROFILE_DIR}/occas.conf"
    DEPLOY_CONF="${PROFILE_DIR}/deploy.conf"
    OCCAS_SECRET="${PROFILE_DIR}/occas.secret"
    DEPLOY_SECRET="${PROFILE_DIR}/deploy.secret"
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
    # Optional numeric IDs — empty means "let the OS pick". See phase_occas.
    INSTALL_UID="$(d install.uid "")"
    INSTALL_GID="$(d install.gid "")"
    INSTALL_TYPE="$(d install.type 'Complete with Examples')"
    JAVA_HOME_VAL="$(d java.home "${JAVA_HOME:-}")"
    # Like the Oracle home, java.home is a SYMLINK on Linux: JDKs sit side by
    # side under java.dir and java.home points at <java.dir>/current, so a
    # Java upgrade is a flip of that one link (the patch step offers it).
    JAVA_BASE="$(d java.dir /opt/oracle/java)"
    prefix="$(d server.name.prefix engine)"
    match="$(d machine.match.expression "")"
    NM_DOMAIN="$(d nm.domain.name nmdomain)"
    NM_BIND="$(d nm.bind.address 0.0.0.0)"
    NM_PORT="$(d nm.listen.port 5556)"
    NM_TYPE="$(d nm.type ssl)"
    DCOUNT="$(d dynamic.server.count "")"
    DMAX="$(d max.dynamic.cluster.size "")"
    # Server names follow the machines: machine0 runs <prefix>0, machine1 runs
    # <prefix>1. Starting at 0 is what lets the local machine's engine come from
    # the same template as every other one -- there is no static server any more.
    SRV_START_INDEX="$(d server.name.starting.index 0)"
    SHARED_FS="$(d shared.filesystem true)"
    BUILD_PROFILE="$(d build.profile production)"
    # The LOGIN user, not the install user: cloud images only plant the ssh key
    # for their login account (opc, ec2-user). Privilege on the far side comes
    # from that user's sudo, applied per command — never from oracle-owned keys.
    SSH_USER="$(d ssh.user "$(id -un)")"
    # Never default to localhost — the AdminServer is reached over the network.
    # blade.sh runs ON the admin box, so its own hostname is the right default.
    ADMINURL="$(d wls.adminurl "t3://$(hostname -f 2>/dev/null || hostname):7001")"
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
    # Where the p12s live on every node, and the private-key alias inside them.
    # emit_tls_block writes both into the server template, so they must come from
    # the profile -- not a default that silently disagrees with what certs.sh made.
    KEYSTORE_DIR="$(d tls.keystore.dir "$(dirname "${OCCAS_BASE:-/opt/oracle/occas}")/security")"
    ID_ALIAS="$(d tls.identity.alias blade-identity)"
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

# Bring a pre-"add a machine" profile up to date, in memory and on disk.
#
# Profiles written before this change carry two things that are now wrong:
#   * static.server=engine0:machine0:...  -- there is no static server; engine0
#     comes from the template like every other engine
#   * a match expression that EXCLUDES the admin machine (machine1,machine2),
#     because engines used to live only on the other boxes
# Left alone, the first would try to create a server that collides with the
# dynamic engine0, and the second would leave machine0 running no engine at all.
# Migrating is mechanical, so do it rather than refuse to open the profile.
migrate_profile() {
    [ -f "$OCCAS_CONF" ] || return 0
    local old_static old_match want_match changed=0
    old_static="$(read_prop "$OCCAS_CONF" static.server)"
    old_match="$(read_prop "$OCCAS_CONF" machine.match.expression)"

    # Every machine now carries an engine, so the expression is just the list.
    local n; want_match=""
    for n in "${H_NAME[@]}"; do want_match="${want_match:+${want_match},}${n}"; done

    if [ -n "$old_static" ]; then
        set_conf_prop "$OCCAS_CONF" static.server ""
        changed=1
    fi
    if [ -n "$want_match" ] && [ "$old_match" != "$want_match" ]; then
        set_conf_prop "$OCCAS_CONF" machine.match.expression "$want_match"
        match="$want_match"
        changed=1
    fi
    if [ -z "$(read_prop "$OCCAS_CONF" server.name.starting.index)" ]; then
        set_conf_prop "$OCCAS_CONF" server.name.starting.index 0
        SRV_START_INDEX=0
        changed=1
    fi
    # The count is derived from the machines now; nothing else may set it.
    if [ "$(read_prop "$OCCAS_CONF" dynamic.server.count)" != "${#H_NAME[@]}" ]; then
        set_conf_prop "$OCCAS_CONF" dynamic.server.count "${#H_NAME[@]}"
        DCOUNT="${#H_NAME[@]}"
        changed=1
    fi
    [ "$changed" = 1 ] && warn "profile migrated: engines now follow the machines (machine0 → ${prefix:-engine}0); static.server dropped."
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
    if [ "${#H_NAME[@]}" -gt 1 ] || [ -n "$INSTALL_UID" ] || [ -n "$INSTALL_GID" ]; then
        help <<'EOF'
On a multi-host cluster you can pin the numeric IDs so every box agrees.

Engine provisioning rsyncs as an ordinary ssh user, which cannot chown, so the
copied files simply end up owned by that user on the far side — names match and
the numbers do not have to. Pinning matters in two cases:

  - MW_HOME on shared storage (NFS): the server checks NUMBERS, not names, so
    'oracle' as 1001 here and 1002 there is a genuine permission failure.
  - anything that copies as root, which does preserve numeric uid/gid.

Enter to leave blank and let the OS choose. That is the right answer for a
single host, and for the ordinary non-shared multi-host case too.
EOF
        ask INSTALL_UID "Numeric uid for ${INSTALL_USER} (blank = OS picks)" "$INSTALL_UID"
        ask INSTALL_GID "Numeric gid for ${INV_GRP} (blank = OS picks)"      "$INSTALL_GID"
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

    # JDK for the INSTALLER + servers. The certification matrix names a major
    # per OCCAS release, but it's a recommendation, not a gate — newer majors
    # are known to run (Oracle says 8.3 runs fine on 25). List what's
    # installed, flag the certified one, and let the user decide. Only a JDK
    # BELOW the certified major is worth a fight — that rarely runs at all.
    # This is NOT the build JDK: ./build.sh wants 23+ (it emits Java 11 bytecode).
    log ""
    local _want; _want="$(occas_jdk_major "$OCCAS_VERSION")"
    local _jdks=() _line _cert=""
    while IFS= read -r _line; do
        _jdks+=("$_line")
        [ -n "$_want" ] && [ -z "$_cert" ] && [ "${_line##*$'\t'}" = "$_want" ] \
            && _cert="${_line%%$'\t'*}"
    done < <(list_jdks)
    if [ "${#_jdks[@]}" -gt 0 ]; then
        log "  installed JDKs:"
        local _i=1 _tag
        for _line in "${_jdks[@]}"; do
            _tag=""
            [ -n "$_want" ] && [ "${_line##*$'\t'}" = "$_want" ] \
                && _tag="   <- certified for OCCAS ${OCCAS_VERSION}"
            log "    [${_i}] JDK ${_line##*$'\t'}   ${_line%%$'\t'*}${_tag}"
            _i=$((_i + 1))
        done
        [ -n "$_want" ] && [ -z "$_cert" ] \
            && log "  ${C_DIM}none is the certified JDK ${_want} — fine if you know yours runs.${C_RESET}"
    elif [ -n "$_want" ] && jdk_dl_supported "$_want" \
         && yesno "no JDKs found; OCCAS ${OCCAS_VERSION} is certified on JDK ${_want} — download it from Oracle into ${JAVA_BASE}?" "Y"; then
        download_jdk "$_want" "$JAVA_BASE" \
            && _jdks=("${JDK_DL_HOME}"$'\t'"${_want}") && _cert="$JDK_DL_HOME"
    else
        warn "no JDKs found here — install one or point me at it."
    fi
    log "  ${C_DIM}(the build JDK is separate — ./build.sh wants 23+.)${C_RESET}"
    # Default JDK home: keep a saved java.home only if it still resolves. A
    # persisted <java.dir>/current link goes dead when the JDK isn't installed
    # yet, or /opt/oracle was rebuilt — never offer a path with no bin/java.
    # Fall back to the certified JDK, else the first one listed, else $JAVA_HOME.
    local _jdef="$JAVA_HOME_VAL"
    if [ -n "$_jdef" ] && [ ! -x "${_jdef}/bin/java" ]; then _jdef=""; fi
    if [ -z "$_jdef" ]; then
        if [ -n "$_cert" ]; then _jdef="$_cert"
        elif [ "${#_jdks[@]}" -gt 0 ]; then _jdef="${_jdks[0]%%$'\t'*}"
        else _jdef="${JAVA_HOME:-}"; fi
    fi
    while :; do
        ask JAVA_HOME_VAL "JDK home for OCCAS (a number above, or a path)" "$_jdef"
        [ -n "$JAVA_HOME_VAL" ] || { warn "a JDK home is required."; continue; }
        case "$JAVA_HOME_VAL" in
            *[!0-9]*) : ;;   # a path — take it as typed
            *) if [ "$JAVA_HOME_VAL" -ge 1 ] && [ "$JAVA_HOME_VAL" -le "${#_jdks[@]}" ]; then
                   JAVA_HOME_VAL="${_jdks[$((JAVA_HOME_VAL - 1))]%%$'\t'*}"
               else
                   warn "no [$JAVA_HOME_VAL] in the list."; continue
               fi ;;
        esac
        if [ ! -x "${JAVA_HOME_VAL}/bin/java" ]; then
            warn "no bin/java under ${JAVA_HOME_VAL} — that's not a JDK home."
            yesno "use it anyway?" "N" && break || continue
        fi
        local _got; _got="$(jdk_major "${JAVA_HOME_VAL}/bin/java")"
        if [ -n "$_want" ] && [ -n "$_got" ] && [ "$_got" != "$_want" ]; then
            if [ "$_got" -lt "$_want" ] 2>/dev/null; then
                warn "that's JDK ${_got}, BELOW OCCAS ${OCCAS_VERSION}'s certified JDK ${_want} — unlikely to run."
                yesno "use JDK ${_got} anyway?" "N" && break || continue
            fi
            log "  ${C_DIM}JDK ${_got} is newer than the certified JDK ${_want} — your call, proceeding.${C_RESET}"
        fi
        break
    done

    # Mirror ORACLE_HOME: store the LINK, not the versioned path, so a JDK
    # upgrade is a flip of <java.dir>/current. macOS dev profiles keep the raw
    # path — host prep is for the Linux install target.
    if [ "$(uname -s)" = "Linux" ] && [ "$JAVA_HOME_VAL" != "${JAVA_BASE}/current" ]; then
        local _real; _real="$(readlink -f "$JAVA_HOME_VAL" 2>/dev/null || printf '%s' "$JAVA_HOME_VAL")"
        if yesno "point ${JAVA_BASE}/current -> ${_real} and use the link as java.home?" "Y"; then
            if [ "$DRY" = "on" ]; then
                log "${C_DIM}  [dry-run] ln -sfn ${_real} ${JAVA_BASE}/current${C_RESET}"
                JAVA_HOME_VAL="${JAVA_BASE}/current"
            elif { mkdir -p "$JAVA_BASE" && ln -sfn "$_real" "${JAVA_BASE}/current"; } 2>/dev/null \
              || { sudo mkdir -p "$JAVA_BASE" && sudo ln -sfn "$_real" "${JAVA_BASE}/current"; } 2>/dev/null; then
                JAVA_HOME_VAL="${JAVA_BASE}/current"
                ok "java.home is the link: ${JAVA_BASE}/current -> ${_real}"
            else
                warn "could not create ${JAVA_BASE}/current — keeping ${JAVA_HOME_VAL}."
            fi
        fi
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
    ask NM_TYPE "  Node Manager type (ssl|plain)" "$NM_TYPE"
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
phase_cluster() {
    log ""; log "${C_BOLD}Dynamic cluster${C_RESET}"
    help <<'EOF'
Every engine in BEA_ENGINE_TIER_CLUST is generated from one server template, so
they all carry the same certificate, the same SIP channels and the same ports.
Server names start at 0 and follow the machines: machine0 runs engine0,
machine1 runs engine1, and so on.

The server COUNT is not asked for -- it is however many machines you have, and
"Add a machine" maintains it. Only the ceiling is a choice.
EOF
    local defmax="${DMAX:-8}"; case "$defmax" in ''|*[!0-9]*) defmax=8 ;; esac
    ask DMAX "Max dynamic cluster size (ceiling)" "$defmax"
    # Count always tracks the machine list; nothing else may set it.
    DCOUNT="${#H_NAME[@]}"
    [ "$DCOUNT" -ge 1 ] || DCOUNT=1
    [ "$DCOUNT" -gt "$DMAX" ] && DMAX=$((DCOUNT * 2))
    return 0
}

# ----- phase 6: runtime / deploy ---------------------------------------------
phase_runtime() {
    log ""; log "${C_BOLD}Runtime / deploy settings${C_RESET}"
    if yesno "Shared filesystem across nodes (install/domain artifacts copy once)?" "$([ "$SHARED_FS" = false ] && echo N || echo Y)"; then SHARED_FS=true; else SHARED_FS=false; fi
    ask BUILD_PROFILE "Build profile to deploy (production|minimal|full)" "$BUILD_PROFILE"
    ask SSH_USER      "SSH user for pushing to engine nodes"              "$SSH_USER"
    ask ADMINURL      "WebLogic admin URL (deploy runs ON the AdminServer)" "$ADMINURL"
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
    [ -n "$ID_CN" ] || ID_CN="${H_FQDN[0]:-${H_NAME[0]:-}}"
    if yesno "Set up TLS settings now?" "Y"; then
        # --- where the certificate comes from --------------------------------
        # A production site almost always has its own. Generating one is for test
        # rigs. Either way the SAME keystore layout comes out, so everything
        # downstream (the server template, the engines) is identical.
        help <<'EOF'
Two ways to get a server certificate:

  supply    you already have one (the normal production answer) — a PKCS12,
            or a PEM cert + key, optionally with a CA chain
  generate  create a self-signed internal CA and a server identity from it.
            Fine for a lab; browsers and SBCs will not trust it by default.

Whichever you pick, WebLogic's built-in DEMO certificate is never used — it is
publicly known and is a real security risk on an internet-facing SIPS port.
EOF
        ask CERT_SOURCE "  Certificate: supply or generate?" "${CERT_SOURCE:-generate}"
        case "$CERT_SOURCE" in
            [Ss]*) CERT_SOURCE=supply ;;
            *)     CERT_SOURCE=generate ;;
        esac
        if [ "$CERT_SOURCE" = supply ]; then
            log "  ${C_DIM}Give a PKCS12, or a PEM cert+key. Enter to skip a field.${C_RESET}"
            ask CERT_P12   "    PKCS12 file (.p12/.pfx)"      "$CERT_P12"
            if [ -z "$CERT_P12" ]; then
                ask CERT_PEM   "    server certificate (PEM)" "$CERT_PEM"
                ask CERT_KEY   "    private key (PEM)"        "$CERT_KEY"
            fi
            ask CERT_CHAIN "    CA chain (PEM, optional)"     "$CERT_CHAIN"
        else
            ask CA_CN "  Internal CA common name"   "$CA_CN"
        fi
        ask ID_CN "  Identity cert common name" "$ID_CN"

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
        # Generate passphrases once; keep existing ones so re-running TLS is safe.
        if [ -z "$(read_prop "$DEPLOY_SECRET" tls.ca.passphrase)" ]; then
            if write_secret "$DEPLOY_SECRET" tls.ca.passphrase "$(gen_pass)"; then
                write_secret "$DEPLOY_SECRET" tls.keystore.passphrase "$(gen_pass)"
                write_secret "$DEPLOY_SECRET" tls.trust.passphrase "$(gen_pass)"
                ok "generated 3 random TLS keystore passphrases (saved to deploy.secret)"
            fi
        else
            log "  ${C_DIM}TLS passphrases already present in deploy.secret — kept.${C_RESET}"
        fi
    fi
    return 0
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
    write_secret "$OCCAS_SECRET"  admin.password "$pw" && ok "saved admin.password to occas.secret (600)"
    write_secret "$DEPLOY_SECRET" wls.password   "$pw" && ok "saved wls.password to deploy.secret (600)"
    return 0
}

# Write/update one key=value in a gitignored secret file (creates it 600).
write_secret() {
    local file="$1" key="$2" val="$3"
    if ! git -C "$SCRIPT_DIR" check-ignore -q "$file" 2>/dev/null; then
        warn "${file#${SCRIPT_DIR}/} is not gitignored — refusing to write a secret. Fix .gitignore."
        return 1
    fi
    if [ ! -f "$file" ]; then
        ( umask 077; printf '# BLADE secret — profile %s (gitignored). chmod 600.\n' "$NAME" > "$file" )
    fi
    set_conf_prop "$file" "$key" "$val"
    chmod 600 "$file"
    return 0
}

# Rewrite occas.conf + deploy.conf from the current globals (keeps comments).
save_profile() {
    [ -n "$NAME" ] || { warn "no profile name — cannot save."; return 1; }
    ensure_gitignore
    mkdir -p "$PROFILE_DIR"
    local stamp; stamp="$(date '+%Y-%m-%d %H:%M')"
    local OCCAS_BASE OCCAS_CURRENT KEYSTORE_DIR APPROUTER_DIR ENGINE_NODES SAN idx
    OCCAS_BASE="$(dirname "$MWHOME")"
    OCCAS_CURRENT="${OCCAS_BASE}/current"
    # Outside the Oracle home, for the same reason domains are: keystores inside
    # it get copied into every patched home, so flipping 'current' would swap
    # which certificates are live -- silently reverting a cert rotation.
    KEYSTORE_DIR="$(dirname "${OCCAS_BASE:-/opt/oracle/occas}")/security"
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
        echo "# Generated by blade.sh on ${stamp}. Re-run: ./blade.sh ${NAME}"
        echo "# Consumed by ./blade.sh. Admin password lives in occas.secret."
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
        echo "# --- Dynamic cluster shape (BEA_ENGINE_TIER_CLUST) ---"
        echo "server.name.prefix=${prefix}"
        echo "machine.match.expression=${match}"
        echo "dynamic.server.count=${DCOUNT}"
        echo "max.dynamic.cluster.size=${DMAX}"
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
        echo "# BLADE — deploy + TLS profile '${NAME}'. Generated by blade.sh on ${stamp}."
        echo "# Consumed by ./deploy.sh and ./tls/*. Secrets live in deploy.secret."
        echo ""
        echo "build.profile=${BUILD_PROFILE}"
        echo "shared.filesystem=${SHARED_FS}"
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
    } > "$DEPLOY_CONF"
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
    phase_occas; phase_domain; phase_hosts; phase_cluster
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
_pw_set() { [ -f "$OCCAS_SECRET" ] && [ -n "$(read_prop "$OCCAS_SECRET" admin.password)" ]; }
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
    local p_clu=0;   { [ -n "$DCOUNT" ] && [ -n "$DMAX" ]; } && p_clu=1
    local p_tls=0;   [ -n "$SSL_PORT" ] && p_tls=1
    local p_run=0;   { [ -n "$BUILD_PROFILE" ] && [ -n "$ADMINURL" ]; } && p_run=1
    local a_i=0; [ -d "${MWHOME}/wlserver" ] && a_i=1
    local a_n=0; [ -d "${DOMAINS_DIR}/${NM_DOMAIN}" ] && a_n=1
    local a_c=0; [ -d "${DOMAINS_DIR}/${DOMAIN}" ] && a_c=1
    # Boot-service rows are "done" only when the unit is installed AND points at
    # our own domain (the same key the guarded teardown uses).
    local a_e=0; grep -qsF "${DOMAINS_DIR}/${NM_DOMAIN}" /etc/systemd/system/nodemanager.service && a_e=1
    local a_w=0; grep -qsF "${DOMAINS_DIR}/${DOMAIN}"    /etc/systemd/system/weblogic.service    && a_w=1
    local nm_state="stopped"; nm_listening "$NM_PORT" && nm_state="running"
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
    _row phase  cluster "Dynamic cluster ceiling"  "$(printf '%s engine(s) · max %s' "${#H_NAME[@]}" "${DMAX:-—}")" "$p_clu"
    _row head ""      "STEP 4 · Start it up (in order)"          "" "-"
    _row action n "Create & start Node Manager" "${NM_DOMAIN} — ${nm_state}" "$a_n"
    _row action c "Create the cluster domain"   "${DOMAIN:-?}" "$a_c"
    _row action s "Start the AdminServer"       "" "-"
    _row action x "Stop the AdminServer"        "" "-"
    _row action k "Stop Node Manager"           "" "-"
    _row action e "Install Node Manager boot service (systemd)"  "nodemanager.service" "$a_e"
    _row action w "Install AdminServer boot service (via NM)"    "weblogic.service"    "$a_w"
    _row action addm "Add a machine (grows the cluster online)" "$(printf 'next: machine%s → %s%s' "${#H_NAME[@]}" "${prefix:-engine}" "${#H_NAME[@]}")" "-"
    _row action remm "Remove the last machine"                   "$(_sum_lastmachine)" "-"
    _row action E "Re-provision every engine host"               "$(_sum_engines)" "-"
    _row action o "Deploy WebLogic Remote Console (/rconsole)" "" "-"
    _row action f "Open firewall ports (firewalld)"              "NM/admin/ssl$([ "${SIP_TLS:-false}" = true ] && printf /sip)" "-"
    _row head ""      "STEP 5 · TLS (optional)"                  "" "-"
    _row phase  tls "TLS settings"          "$(_sum_tls)" "$p_tls"
    _row action g "Certificate (${CERT_SOURCE:-generate})" "$([ "${CERT_SOURCE:-generate}" = supply ] && echo "${CERT_P12:-${CERT_PEM:-not set}}" || echo "self-signed CA")" "-"
    _row action t "Turn on HTTPS / SIP-TLS" "" "-"
    _row head ""      "STEP 6 · Deploy settings (build profile, SSH, admin URL)" "" "-"
    _row phase runtime "Build profile, SSH user, admin URL" "${BUILD_PROFILE} · ${ADMINURL}" "$p_run"
    local distlbl; distlbl="$(ls -1t "${SCRIPT_DIR}/dist" 2>/dev/null | head -1)"; distlbl="${distlbl:-no build — run ./build.sh}"
    _row head ""      "STEP 7 · Deploy to WebLogic (./build.sh first)" "" "-"
    _row action y "Deploy everything (shared library -> the 3 EARs)" "$distlbl" "-"
    _row action l "List current deployments" "" "-"
    _row action z "Undeploy everything" "" "-"
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
    unset -f _row
}

# Run one row by id (phase → edit + save; action → its worker). Shared dispatch.
dispatch_row() {
    local dr=""
    case "$1" in
        occas)   phase_occas;   save_profile ;;
        ident)   phase_domain;  phase_password; save_profile ;;
        hosts)   phase_hosts;   save_profile ;;
        cluster) phase_cluster; save_profile ;;
        tls)     phase_tls;     save_profile ;;
        runtime) phase_runtime; save_profile ;;
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
        x) stop_admin  "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        k) stop_nm || true ;;
        y) do_deploy_all || true ;;
        l) do_deploy_status || true ;;
        z) do_undeploy_all || true ;;
        r) do_remove_domain "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        b) do_remove_nmdomain || true ;;
        di)   do_deinstall     || true ;;
        md)   do_remove_dirs   || true ;;
        ug)   do_remove_usergrp || true ;;
        repo) do_remove_repo   || true ;;
        g) if [ "${CERT_SOURCE:-generate}" = supply ]; then
               # The site's own certificate -- certs.sh packages a PKCS12, or a
               # PEM cert+key(+chain), into the same keystore layout generate
               # produces, so everything downstream is identical either way.
               "${SCRIPT_DIR}/certs.sh" "$DEPLOY_CONF" import || warn "certificate import returned an error"
           else
               "${SCRIPT_DIR}/tls/make-certs.sh" "$DEPLOY_CONF" || warn "make-certs returned an error"
           fi ;;
        t) [ "$DRY" = "on" ] && dr="--dry-run"; "${SCRIPT_DIR}/tls/install-ssl.sh" "$DEPLOY_CONF" $dr || warn "install-ssl returned an error" ;;
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
               case "$r" in '[A'|'OA') printf 'up' ;; '[B'|'OB') printf 'down' ;; *) printf 'other' ;; esac ;;
        '')    printf 'enter' ;;
        ' ')   printf 'space' ;;
        d|D)   printf 'dry' ;;
        q|Q)   printf 'quit' ;;
        [a-zA-Z]) printf 'key:%s' "$k" ;;   # a row-id hotkey (m, p, u, i, E, …)
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
    # (next_step), list them as pressable lines instead of a vague "press a letter".
    _return_prompt() {
        if [ "${#NEXT_K[@]}" -gt 0 ]; then
            printf '\n  %sNext:%s\n' "$C_BOLD" "$C_RESET"
            local i
            for i in "${!NEXT_K[@]}"; do
                printf "    %s'%s'%s  %s\n" "$C_BOLD" "${NEXT_K[$i]}" "$C_RESET" "${NEXT_D[$i]}"
            done
            printf '  %spress one of the letters above, or Enter to return…%s ' "$C_DIM" "$C_RESET"
        else
            printf '\n  %spress Enter to return…%s ' "$C_DIM" "$C_RESET"
        fi
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
        # Chain a follow-up step ONLY from the advertised "Next:" letters — a stray
        # or wrong-case keypress must never launch a surprise action. Anything else
        # (Enter included) returns to the dashboard, where every row is visible.
        while :; do
            _return_prompt
            local kp; kp="$(_read_key)"; printf '\n'
            local kc="" j hit=""
            case "$kp" in key:*) kc="${kp#key:}" ;; *) break ;; esac
            for j in "${!NEXT_K[@]}"; do
                [ "${NEXT_K[$j]}" = "$kc" ] && { hit="$kc"; break; }
            done
            [ -n "$hit" ] || break
            load_profile
            printf '\e[2J\e[H'; banner; _tui_subtitle "$hit"; printf '\n'
            next_step_reset
            dispatch_row "$hit"
            { [ "$PROFILE_GONE" = 1 ] || [ "$REPO_GONE" = 1 ]; } && return 1
        done
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
            local arrow="  "; [ "${MR_TYPE[$i]}" = action ] && arrow="→ "
            if [ "$i" = "$cur" ]; then
                printf '\e[7m   %s %s %s%-42s %s \e[0m\n' "$box" "$g" "$arrow" "${MR_LABEL[$i]}" "${MR_VAL[$i]}"
            else
                printf '   %s %s %s%-42s %s%s%s\n' "$box" "$g" "$arrow" "${MR_LABEL[$i]}" "$C_DIM" "${MR_VAL[$i]}" "$C_RESET"
            fi
        done
        printf '\n  %s↑/↓%s move · %sspace%s select · %senter%s run · %sletter%s run that row · %sd%s dry-run · %sq%s quit\n' \
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
            key:*) # a single-letter row-id hotkey (the letters the help text names)
                   local kc="${kpress#key:}" j hit=""
                   for j in "${!MR_TYPE[@]}"; do
                       [ "${MR_TYPE[$j]}" = action ] || continue
                       [ "${MR_ID[$j]}" = "$kc" ] && { hit="$kc"; break; }
                   done
                   [ -n "$hit" ] && { _tui_run "$hit" || break; } ;;
            quit)  break ;;
            *)     : ;;
        esac
    done
    printf '\e[?25h'; trap - EXIT INT
    log ""
    if [ "$REPO_GONE" = 1 ]; then
        log "  ${C_DIM}Local BLADE clone at ${SCRIPT_DIR} is being removed. GitHub remote is untouched.${C_RESET}"
    elif [ "$PROFILE_GONE" = 1 ]; then
        log "  ${C_DIM}Profile '${NAME}' removed. Re-run ./blade.sh to pick or create another.${C_RESET}"
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
                all) local k; for k in occas ident hosts cluster tls runtime; do dispatch_row "$k"; done ;;
                d)   [ "$DRY" = "on" ] && DRY="off" || DRY="on"; log "  dry-run: ${DRY}" ;;
                q)   quit=1 ;;
                *[!0-9]*)  # a row-id token (the letters the help text names: m, p, patch, …)
                    local mj matched=""
                    for mj in "${!MR_TYPE[@]}"; do
                        [ "${MR_TYPE[$mj]}" = head ] && continue
                        [ "${MR_ID[$mj]}" = "$tok" ] && { matched="$tok"; break; }
                    done
                    if [ -n "$matched" ]; then dispatch_row "$matched"; else warn "unknown choice: $tok"; fi ;;
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
        log "  ${C_DIM}Profile '${NAME}' removed. Re-run ./blade.sh to pick or create another.${C_RESET}"
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
# blade.sh's install actions ('e'/'w') write these units pointed at our own
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
                   "stat -c '%U:%G' '${path}' 2>/dev/null" 2>/dev/null)"
    else
        out="$(xfer_owner_of "$path")"
    fi
    case "$out" in
        ?*:?*) printf '%s' "$out" ;;
        *)     printf '%s:%s' "${INSTALL_USER:-oracle}" "${INV_GRP:-oinstall}" ;;
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
    local work="$1" py="$2" mw="$3" jh="$4"
    local setwls="${mw}/wlserver/server/bin/setWLSEnv.sh"
    # A stale java.home must fall back to the ambient environment, exactly as
    # the pre-runner code did — exporting a dead JAVA_HOME aborts WLST with an
    # opaque rc far from the actual cause.
    if [ -n "$jh" ] && [ ! -d "$jh" ]; then
        warn "java.home ${jh} does not exist — using the environment's JAVA_HOME."
        jh=""
    fi
    cat > "${work}/run.sh" <<EOF
#!/bin/bash
cd '${work}'
${jh:+export JAVA_HOME='${jh}'; PATH='${jh}/bin':"\$PATH"}
export MW_HOME='${mw}' BEA_HOME='${mw}'
. '${setwls}' >/dev/null
exec java weblogic.WLST '${py}'
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
# blade.sh starts NM and the AdminServer interactively (RUN n/s). These install
# the equivalent systemd units so both come back up on reboot. Both unit files
# are GENERATED here from the live domain paths (the misc/*.service files are
# only hand-edit references) — so the conventional names always point at exactly
# the domain blade.sh manages, which is what the guarded teardown above keys on.
#
# startNodeManager.sh / startWebLogic.sh each run their JVM in the foreground, so
# Type=simple + Restart=always is the right shape (matches misc/*.service). Both
# scripts source setDomainEnv.sh -> setUserOverrides.sh, so the server.mem.args
# tuning applies under systemd exactly as under RUN n/s.

# Emit a systemd unit to stdout. after = extra ordering deps (may be empty).
render_systemd_unit() {
    local desc="$1" workdir="$2" start="$3" stop="$4" user="$5" group="$6" after="$7"
    printf '%s\n' "[Unit]"
    printf 'Description=%s\n' "$desc"
    printf 'After=network-online.target%s\n' "${after:+ ${after}}"
    printf 'Wants=network-online.target\n'
    printf '\n[Service]\n'
    printf 'Type=simple\n'
    [ -n "${JAVA_HOME_VAL:-}" ] && printf 'Environment=JAVA_HOME=%s\n' "$JAVA_HOME_VAL"
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
    printf '%s\n' "[Unit]"
    printf 'Description=WebLogic %s via Node Manager (BLADE %s)\n' "$server" "$dom"
    printf 'After=network-online.target nodemanager.service\n'
    printf 'Wants=network-online.target\n'
    printf 'Requires=nodemanager.service\n'
    printf '\n[Service]\n'
    printf 'Type=oneshot\n'
    printf 'RemainAfterExit=yes\n'
    [ -n "${JAVA_HOME_VAL:-}" ] && printf 'Environment=JAVA_HOME=%s\n' "$JAVA_HOME_VAL"
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
# clone at all — the rsync carries the DOMAIN, not the repo — and blade.sh can
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

# Install nodemanager.service for our nmdomain (RUN: e).
do_install_nm_service() {
    local mw="$MWHOME" nmdom="$NM_DOMAIN"
    [ -n "$nmdom" ] || { warn "no nm.domain.name."; return 1; }
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    [ "$DRY" = "on" ] || [ -d "$nmhome" ] || { warn "nmdomain not found: ${nmhome} — create it first ('n')."; return 1; }
    local user grp; IFS=: read -r user grp <<< "$(owner_of_path "$nmhome")"
    local text
    text="$(render_systemd_unit "WebLogic Node Manager (BLADE ${nmdom})" \
        "$nmhome" "${nmhome}/bin/startNodeManager.sh" "${nmhome}/bin/stopNodeManager.sh" \
        "$user" "$grp" "")"
    install_systemd_unit nodemanager.service "$text"
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
    [ "$DRY" = "on" ] || [ -d "$domhome" ] || { warn "app domain not found: ${domhome} — create it first ('c')."; return 1; }
    local user grp; IFS=: read -r user grp <<< "$(owner_of_path "$domhome")"
    # Boot start is nmConnect/nmStart, so the domain must be enrolled in NM. Warn
    # (don't fail) if it isn't yet — 'c' or a first 's' enrolls it persistently.
    local nmfile="${DOMAINS_DIR}/${NM_DOMAIN}/nodemanager/nodemanager.domains"
    if [ "$DRY" != "on" ] && { [ ! -f "$nmfile" ] || ! grep -q "^${dom}=" "$nmfile" 2>/dev/null; }; then
        warn "'${dom}' isn't enrolled in ${NM_DOMAIN} yet — run 'c' (or 's') once so boot start works."
    fi
    # The boot service runs the same scripts blade.sh uses, but from inside the
    # domain so the unit doesn't depend on this checkout still being here.
    stage_boot_scripts "$domhome" || return 1
    local pw="${BLADE_WLS_PASSWORD:-}"
    [ -z "$pw" ] && [ -f "$OCCAS_SECRET" ] && pw="$(read_prop "$OCCAS_SECRET" admin.password)"
    local envfile="${domhome}/.blade-nm.env"
    write_nm_envfile "$envfile" "$user" "$pw" || true
    local text
    text="$(render_admin_nm_unit "$dom" "$domhome" "${domhome}/${BOOT_SCRIPT_SUBDIR}" "$user" "$grp" "$envfile")"
    install_systemd_unit weblogic.service "$text" || return 1

    # machine0 runs the AdminServer AND the first engine, so that engine needs its
    # own unit: weblogic.service starts only the AdminServer, and the engine units
    # live on the engine hosts. Without this it is the one server that stays down
    # after a reboot.
    local sname="${prefix:-engine}${SRV_START_INDEX:-0}"
    write_boot_properties "$domhome" "$sname" "${ADMIN_USER:-weblogic}" "$pw" || true
    local stext
    stext="$(render_admin_nm_unit "$dom" "$domhome" "${domhome}/${BOOT_SCRIPT_SUBDIR}" \
        "$user" "$grp" "$envfile" "$sname" "${ADMINURL:-t3://${H_ADDR[0]}:7001}")"
    install_systemd_unit "weblogic-${sname}.service" "$stext"
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
    cdir="${cdir/#\~/$HOME}"; cdir="${cdir:-${HOME}/.blade/certs/${NAME}}"
    local domhome="${DOMAINS_DIR}/${dom}"
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    local jdk="${JAVA_HOME_VAL:-}"
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
    [ -z "$pw" ] && [ -f "$OCCAS_SECRET" ] && pw="$(read_prop "$OCCAS_SECRET" admin.password)"
    local user grp; IFS=: read -r user grp <<< "$(owner_of_path "$mw")"

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
        log "${C_DIM}  [dry-run] sudo install -d -o ${user} -g ${grp} $(dirname "$mw")${jdk_real:+ $(dirname "$jdk_real")}${jdk_link:+ $(dirname "$jdk_link")}; -o ${sshu} $(dirname "$cdir")${C_RESET}"
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
         "sudo install -d -o '${user}' -g '${grp}' '$(dirname "$real_home")' '${DOMAINS_DIR}'${jdk_real:+ '$(dirname "$jdk_real")'}${jdk_link:+ '$(dirname "$jdk_link")'} \
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
    local newmatch="" h
    for h in "${H_NAME[@]}"; do newmatch="${newmatch:+${newmatch},}${h}"; done
    match="$newmatch"

    # 1. the DOMAIN first. The new server has to exist before the host can be told
    #    to start it, and the rsync in step 2 copies this domain -- so the engine
    #    receives a config that already knows about itself.
    if ! cluster_resize "$name" "$addr" "$newmatch" "$DCOUNT"; then
        warn "could not add ${name} to the domain — nothing changed."
        local last=$(( ${#H_NAME[@]} - 1 ))
        unset "H_NAME[$last]" "H_ADDR[$last]" "H_PORT[$last]" "H_TYPE[$last]" "H_PUB[$last]" "H_FQDN[$last]" "H_ROLE[$last]"
        return 1
    fi

    # 2. then the host
    if ! provision_one_host "$n"; then
        warn "provisioning ${name} failed — it is in the domain but not running; re-run to retry."
        return 1
    fi

    # 3. the profile, only once both halves worked
    save_profile
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

    # Domain first: stop targeting the machine before tearing the host down.
    local newmatch="" i
    for i in $(seq 0 $((n - 1))); do newmatch="${newmatch:+${newmatch},}${H_NAME[$i]}"; done
    cluster_resize "" "" "$newmatch" "$n" || warn "domain not updated — continuing with host teardown."

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
    local mname="$1" maddr="$2" newmatch="$3" count="$4"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] online WLST, phase 1: ${mname:+create Machine ${mname} (${maddr}) + activate}${C_RESET}"
        log "${C_DIM}  [dry-run] online WLST, phase 2: match=${newmatch}; count=${count} + activate${C_RESET}"
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
    try:
        cd('/')
        cmo.createUnixMachine(mname)
    except:
        pass                      # already there is fine
    cd('/Machines/' + mname + '/NodeManager/' + mname)
    cmo.setListenAddress('${maddr}')
    cmo.setListenPort(int('${NM_PORT:-5556}'))
    cmo.setNMType('${NM_TYPE:-ssl}')
    save()
    activate(block='true')
    print('MACHINE_COMMITTED ' + mname)

# --- phase 2: only now can the cluster reference it --------------------------
edit()
startEdit()
cd('/Clusters/BEA_ENGINE_TIER_CLUST')
ds = cmo.getDynamicServers()
ds.setMachineNameMatchExpression('${newmatch}')
ds.setMaximumDynamicServerCount(int('${count}'))
save()
activate(block='true')
print('CLUSTER_RESIZED match=${newmatch} count=${count}')
PYEOF
    chmod 600 "${work}/resize.py"
    local out
    out="$("${MWHOME}/oracle_common/common/bin/wlst.sh" "${work}/resize.py" 2>&1)"
    rm -rf "$work"
    printf '%s\n' "$out" | grep -E "MACHINE_COMMITTED|CLUSTER_RESIZED" | sed 's/^/  /'
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
            log "  ${C_DIM}Re-run 'e'/'w' (and E for engines) so the units carry the link, then restart NM.${C_RESET}"
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

    if [ -z "$best" ] && jdk_dl_supported "$curmaj" \
       && yesno "JDK: current -> $(basename "$cur"); no newer JDK ${curmaj} on this host. Download Oracle's latest into ${base}?" "N"; then
        if download_jdk "$curmaj" "$base"; then
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

# Build a PATCHED Oracle home, out-of-place (dashboard: patch).
#
# Oracle's eDelivery media ships buggy and the fixes come from My Oracle Support,
# so patching sits between install and configure. This never touches the running
# home: it copies the one 'current' resolves to, patches the COPY, and stops.
# Nothing is switched. If a patch fails, the copy is discarded and the live
# install is exactly as it was.
#
# Patch source: a directory of downloaded Oracle patch .zip files. Every zip is
# unzipped and the OPatch patches are discovered from what unpacks — no hand-kept
# list. Numbered patches apply lowest-number-first; an OPatch-tool update (a zip
# that unpacks its own 'OPatch/' dir) applies first, since later patches may need
# the newer OPatch. A patch's directory (holding etc/config/inventory.xml) is
# named for its patch number, which is how they're ordered.
#
# Promotion is deliberate and separate:  sync-occas.sh distribute <ver> / switch <ver>
# Rollback is flipping 'current' back -- the old home is still there.
#
# Engines are never patched. They receive a home that was patched and validated
# once, here, on machine0.
do_patch() {
    local base="${OCCAS_BASE:-/opt/oracle/occas}" link="${MWHOME}"

    local real; real="$(readlink -f "$link" 2>/dev/null)"
    [ -n "$real" ] && [ -d "${real}/wlserver" ] || { warn "no Oracle home behind ${link} — install first."; return 1; }

    patch_jdk
    # Read java.home AFTER the JDK leg — migration may have just rewritten it.
    local jre; jre="$(read_prop "$OCCAS_CONF" java.home)"

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
        unzip -q -o "$z" -d "$stage" || warn "unzip failed: $(basename "$z") — skipping."
    done

    # OPatch-tool update: a zip unpacking its own 'OPatch/' dir (with an 'opatch'
    # launcher). A normal patch is a home carrying OPatch metadata at
    # etc/config/inventory.xml; its directory name is the patch number.
    local opdirs=() pnum=() ppath=()
    local seen=" " d bn key f
    while IFS= read -r d; do
        [ -n "$d" ] && [ -f "${d}/opatch" ] && opdirs+=("$d")
    done < <(find "$stage" "$pdir" -maxdepth 4 -type d -name OPatch 2>/dev/null)
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        d="$(dirname "$(dirname "$(dirname "$f")")")"   # …/etc/config/inventory.xml → patch home
        bn="$(basename "$d")"
        key="$(printf '%s' "$bn" | tr -cd '0-9')"; key="${key:-0}"
        case "$seen" in *" ${key} "*) continue ;; esac   # same patch found in stage AND pdir
        seen="${seen}${key} "
        pnum+=("$key"); ppath+=("$d")
    done < <(find "$stage" "$pdir" -type f -name inventory.xml -path '*/etc/config/inventory.xml' 2>/dev/null)

    if [ "${#opdirs[@]}" -eq 0 ] && [ "${#pnum[@]}" -eq 0 ]; then
        rm -rf "$stage"
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

    # Next free <ver>_p<n>. The base version never gains a suffix, so the GA home
    # stays identifiable however many rounds happen.
    local stem="${real##*/}"; stem="${stem%%_p*}"
    local n=1; while [ -e "${base}/${stem}_p${n}" ]; do n=$((n + 1)); done
    local copy="${base}/${stem}_p${n}"

    info "Patch ${stem} -> $(basename "$copy")   (${#opdirs[@]} OPatch update(s), ${#pnum[@]} patch(es), from ${pdir})"
    if [ "${#order[@]}" -gt 0 ]; then
        for pth in "${order[@]}"; do log "    $(basename "$pth")"; done
    fi

    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] cp -a ${real} ${copy}${C_RESET}"
        if [ "${#opdirs[@]}" -gt 0 ]; then
            for d in "${opdirs[@]}"; do log "${C_DIM}  [dry-run] OPatch update ← ${d} (replace ${copy}/OPatch)${C_RESET}"; done
        fi
        if [ "${#order[@]}" -gt 0 ]; then
            for pth in "${order[@]}"; do
                log "${C_DIM}  [dry-run] $(basename "$pth"): prereq CheckConflictAgainstOHWithDetail, then opatch apply -oh ${copy}${C_RESET}"
            done
        fi
        log "${C_DIM}  [dry-run] opatch lsinventory -oh ${copy} > ${copy}/.blade-patch-manifest${C_RESET}"
        rm -rf "$stage"
        return 0
    fi

    # Everything from here runs as the OWNER of the live home — not blindly
    # install.user: a legacy install owned by the login user patches as that
    # user, and opatch writes the central inventory the same owner holds.
    # IU_USER is dynamically scoped; every iu_*/as_install_user below follows.
    local IU_USER; IU_USER="$(iu_owner_user "$real")"

    info "Copying the home (the live one is not touched) …"
    as_install_user cp -a "$real" "$copy" || { warn "copy to ${copy} failed (disk space?)."; rm -rf "$stage"; return 1; }

    # Hand the unpacked patches to the home's owner so opatch (run as that user)
    # can read them; cleanup then runs as the same owner.
    iu_adopt_dir "$stage" || { rm -rf "$stage"; as_install_user rm -rf "$copy"; return 1; }

    local op="${copy}/OPatch/opatch"
    # OPatch-tool updates first — later patches may require the newer OPatch.
    if [ "${#opdirs[@]}" -gt 0 ]; then
        for d in "${opdirs[@]}"; do
            info "  OPatch update ← $(basename "$(dirname "$d")")"
            as_install_user sh -c "rm -rf '${copy}/OPatch' && cp -a '${d}' '${copy}/OPatch'" \
                || { warn "could not replace OPatch."; as_install_user rm -rf "$stage" "$copy"; return 1; }
        done
    fi

    # Numbered patches, lowest-first. cd into the patch home; opatch applies it.
    local pd zb
    if [ "${#order[@]}" -gt 0 ]; then
        for pd in "${order[@]}"; do
            zb="$(basename "$pd")"
            info "  ${zb}: conflict check"
            if ! as_install_user sh -c "cd '${pd}' && ORACLE_HOME='${copy}' '${op}' prereq CheckConflictAgainstOHWithDetail -ph '${pd}' -oh '${copy}' ${jre:+-jre '${jre}'} -silent" >/dev/null 2>&1; then
                warn "${zb}: conflict check FAILED — stopping. ${copy} discarded, live home untouched."
                as_install_user rm -rf "$stage" "$copy"; return 1
            fi
            info "  ${zb}: applying"
            if ! as_install_user sh -c "cd '${pd}' && ORACLE_HOME='${copy}' '${op}' apply -silent -oh '${copy}' ${jre:+-jre '${jre}'}"; then
                warn "${zb}: APPLY FAILED — stopping. ${copy} discarded, live home untouched."
                as_install_user rm -rf "$stage" "$copy"; return 1
            fi
        done
    fi
    as_install_user rm -rf "$stage"

    as_install_user sh -c "ORACLE_HOME='${copy}' '${op}' lsinventory -oh '${copy}' ${jre:+-jre '${jre}'} > '${copy}/.blade-patch-manifest' 2>&1" || true
    ok "Patched home ready: ${copy}"
    grep -cE "^Patch  *[0-9]+" "${copy}/.blade-patch-manifest" 2>/dev/null \
        | sed 's/^/  interim patches now present: /'
    log "  ${C_DIM}Nothing switched. Validate it, then:${C_RESET}"
    log "  ${C_DIM}  ./sync-occas.sh ${NAME} distribute $(basename "$copy")${C_RESET}"
    log "  ${C_DIM}  ./sync-occas.sh ${NAME} switch     $(basename "$copy")${C_RESET}"
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
#   2. sets Node Manager to bind ${NM_BIND} on ${NM_PORT} (ssl|plain),
#   3. starts Node Manager in the background and waits for it to listen.
# Enrolling app domains into this NM (nmEnroll) happens at configure/start time.
# Idempotent: an existing nmdomain is reconfigured + (re)started, not rebuilt.
# ----------------------------------------------------------------------------
do_nmdomain() {
    local mw="$MWHOME" nmdom="$NM_DOMAIN" bind="$NM_BIND" port="$NM_PORT" type="$NM_TYPE"
    local auser="${ADMIN_USER:-weblogic}" mode="${START_MODE:-dev}"
    [ -n "$mw" ]    || { warn "occas.conf: missing oracle.home"; return 1; }
    [ -n "$nmdom" ] || { warn "occas.conf: missing nm.domain.name"; return 1; }
    [ -n "$port" ]  || { warn "occas.conf: missing nm.listen.port"; return 1; }
    local nmhome="${DOMAINS_DIR}/${nmdom}"
    local tmpl="${mw}/wlserver/common/templates/wls/wls.jar"
    local secure; [ "$type" = "ssl" ] && secure="true" || secure="false"

    info "Node Manager domain '${nmdom}'  →  ${nmhome}"
    log  "  bind=${bind}  port=${port}  type=${type} (SecureListener=${secure})  admin=${auser}"

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
    ok "Node Manager bind set: ${bind}:${port} (SecureListener=${secure}, native=off)"

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
    local nmlog="${nmhome}/nodemanager/nodemanager.out"
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
JDK_DL_HOME=""
download_jdk() {
    JDK_DL_HOME=""
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

# ----------------------------------------------------------------------------
# Create the Linux install user + group that own OCCAS (defaults oracle:oinstall).
# Idempotent; needs root or passwordless sudo for the actual creates.
# ----------------------------------------------------------------------------
do_makeuser() {
    local user="${INSTALL_USER:-oracle}" grp="${INV_GRP:-oinstall}"
    local uid="${INSTALL_UID:-}" gid="${INSTALL_GID:-}"
    # install.uid/gid pin the NUMERIC ids so every host in the cluster agrees --
    # rsync -a carries numbers, not names (see phase_occas). Blank = OS picks.
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
    # The servers read the TLS keystores at runtime — install-user territory too
    # (and outside the Oracle home, so a patch flip cannot swap them).
    if [ -n "${KEYSTORE_DIR:-}" ]; then
        $SUDO mkdir -p "$KEYSTORE_DIR" && $SUDO chown "${user}:${grp}" "$KEYSTORE_DIR" \
            && ok "created + chowned ${KEYSTORE_DIR} (TLS keystores)." \
            || warn "could not set up ${KEYSTORE_DIR}."
    fi
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
    URLS_FILE="${PROFILE_DIR}/occas.urls"

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
        [ -n "$wsh" ] || { warn "No wget.sh yet — do the browser step above, then re-run 'd'; it resumes."; return 1; }
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
                warn "Re-run 'd' with a fresh token; if it still fails, delete ${URLS_FILE} for a fresh wget.sh."
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
emit_tls_block() {
    local tmpl="${1}-template" ksdir="${KEYSTORE_DIR:-/opt/oracle/security}"
    local kspw trpw
    kspw="$(read_prop "$DEPLOY_SECRET" tls.keystore.passphrase)"
    trpw="$(read_prop "$DEPLOY_SECRET" tls.trust.passphrase)"
    local alias="${ID_ALIAS:-blade-identity}"
    [ -n "$kspw" ] && [ -n "$trpw" ] || { warn "TLS passphrases missing from deploy.secret."; return 1; }

    # Identity + trust, applied to the template AND to the real servers.
    _emit_keystores() {
        cat <<PYBLOCK
cd('${1}')
set('KeyStores','CustomIdentityAndCustomTrust')
set('CustomIdentityKeyStoreFileName','${ksdir}/blade-identity.p12')
set('CustomIdentityKeyStoreType','PKCS12')
set('CustomIdentityKeyStorePassPhraseEncrypted','${kspw}')
set('CustomTrustKeyStoreFileName','${ksdir}/blade-trust.p12')
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
set('ListenPort',${SSL_PORT:-7002})
set('ServerPrivateKeyAlias','${alias}')
set('ServerPrivateKeyPassPhraseEncrypted','${kspw}')
PYBLOCK
    }

    echo "# --- BLADE: real certificate + SIP channels (no demo certs) ---"
    _emit_keystores "/ServerTemplates/${tmpl}" "${tmpl}"
    _emit_keystores "/Servers/AdminServer" "AdminServer"

    # Dynamic-server shape, set at CREATE time so a rebuild keeps it.
    #
    # ServerNameStartingIndex=0 is what makes machine0 run engine0 -- the local
    # engine is stamped from the same template as every other one, so there is no
    # static server to special-case.
    #
    # CalculatedListenPorts=false gives every engine the template's ports
    # verbatim (5060/5061/8001) instead of base+index. Incrementing only makes
    # sense when several engines share a host -- a developer laptop, not a SIP
    # tier. The trade is one engine per machine, which is exactly the shape
    # "add a machine" produces.
    #
    # The DynamicServers child is named after the server prefix in the domain
    # this template builds. Try the cluster name too rather than fail the whole
    # domain build if a future template names it differently.
    cat <<PYBLOCK
for _dsn in ['${prefix:-engine}','${1}']:
    try:
        cd('/Clusters/${1}/DynamicServers/' + _dsn)
        set('ServerNameStartingIndex',${SRV_START_INDEX:-0})
        set('CalculatedListenPorts','$([ "${DYN_CALC_PORTS:-false}" = true ] && echo true || echo false)')
        set('MachineNameMatchExpression','${match:-machine0}')
        set('MaximumDynamicServerCount',${DCOUNT:-1})
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
    unset -f _emit_keystores
}

# Admin password: env > occas.secret > prompt (skipped under dry-run).
get_admin_pw() {
    local v="${BLADE_WLS_PASSWORD:-}"
    [ -z "$v" ] && [ -f "$OCCAS_SECRET" ] && v="$(read_prop "$OCCAS_SECRET" admin.password)"
    if [ -z "$v" ] && [ "$DRY" != "on" ]; then
        # The cursor-newline must go to stderr: callers capture this function's
        # stdout via $(get_admin_pw), and a stray newline there prepends to the
        # password (breaking e.g. the WLST setPassword('…') literal).
        read -rs -p "  Admin password for the new domain: " v || v=""; echo >&2
        [ -n "$v" ] || { warn "no password provided."; return 1; }
    fi
    printf '%s' "$v"
}

# ----------------------------------------------------------------------------
# Step 2 — dynamic-cluster domain from Oracle's template, parameterized.
# Writes with OverwriteDomain=true (the template's default) — clobbers an
# existing domain dir of the same name.
# ----------------------------------------------------------------------------
do_configure() {
    local mwhome domain mode auser prefix match dcount dmax static chk
    mwhome="$(read_prop "$OCCAS_CONF" oracle.home)"
    domain="$(read_prop "$OCCAS_CONF" domain.name)"
    mode="$(read_prop "$OCCAS_CONF" server.start.mode)";   mode="${mode:-dev}"
    auser="$(read_prop "$OCCAS_CONF" admin.username)";     auser="${auser:-weblogic}"
    prefix="$(read_prop "$OCCAS_CONF" server.name.prefix)"
    match="$(read_prop "$OCCAS_CONF" machine.match.expression)"
    dcount="$(read_prop "$OCCAS_CONF" dynamic.server.count)"
    dmax="$(read_prop "$OCCAS_CONF" max.dynamic.cluster.size)"
    for chk in mwhome domain prefix match dcount dmax; do
        [ -n "${!chk}" ] || { warn "occas.conf: missing $chk (required for configure)"; return 1; }
    done

    local machines=() i=1 m
    while :; do
        m="$(read_prop "$OCCAS_CONF" "machine.${i}")"; [ -n "$m" ] || break
        machines+=("$m"); i=$((i + 1))
    done
    [ "${#machines[@]}" -ge 1 ] || { warn "occas.conf: no machine.N entries"; return 1; }

    local pw; pw="$(get_admin_pw)" || return 1

    info "Configure domain '${domain}' (${mode}) — dynamic cluster"
    log  "  prefix=${prefix}  match=${match}  count=${dcount}  max=${dmax}"

    local props name addr port type idx=1
    props="ADMIN_USERNAME=${auser}
ADMIN_PASSWORD=__PW__
ServerNamePrefix=${prefix}
MachineNameMatchExpression=${match}
MaximumDynamicServerCount=${dcount}
MaxDynamicClusterSize=${dmax}"
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
    printf '%s\n' "${props/__PW__/$pw}" > "${work}/occas-replicated-dynamiccluster.properties"
    chmod 600 "${work}/occas-replicated-dynamiccluster.properties"

    # Oracle's template computes domainDir from beaHome + user_projects/domains.
    # Point it at DOMAINS_DIR instead: a domain inside the Oracle home cannot
    # survive a patch symlink flip (it would swing onto the copy's snapshot).
    sed "s/^domainName=.*/domainName='${domain}'/; \
         s|^domainDir=.*|domainDir='${DOMAINS_DIR}/${domain}'|; \
         s/setOption('ServerStartMode', '[^']*')/setOption('ServerStartMode', '${mode}')/" \
        "${work}/occas-replicated-dynamiccluster.py" > "${work}/.py.tmp" \
        && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"

    # The template is about to reference blade-identity.p12 / blade-trust.p12 by
    # path, so they have to exist first. Placing them is install-ssl's
    # 'keystores' tier; its ssl/sip tiers are NOT used any more -- that work now
    # happens here, on the template, where dynamic servers can actually get it.
    local ksdir="${KEYSTORE_DIR:-/opt/oracle/security}"
    if [ "$DRY" != "on" ] && [ ! -f "${ksdir}/blade-identity.p12" ]; then
        info "Placing keystores in ${ksdir} …"
        "${SCRIPT_DIR}/tls/install-ssl.sh" "$DEPLOY_CONF" keystores apply \
            || warn "keystore placement failed — the domain will be built without TLS."
    fi

    # TLS goes in FIRST so the template already carries the real certificate
    # before any server is written from it.
    if [ "${SIP_TLS:-}" != "" ] || [ "${CERT_SOURCE:-}" != "" ]; then
        if emit_tls_block "BEA_ENGINE_TIER_CLUST" > "${work}/tls.block" 2>/dev/null; then
            chmod 600 "${work}/tls.block"
            awk 'NR==FNR { blk = blk $0 ORS; next }
                 /OverwriteDomain/ && !ins { printf "%s", blk; ins = 1 }
                 { print }' \
                "${work}/tls.block" "${work}/occas-replicated-dynamiccluster.py" \
                > "${work}/.py.tmp" && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"
            log "  TLS: real certificate on the server template; sip=$([ "$SIP_PLAIN" = false ] && echo off || echo on):${SIP_PLAIN_PORT:-5060} sips=$([ "$SIP_TLS" = true ] && echo on:${SIP_PORT:-5061} || echo off)"
        else
            warn "TLS block not generated — domain will be built without it."
        fi
    fi

    local jh rc=0; jh="$(read_prop "$OCCAS_CONF" java.home)"
    # The domain lands in the install user's DOMAINS_DIR, so WLST runs as them.
    iu_wlst_run "$work" occas-replicated-dynamiccluster.py "$mwhome" "$jh" || rc=$?
    as_install_user rm -rf "$work"
    [ "$rc" -eq 0 ] || { warn "configure failed (WLST rc=${rc})"; return 1; }
    ok "Domain '${domain}' written under ${DOMAINS_DIR}/"
    # Give the domain's servers enough heap/metaspace (the admin EAR OOMs the
    # OCCAS dev default) via setUserOverrides.sh, which the NM start path sources.
    write_user_overrides "${DOMAINS_DIR}/${domain}"
    # Enroll the new app domain into the standalone Node Manager so it can start
    # the AdminServer/engines. No-op-with-hint if the NM domain isn't built yet.
    register_domain_with_nm "$domain" "${DOMAINS_DIR}/${domain}" || true
    warn "Next: run 'n' (start/restart Node Manager so it sees this domain), then 's' to start the AdminServer."
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
        if iu_switching && [ -n "$installer" ] && [ -f "$installer" ]; then
            if as_install_user test -r "$installer"; then ok "installer readable by ${pf_as}"
            else log "  ${C_DIM}installer not readable by ${pf_as} — the install step stages a copy beside the install.${C_RESET}"; fi
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
                         || { warn "WLS basic template missing: ${nmtmpl} — 'n' (create NM domain) needs it."; PF_NEED="yes"; _pf_tmpl="yes"; }
    fi
    if nm_listening "$nmport"; then ok "Node Manager already listening on :${nmport}."
    else log "  ${C_DIM}Node Manager port :${nmport} is free (it'll start with the 'n' step).${C_RESET}"; fi

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
            log "    grant $(id -un) sudo (NOPASSWD covers unattended runs), or run ./blade.sh as '${pf_user}'."
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
            log "    the wlserver template is missing from ${mwhome} — re-run 'i' (install)."
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
    # has a side effect). A later config change just means re-running it.
    [ -n "$PF_NEED" ] && PF_OK=0 || PF_OK=1
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
        warn "Node Manager domain '${nmdom}' not set up yet — run the 'n' step first."
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
        [ -z "$apw" ] && [ -f "$OCCAS_SECRET" ] && apw="$(read_prop "$OCCAS_SECRET" admin.password)"
        set_domain_nm_credentials "$domhome" "$domname" "${ADMIN_USER:-weblogic}" "$apw" || true
    fi
    # Node Manager reads nodemanager.domains ONCE, at startup. A running NM has
    # therefore not seen what we just wrote, and every later nmConnect for this
    # domain fails with "no domain" — which is what an unattended install hits
    # between 'c' and 's'. Telling the user to go restart it by hand leaves a
    # half-done operation behind, so finish it here.
    if nm_listening "${NM_PORT:-$(read_prop "$OCCAS_CONF" nm.listen.port)}"; then
        info "Restarting Node Manager so it picks up the '${domname}' enrollment …"
        restart_nm || { warn "could not restart Node Manager — run 'k' then 'n', or the AdminServer start will fail."; return 1; }
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
# NM's listener is SSL, so WLST must trust whatever cert NM presents — and WLST
# trusts only the JDK cacerts by default, which contains neither. That surfaces
# as a bare PKIX "unable to find valid certification path", nowhere near the
# actual cause.
#
# Two cases, and both need handling:
#   * NM on the env PKI (after the TLS step) -> CustomTrust + the env trust.p12
#   * NM on WebLogic's DemoIdentity (a fresh nmdomain) -> DemoTrust
# Hostname verification is off either way: the cert names the host, while the
# units and interactive runs both nmConnect to 'localhost'.
nm_wlst_props() {
    local mw="${MWHOME:-$(read_prop "$OCCAS_CONF" oracle.home)}"
    local nmdom="${NM_DOMAIN:-$(read_prop "$OCCAS_CONF" nm.domain.name)}"
    local nmprops="${DOMAINS_DIR}/${nmdom}/nodemanager/nodemanager.properties"
    local common="-Dweblogic.security.SSL.ignoreHostnameVerification=true"
    if grep -q "^CustomIdentityKeyStoreFileName=" "$nmprops" 2>/dev/null; then
        local pw cdir
        pw="${BLADE_STORE_PASSWORD:-}"
        [ -z "$pw" ] && [ -f "$OCCAS_SECRET" ] && pw="$(read_prop "$OCCAS_SECRET" store.password)"
        cdir="$(read_prop "$OCCAS_CONF" certs.dir)"; cdir="${cdir/#\~/$HOME}"
        cdir="${cdir:-${HOME}/.blade/certs/${NAME}}"
        if [ -n "$pw" ] && [ -f "${cdir}/trust.p12" ]; then
            printf '%s' "-Dweblogic.security.TrustKeyStore=CustomTrust -Dweblogic.security.CustomTrustKeyStoreFileName=${cdir}/trust.p12 -Dweblogic.security.CustomTrustKeyStoreType=PKCS12 -Dweblogic.security.CustomTrustKeyStorePassPhrase=${pw} ${common}"
            return 0
        fi
        # Custom identity but no usable truststore — say so rather than fall
        # through to the demo store, which fails with the same opaque PKIX error.
        warn "Node Manager uses a custom identity but ${cdir}/trust.p12 or store.password is missing." >&2
    fi
    # Demo certs. NOT -Dweblogic.security.TrustKeyStore=DemoTrust: that resolves
    # to $WL_HOME/server/lib/DemoTrust.jks, which contains only the 2012
    # 'wlscertgenca'. WLS 14.1.2 generates a PER-DOMAIN demo CA instead
    # (CN=CertGenCA_<domain>) and keeps it in the domain's own
    # security/DemoTrust.p12, so the shipped store can never validate it — the
    # symptom is a bare PKIX failure that looks like a broken install.
    local demotrust="${DOMAINS_DIR}/${nmdom}/security/DemoTrust.p12"
    if [ -f "$demotrust" ]; then
        printf '%s' "-Dweblogic.security.TrustKeyStore=CustomTrust -Dweblogic.security.CustomTrustKeyStoreFileName=${demotrust} -Dweblogic.security.CustomTrustKeyStoreType=PKCS12 -Dweblogic.security.CustomTrustKeyStorePassPhrase=DemoTrustKeyStorePassPhrase ${common}"
        return 0
    fi
    printf '%s' "-Dweblogic.security.TrustKeyStore=DemoTrust ${common}"
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

    # Prefer the boot service when it's installed and owns this nmdomain: that
    # keeps systemd's idea of the process and ours from diverging.
    if command -v systemctl >/dev/null 2>&1 \
       && grep -qsF -- "$nmhome" /etc/systemd/system/nodemanager.service; then
        sudo systemctl restart nodemanager.service 2>/dev/null || { warn "systemctl restart nodemanager.service failed."; return 1; }
    else
        stop_nm || true
        local nmlog="${nmhome}/nodemanager/nodemanager.out"
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

# Write <domain>/bin/setUserOverrides.sh so every server launched in this domain
# gets enough heap/metaspace. Node Manager starts servers via the start script,
# which sources setDomainEnv.sh, which sources THIS hook — so USER_MEM_ARGS here
# is what an NM-launched AdminServer/engine actually runs with. (The OCCAS dev
# default -Xmx512m -XX:MaxMetaspaceSize=256m OOMs on Metaspace when the admin EAR
# deploys.) Tune with server.mem.args in occas.conf. Idempotent; survives reconfig.
write_user_overrides() {
    local domhome="$1" mem
    [ -d "${domhome}/bin" ] || return 0
    local IU_USER; IU_USER="$(iu_owner_user "$domhome")"   # write as the domain's owner
    mem="$(read_prop "$OCCAS_CONF" server.mem.args)"
    mem="${mem:--Xms512m -Xmx1024m -XX:MaxMetaspaceSize=512m}"
    iu_write "${domhome}/bin/setUserOverrides.sh" 755 <<EOF
# BLADE - generated by blade.sh. Node Manager's start script sources
# setDomainEnv.sh, which sources this; USER_MEM_ARGS overrides the OCCAS dev
# default (-Xmx512m -XX:MaxMetaspaceSize=256m) that OOMs on Metaspace when the
# admin EAR deploys. Applies to every server; change server.mem.args in
# occas.conf and re-run configure (or 's') to update. To split AdminServer vs
# engines, branch on \$SERVER_NAME here.
USER_MEM_ARGS="${mem}"
export USER_MEM_ARGS
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
    [ -d "$domhome" ] || { warn "app domain not found: ${domhome} — create it first (configure / 'c')."; return 1; }
    nm_listening "$nmport" || { warn "Node Manager isn't listening on :${nmport} — start it first ('n')."; return 1; }
    # Everything below touches or runs against the EXISTING domain — as its owner.
    local IU_USER; IU_USER="$(iu_owner_user "$domhome")"
    # Starting needs the domain enrolled (no-op if already) + adequate launch memory.
    [ "$action" = "start" ] && { register_domain_with_nm "$dom" "$domhome" || true; write_user_overrides "$domhome"; }
    # NM credentials = the admin creds (env > occas.secret > misc/.nmsecret).
    # The .nmsecret fallback is resolved HERE, not in the piped script: under
    # `bash -s` its $(dirname "$0") is the CWD, not misc/, and the checkout
    # isn't readable by the install user anyway.
    local pw="${BLADE_WLS_PASSWORD:-}"
    [ -z "$pw" ] && [ -f "$OCCAS_SECRET" ] && pw="$(read_prop "$OCCAS_SECRET" admin.password)"
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
    [ -z "$pw" ] && { warn "no admin password (env / occas.secret / misc/.nmsecret) — cannot nmConnect; aborting the ${action}."; return 1; }
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
stop_admin() {
    local oh="$1" dom="$2"
    local domhome="${DOMAINS_DIR}/${dom}"
    if [ "$DRY" = "on" ]; then log "${C_DIM}  [dry-run] OS-stop servers under ${domhome}${C_RESET}"; return 0; fi
    [ -d "$domhome" ] || { warn "app domain not found: ${domhome}."; return 1; }
    info "Stopping servers for '${dom}' (OS-level — pure-Java NM can't nmKill child JVMs)"
    kill_domain_procs "$domhome"
}

# Synchronously kill the JVMs belonging to a domain (matched by domain home in
# their cmdline — never a blind pkill). Waits for exit, escalates to SIGKILL.
kill_domain_procs() {
    local home="$1" p cmd pids="" n=0 i=0
    command -v pgrep >/dev/null 2>&1 || { warn "no pgrep — can't OS-stop servers."; return 1; }
    # Signal as the domain's owner (see stop_nm) — EPERM is a silent no-op kill.
    local IU_USER; IU_USER="$(iu_owner_user "$home")"
    for p in $(pgrep -f weblogic.Name 2>/dev/null || true); do
        cmd="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)"
        case "$cmd" in *"$home"*) pids="${pids} ${p}" ;; esac
    done
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
    local have_prof=0; [ -n "${PROFILE_DIR:-}" ] && [ -d "$PROFILE_DIR" ] && have_prof=1
    if [ "$have_dom" = 0 ] && [ "$have_prof" = 0 ]; then
        ok "domain '${dom}' and its profile already gone — nothing to remove."; return 0
    fi
    # The uninstall ladder sets KEEP_PROFILE so an iterate-fast reinstall can reuse
    # the profile's config + secrets; interactive 'r' clears it (removes both).
    local profnote="; rm -rf ${PROFILE_DIR}"; local proflabel=" AND profile '${NAME}'"
    if [ "${KEEP_PROFILE:-0}" = 1 ]; then profnote=" (keeping the profile config)"; proflabel=""; fi
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] stop AdminServer; kill stray JVMs; un-enroll ${dom}; rm -rf ${domhome}; remove weblogic.service (if it points here)${profnote}${C_RESET}"
        [ "$have_dom" = 1 ] && remove_domain_systemd_unit "$domhome" weblogic.service
        remove_engine_systemd_units "$domhome" "${DOMAINS_DIR}/${NM_DOMAIN}"
        return 0
    fi
    yesno "Remove domain '${dom}'${proflabel}? Stops its servers, DELETES ${domhome}, removes its weblogic.service unit${proflabel:+, and erases the profile config + secrets at ${PROFILE_DIR}}." "N" \
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
                && ok "un-enrolled '${dom}' from ${nmdom} (restart NM with 'k' then 'n' to apply)."
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
        rm -rf "$PROFILE_DIR" && { ok "removed profile '${NAME}' (${PROFILE_DIR})."; PROFILE_GONE=1; }
    elif [ "$have_prof" = 1 ]; then
        ok "kept profile '${NAME}' (${PROFILE_DIR}) — reinstall with: ./blade.sh ${NAME} install"
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
# GitHub remote. blade.sh is running from inside this tree, so we detach the rm to
# a background shell that fires after we exit, then set REPO_GONE so the dashboard
# drops out cleanly instead of redrawing from a directory that's about to vanish.
do_remove_repo() {
    local dir="$SCRIPT_DIR"
    { [ -n "$dir" ] && [ -d "$dir" ]; } || { warn "can't locate the repo dir."; return 1; }
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] rm -rf ${dir}  (local clone only — GitHub remote untouched)${C_RESET}"; return 0
    fi
    yesno "Delete the LOCAL BLADE repo clone at ${dir}? Removes blade.sh and everything here. Your GitHub remote is NOT affected." "N" \
        || { warn "kept the repo at ${dir}."; return 1; }
    # Detach so this process finishes before its own script tree is unlinked.
    nohup sh -c "sleep 1; rm -rf '$dir'" >/dev/null 2>&1 &
    ok "removing ${dir} (local clone) — BLADE will exit now. GitHub is untouched."
    REPO_GONE=1
}

# ============================================================================
# Deploy — push the built artifacts to their WebLogic targets via WLST
# (misc/deploy-wls.sh; needs only the OCCAS install, no Maven). Maven's only job
# was building/bundling the framework into the WARs. Reads the newest
# dist/<ver>-<build>/ (override with $BLADE_DIST).
#   shared    blade-shared.war  (library) -> AdminServer + cluster
#   admin     blade-admin.ear              -> AdminServer
#   services  dist/services/*.war (loose)  -> BEA_ENGINE_TIER_CLUST
#   test      blade-test.ear               -> the static engine (engine0)
#
# The services tier has no EAR: Oracle's Remote Console cannot show the status
# of an application bundled inside one, so each service deploys as its own WAR
# and shows up individually.
# ============================================================================
_dist_dir() {
    if [ -n "${BLADE_DIST:-}" ]; then printf '%s' "${BLADE_DIST%/}"; return; fi
    local d; d="$(ls -1dt "${SCRIPT_DIR}/dist"/*/ 2>/dev/null | head -1)"
    printf '%s' "${d%/}"
}

# Authoritative AdminServer t3/t3s URL from the live domain config (the server
# often binds the host IP, not localhost). Falls back to deploy.conf, then
# localhost. TLS everywhere: when the AdminServer's <ssl> block is enabled,
# prefer t3s on the SSL port — mandatory once TLS is on (blade.sh row 't')
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

# The engine on THIS machine -- prefix + starting index (machine0 runs engine0).
_test_target() {
    local pfx idx
    pfx="$(read_prop "$OCCAS_CONF" server.name.prefix)"; pfx="${pfx:-engine}"
    idx="$(read_prop "$OCCAS_CONF" server.name.starting.index)"; idx="${idx:-0}"
    printf '%s%s' "$pfx" "$idx"
}

# One WLST deploy/undeploy/status via misc/deploy-wls.sh.
_deploy_one() {
    local action="$1" name="$2" source="$3" targets="$4" library="${5:-false}"
    local url auser pw
    url="$(_wls_adminurl)"
    auser="$(read_prop "$OCCAS_CONF" admin.username)"; auser="${auser:-weblogic}"
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] WLST ${action} ${name}${source:+ src=$(basename "$source")}${targets:+ -> ${targets}}$([ "$library" = true ] && echo ' (library)') @ ${url}${C_RESET}"
        return 0
    fi
    [ "$action" = "deploy" ] && [ ! -f "$source" ] && { warn "missing artifact: ${source} — run ./build.sh first."; return 1; }
    pw="${BLADE_WLS_PASSWORD:-}"; [ -z "$pw" ] && [ -f "$OCCAS_SECRET" ] && pw="$(read_prop "$OCCAS_SECRET" admin.password)"
    # t3s trust: the occas conf's trust keystore (certs.sh layout) + store password.
    local trust="" trustpw=""
    case "$url" in t3s://*)
        trust="$(read_prop "$OCCAS_CONF" trust.keystore)"; trust="${trust/#\~/$HOME}"
        if [ -z "$trust" ]; then
            local cdir; cdir="$(read_prop "$OCCAS_CONF" certs.dir)"; cdir="${cdir/#\~/$HOME}"; cdir="${cdir:-${HOME}/.blade/certs/${NAME}}"
            [ -f "${cdir}/trust.p12" ] && trust="${cdir}/trust.p12"
        fi
        trustpw="${BLADE_STORE_PASSWORD:-}"; [ -z "$trustpw" ] && [ -f "$OCCAS_SECRET" ] && trustpw="$(read_prop "$OCCAS_SECRET" store.password)"
        ;;
    esac
    MW_HOME="$MWHOME" JAVA_HOME="${JAVA_HOME_VAL:-${JAVA_HOME:-}}" \
        WLS_ADMINURL="$url" WLS_USER="$auser" WLS_PASSWORD="$pw" \
        WLS_TRUSTSTORE="$trust" WLS_TRUSTSTORE_PASSWORD="$trustpw" \
        WLS_ACTION="$action" WLS_NAME="$name" WLS_SOURCE="$source" WLS_TARGETS="$targets" WLS_LIBRARY="$library" \
        bash "${SCRIPT_DIR}/misc/deploy-wls.sh" || { warn "deploy ${action} ${name} failed"; return 1; }
}

# Deploy/undeploy every service WAR in dist/services/, one at a time.
#
# The app name is the WAR basename, which is also its context root — so a
# service shows up in Remote Console under the name you'd expect ("transfer",
# "events") and can be stopped, started and retargeted on its own. That is the
# whole reason this tier is loose WARs rather than one EAR.
#
# Keeps going after a failure so one bad service doesn't strand the rest, and
# reports non-zero if any of them failed.
_deploy_services() {
    local action="$1" dist="$2" rc=0 war app
    local src="${dist}/services"
    [ -d "$src" ] || { warn "no ${src}/ in this build — run ./build.sh first."; return 1; }
    shopt -s nullglob
    local wars=("$src"/*.war)
    shopt -u nullglob
    [ ${#wars[@]} -gt 0 ] || { warn "no service WARs in ${src}."; return 1; }
    for war in "${wars[@]}"; do
        app="$(basename "${war%.war}")"
        _deploy_one "$action" "$app" "$war" "BEA_ENGINE_TIER_CLUST" || rc=1
    done
    return "$rc"
}

# Deploy/undeploy a single tier. tier = shared|admin|services|test.
do_deploy_tier() {
    local tier="$1" action="${2:-deploy}" dist; dist="$(_dist_dir)"
    [ -n "$dist" ] && [ -d "$dist" ] || { warn "no dist/ build found — run ./build.sh first."; return 1; }
    case "$tier" in
        shared)   _deploy_one "$action" blade-shared   "${dist}/blade-shared.war"  "AdminServer,BEA_ENGINE_TIER_CLUST" true ;;
        admin)    _deploy_one "$action" blade-admin    "${dist}/blade-admin.ear"    "AdminServer" ;;
        services) _deploy_services "$action" "$dist" ;;
        test)     _deploy_one "$action" blade-test     "${dist}/blade-test.ear"     "$(_test_target)" ;;
        *) warn "unknown deploy tier: $tier"; return 1 ;;
    esac
}

# Deploy the whole build in dependency order (library first).
do_deploy_all() {
    info "Deploying $(basename "$(_dist_dir)") — shared library first, then the EARs."
    local t rc=0
    for t in shared admin services test; do do_deploy_tier "$t" deploy || rc=1; done
    [ "$rc" -eq 0 ] && ok "deploy complete." || warn "deploy finished with errors."
    return 0
}
do_undeploy_all() {
    local t
    for t in test services admin shared; do do_deploy_tier "$t" undeploy || true; done
    ok "undeploy complete."
    return 0
}
do_deploy_status() { _deploy_one status "" "" "" ; }

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
    { [ -n "${PROFILE_DIR:-}" ] && [ -d "$PROFILE_DIR" ]; } || return 0
    local lf="${PROFILE_DIR}/blade.log"
    exec > >(tee -a "$lf") 2>&1
    LOGGING=1
    log ""
    log "===== blade ${BLADE_VERSION} · ${what} · $(date '+%Y-%m-%d %H:%M:%S') · profile '${NAME}' ====="
}

# Unattended runs can leave partial state if Ctrl-C'd mid-install; say so and
# point at the recovery path instead of dying silently.
trap_interrupt() {
    trap 'echo; warn "interrupted — state may be partial. Clean up with: ./blade.sh '"'"''"${NAME}"''"'"' uninstall"; exit 130' INT
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

# Unattended install: STEP 1→4 in order, no menu. Each worker is idempotent and
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
    local id
    for id in u m dl i g n c f s e w o; do
        rule; info "install step '${id}'"
        dispatch_row "$id"
    done
    rule
    ok "install complete for '${NAME}'."
    log "  verify with:  ./blade.sh ${NAME} status"
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
                     || log "  ${C_DIM}keeping the profile so './blade.sh ${NAME} install' can rebuild.${C_RESET}"
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
    log "  session log: ${PROFILE_DIR}/blade.log"
}

# Snapshot the profile (configs + secrets) and the domain's config tree to a tgz
# BEFORE a teardown, so a fat-fingered uninstall is recoverable. Kept OUTSIDE the
# profile dir (under .conf/.backups/) so removing the profile doesn't take the
# backups with it. Best-effort: never blocks the operation it precedes.
do_backup() {
    { [ -n "${PROFILE_DIR:-}" ] && [ -d "$PROFILE_DIR" ]; } || { warn "no profile dir — nothing to back up."; return 1; }
    local bdir="${CONF_BASE}/.backups"
    local dest="${bdir}/${NAME}-$(date '+%Y%m%d-%H%M%S').tgz"
    local domhome="${DOMAINS_DIR}/${DOMAIN:-}"
    local -a items=()
    local f; for f in "$PROFILE_DIR"/*.conf "$PROFILE_DIR"/*.secret; do [ -f "$f" ] && items+=("$f"); done
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
    # No name given: list profiles, let the user pick or create.
    profiles=()
    if [ -d "$CONF_BASE" ]; then
        for dpath in "$CONF_BASE"/*/; do
            [ -f "${dpath}occas.conf" ] && profiles+=("$(basename "$dpath")")
        done
    fi
    if [ "${#profiles[@]}" -eq 0 ]; then
        info "No profiles yet — name one and fill in the phases."
        NAME=""
        dashboard
    else
        log "${C_BOLD}BLADE install — profiles${C_RESET}"
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
    _need_profile() { [ -f "$OCCAS_CONF" ] || die "no profile '${NAME}' yet — create it first: ./blade.sh ${NAME}"; }
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
