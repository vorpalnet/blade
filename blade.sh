#!/usr/bin/env bash
# ============================================================================
# blade.sh - Guided installer/configurator for BLADE (OCCAS + BLADE).
#
# One interview builds a PROFILE — a directory under .conf/<name>/ holding the
# two config files the rest of the tooling reads (plus their secrets):
#
#   .conf/<name>/occas.conf     ./install-occas.sh   (silent install + domain)
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
#   - install OCCAS binaries (silent)
#   - create + start the standalone Node Manager domain 'nmdomain' (RUN: n).
#     Node Manager runs in its OWN basic domain, binding 0.0.0.0, so app/cluster
#     domains can be rebuilt/upgraded without ever taking Node Manager down.
#   - create the dynamic-cluster app domain and enroll it into that NM (RUN: c)
#   - start the AdminServer via Node Manager (RUN: s; misc/start-admin-nm.sh)
#   - stop Node Manager to re-read enrollments (RUN: k)
#   - set up TLS  (RUN: g/t; tls/make-certs.sh, tls/install-ssl.sh)
# Build with ./build.sh and deploy with ./deploy.sh <profile> afterwards.
#
# Usage:
#   ./blade.sh                 pick a profile (or create one), then the dashboard
#   ./blade.sh <name>          open profile <name> in the dashboard
#   ./blade.sh <name> wizard      run the full linear interview first
#   ./blade.sh <name> preflight   run host-prerequisite checks first
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

# --- args ---------------------------------------------------------------------
case "${1:-}" in -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;; esac
NAME="${1:-}"
JUMP="${2:-}"   # 'wizard' to skip the menu

# Existing-profile scalar defaults (edit mode). Set once NAME/paths are known.
OCCAS_CONF=""; DEPLOY_CONF=""; OCCAS_SECRET=""; DEPLOY_SECRET=""
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
    MEM_ARGS="$(d server.mem.args "-Xms512m -Xmx1024m -XX:MaxMetaspaceSize=512m")"
    MWHOME="$(d oracle.home "${MW_HOME:-}")"
    OCCAS_VERSION="$(d occas.version "")"
    INSTALLER_JAR="$(d installer.jar "")"
    INV_LOC="$(d inventory.loc /home/oracle/oraInventory)"
    INV_GRP="$(d inventory.group oinstall)"
    INSTALL_USER="$(d install.user oracle)"
    INSTALL_TYPE="$(d install.type 'Complete with Examples')"
    JAVA_HOME_VAL="$(d java.home "${JAVA_HOME:-}")"
    prefix="$(d server.name.prefix engine)"
    match="$(d machine.match.expression "")"
    NM_DOMAIN="$(d nm.domain.name nmdomain)"
    NM_BIND="$(d nm.bind.address 0.0.0.0)"
    NM_PORT="$(d nm.listen.port 5556)"
    NM_TYPE="$(d nm.type ssl)"
    DCOUNT="$(d dynamic.server.count "")"
    DMAX="$(d max.dynamic.cluster.size "")"
    STATIC="$(d static.server "")"
    SHARED_FS="$(d shared.filesystem true)"
    BUILD_PROFILE="$(d build.profile production)"
    SSH_USER="$(d ssh.user oracle)"
    # Never default to localhost — the AdminServer is reached over the network.
    # blade.sh runs ON the admin box, so its own hostname is the right default.
    ADMINURL="$(d wls.adminurl "t3://$(hostname -f 2>/dev/null || hostname):7001")"
    SSL_PORT="$(d tls.ssl.port 7002)"
    SIP_TLS="$(d sip.tls.enabled true)"
    SIP_PORT="$(d sip.tls.port 5061)"
    SIP_VER="$(d sip.tls.versions TLSv1.2)"
    SIP_TWOWAY="$(d sip.tls.twoway false)"
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
    ask INSTALLER_JAR "OCCAS installer jar (occas_generic.jar; Enter to skip if already installed)" "$INSTALLER_JAR"
    local dv=""
    [ -n "$INSTALLER_JAR" ] && dv="$(installer_version "$INSTALLER_JAR")"
    [ -n "$dv" ] && ok "read OCCAS version ${dv} from the installer"

    # 2. The OS user + group that will own OCCAS (the 'u' step creates them).
    ask INSTALL_USER "Install OS user (owns the OCCAS install)" "$INSTALL_USER"
    ask INV_GRP      "Install OS group"                         "$INV_GRP"

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

    # JDK for the INSTALLER + servers — version-locked to the OCCAS release.
    # This is NOT the build JDK: ./build.sh wants 23+ (it emits Java 11 bytecode).
    log ""
    local _want _found
    _want="$(occas_jdk_major "$OCCAS_VERSION")"
    _found="$(find_jdk "$_want")"
    if [ -n "$_want" ]; then
        if [ -n "$_found" ]; then
            ok "OCCAS ${OCCAS_VERSION} runs on JDK ${_want} — found one at ${_found}"
        elif jdk_dl_supported "$_want" \
             && yesno "OCCAS ${OCCAS_VERSION} runs on JDK ${_want} — none found. Download it from Oracle into /usr/lib/jvm?" "Y"; then
            download_jdk "$_want" && _found="$JDK_DL_HOME"
        else
            warn "OCCAS ${OCCAS_VERSION} runs on JDK ${_want} — none found here; install it or point me at one."
        fi
    else
        log "  ${C_DIM}no JDK recommendation on file for OCCAS ${OCCAS_VERSION} — see Oracle's certification matrix.${C_RESET}"
    fi
    log "  ${C_DIM}(the build JDK is separate — ./build.sh wants 23+.)${C_RESET}"
    local _jdef="$JAVA_HOME_VAL"; [ -n "$_jdef" ] || _jdef="${_found:-${JAVA_HOME:-}}"
    while :; do
        ask JAVA_HOME_VAL "JDK home for OCCAS (installer + servers run on this)" "$_jdef"
        [ -n "$JAVA_HOME_VAL" ] || { warn "a JDK home is required."; continue; }
        if [ ! -x "${JAVA_HOME_VAL}/bin/java" ]; then
            warn "no bin/java under ${JAVA_HOME_VAL} — that's not a JDK home."
            yesno "use it anyway?" "N" && break || continue
        fi
        local _got; _got="$(jdk_major "${JAVA_HOME_VAL}/bin/java")"
        if [ -n "$_want" ] && [ -n "$_got" ] && [ "$_got" != "$_want" ]; then
            warn "that's JDK ${_got}, but OCCAS ${OCCAS_VERSION} wants JDK ${_want}."
            yesno "use JDK ${_got} anyway?" "N" && break || continue
        fi
        break
    done
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

    # Snapshot current hosts for per-field defaults, then rebuild the arrays.
    local cur_an="${H_NAME[0]:-admin}" cur_aa="${H_ADDR[0]:-127.0.0.1}" cur_ap="${H_PUB[0]:-}" cur_af="${H_FQDN[0]:-}"
    local cur_eng=$(( ${#H_NAME[@]} > 1 ? ${#H_NAME[@]} - 1 : 0 ))
    local old_name=("${H_NAME[@]}") old_addr=("${H_ADDR[@]}") old_pub=("${H_PUB[@]}") old_fqdn=("${H_FQDN[@]}")
    H_NAME=(); H_ADDR=(); H_PORT=(); H_TYPE=(); H_PUB=(); H_FQDN=(); H_ROLE=()

    log ""
    log "  ${C_BOLD}AdminServer host${C_RESET}"
    local aname aaddr apub afqdn
    ask aname "  host / machine name (the HOST, e.g. 'admin' — not a server)" "$cur_an"
    ask aaddr "  reachable address (IP/host the AdminServer dials for NM)"    "$cur_aa"
    ask apub  "  public IP (for the cert SAN; Enter to skip)" "$cur_ap"
    ask afqdn "  fully-qualified DNS name (for SAN; Enter to skip)" "$cur_af"
    H_NAME+=("$aname"); H_ADDR+=("$aaddr"); H_PORT+=("$NM_PORT"); H_TYPE+=("$NM_TYPE")
    H_PUB+=("$apub");   H_FQDN+=("$afqdn"); H_ROLE+=("admin")

    local neng
    log ""
    [ "$cur_eng" -ge 1 ] || cur_eng=2
    ask neng "Number of SIP engine hosts" "$cur_eng"
    case "$neng" in ''|*[!0-9]*) neng=0 ;; esac
    local i ename eaddr epub efqdn dn da dp df
    i=1
    while [ "$i" -le "$neng" ]; do
        log ""
        log "  ${C_BOLD}Engine host ${i}${C_RESET}"
        dn="${old_name[$i]:-${prefix}${i}}"; da="${old_addr[$i]:-127.0.0.1}"
        dp="${old_pub[$i]:-}";               df="${old_fqdn[$i]:-}"
        ask ename "  host / machine name" "$dn"
        ask eaddr "  reachable address (IP/host the AdminServer dials for NM)" "$da"
        ask epub  "  public IP (for SAN; Enter to skip)" "$dp"
        ask efqdn "  fully-qualified DNS name (for SAN; Enter to skip)" "$df"
        H_NAME+=("$ename"); H_ADDR+=("$eaddr"); H_PORT+=("$NM_PORT"); H_TYPE+=("$NM_TYPE")
        H_PUB+=("$epub");   H_FQDN+=("$efqdn"); H_ROLE+=("engine")
        i=$((i + 1))
    done
    return 0
}

# ----- phase 4: dynamic cluster shape ----------------------------------------
phase_cluster() {
    log ""; log "${C_BOLD}Dynamic cluster — engine count${C_RESET}"
    help <<'EOF'
The engine cluster (BEA_ENGINE_TIER_CLUST) generates its servers from a count
rather than a hand-written list, so adding capacity later is a number bump.
EOF
    local neng defcount defmax
    neng=$(( ${#H_NAME[@]} > 1 ? ${#H_NAME[@]} - 1 : 0 ))
    defcount="${DCOUNT:-$neng}"; case "$defcount" in ''|*[!0-9]*) defcount="$neng" ;; esac
    [ "$defcount" -ge 1 ] || defcount=1
    defmax="${DMAX:-8}"; case "$defmax" in ''|*[!0-9]*) defmax=8 ;; esac
    [ "$defcount" -gt "$defmax" ] && defmax=$((defcount * 2))
    ask DCOUNT "Dynamic server count (now)"        "$defcount"
    ask DMAX   "Max dynamic cluster size (ceiling)" "$defmax"
    return 0
}

# ----- phase 5: static test engine -------------------------------------------
phase_static() {
    log ""; log "${C_BOLD}Static test engine${C_RESET}"
    help <<'EOF'
A fixed engine-tier member on the AdminServer box, for testing and BLADE config
file generation. It is never SBC-targeted. Recommended.
EOF
    local adminhost="${H_NAME[0]:-admin}"
    if yesno "Add a static test engine on '${adminhost}'?" "Y"; then
        local sn sl ss sp
        IFS=: read -r sn _ sl ss sp <<< "${STATIC:-::::}"
        ask sn "  test engine server name" "${sn:-${prefix}0}"
        ask sl "  HTTP listen port"        "${sl:-8001}"
        ask ss "  SIP (udp/tcp) port"      "${ss:-5060}"
        ask sp "  SIPS (tls) port"         "${sp:-5061}"
        STATIC="${sn}:${adminhost}:${sl}:${ss}:${sp}"
    else
        STATIC=""
    fi
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
        ask SSL_PORT "  HTTPS / t3s SSL port" "$SSL_PORT"
        if yesno "  Enable SIP TLS (sips channel on the engines)?" "$([ "$SIP_TLS" = false ] && echo N || echo Y)"; then SIP_TLS=true; else SIP_TLS=false; fi
        if [ "$SIP_TLS" = "true" ]; then
            ask SIP_PORT "  SIPS port"            "$SIP_PORT"
            ask SIP_VER  "  Enabled TLS versions" "$SIP_VER"
            if yesno "  Mutual TLS to the SBC (two-way)?" "$([ "$SIP_TWOWAY" = true ] && echo Y || echo N)"; then SIP_TWOWAY=true; else SIP_TWOWAY=false; fi
        fi
        ask CA_CN "  Internal CA common name"   "$CA_CN"
        ask ID_CN "  Identity cert common name" "$ID_CN"
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
    KEYSTORE_DIR="${MWHOME}/security"
    APPROUTER_DIR="${MWHOME}/user_projects/domains/${DOMAIN}/approuter"

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
        echo "# Consumed by ./install-occas.sh. Admin password lives in occas.secret."
        echo ""
        echo "# --- Step 1: silent product install (runs once; MW_HOME may be shared) ---"
        echo "oracle.home=${MWHOME}"
        echo "occas.version=${OCCAS_VERSION}"
        echo "installer.jar=${INSTALLER_JAR}"
        echo "inventory.loc=${INV_LOC}"
        echo "inventory.group=${INV_GRP}"
        echo "install.user=${INSTALL_USER}"
        echo "install.type=${INSTALL_TYPE}"
        echo "# JDK the installer (and the servers it configures) run on."
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
        if [ -n "$STATIC" ]; then
            echo ""
            echo "# --- Static test engine on the admin box (name:machine:listen:sip:sips) ---"
            echo "static.server=${STATIC}"
        fi
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
    log "${C_BOLD}BLADE installer${C_RESET}"
    [ -n "$NAME" ] && [ -f "$OCCAS_CONF" ] && log "  editing profile '${NAME}' (its values are offered as defaults)"
    load_profile
    # Journey order; phase_occas opens with the environment scan.
    phase_occas; phase_domain; phase_hosts; phase_cluster
    phase_static; phase_tls; phase_runtime
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
DRY="off"

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

# Build the current menu into MR_* parallel arrays (shared by TUI + fallback):
#   MR_TYPE head|phase|action   MR_ID   MR_LABEL   MR_VAL   MR_DONE(1|0|-)
build_menu_rows() {
    MR_TYPE=(); MR_ID=(); MR_LABEL=(); MR_VAL=(); MR_DONE=()
    local nhosts="${#H_NAME[@]}"
    local p_occas=0; { [ -n "$MWHOME" ] && [ -n "$OCCAS_VERSION" ] && [ -n "$JAVA_HOME_VAL" ]; } && p_occas=1
    local p_ident=0; [ -n "$DOMAIN" ] && p_ident=1
    local p_hosts=0; [ "$nhosts" -ge 1 ] && p_hosts=1
    local p_clu=0;   { [ -n "$DCOUNT" ] && [ -n "$DMAX" ]; } && p_clu=1
    local p_stat=0;  [ -n "$STATIC" ] && p_stat=1
    local p_tls=0;   [ -n "$SSL_PORT" ] && p_tls=1
    local p_run=0;   { [ -n "$BUILD_PROFILE" ] && [ -n "$ADMINURL" ]; } && p_run=1
    local a_i=0; [ -d "${MWHOME}/wlserver" ] && a_i=1
    local a_n=0; [ -d "${MWHOME}/user_projects/domains/${NM_DOMAIN}" ] && a_n=1
    local a_c=0; [ -d "${MWHOME}/user_projects/domains/${DOMAIN}" ] && a_c=1
    local nm_state="stopped"; nm_listening "$NM_PORT" && nm_state="running"
    local pwlbl="—"; _pw_set && pwlbl="set"

    _row() { MR_TYPE+=("$1"); MR_ID+=("$2"); MR_LABEL+=("$3"); MR_VAL+=("$4"); MR_DONE+=("$5"); }

    local a_u=0; id "${INSTALL_USER:-oracle}" >/dev/null 2>&1 && a_u=1
    local a_m=0; [ -n "$MWHOME" ] && [ -d "$MWHOME" ] && a_m=1
    _row head ""      "STEP 1 · Point at OCCAS, then install it" "" "-"
    _row phase  occas "Where OCCAS lives — home, version, Java"  "$(_sum_occas)" "$p_occas"
    _row action u     "Create install user & group"             "${INSTALL_USER:-oracle}:${INV_GRP:-oinstall}" "$a_u"
    _row action m     "Create install dirs & chown"             "MW_HOME + inventory" "$a_m"
    _row action p     "Preflight host checks"                    "" "-"
    _row action i     "Install OCCAS"                            "$([ "$a_i" = 1 ] && echo installed || echo '')" "$a_i"
    _row head ""      "STEP 2 · Name it & set the admin login"   "" "-"
    _row phase  ident "Domain name + admin user & password"      "${DOMAIN:-—} / ${ADMIN_USER} · pw ${pwlbl}" "$p_ident"
    _row head ""      "STEP 3 · Describe your machines"          "" "-"
    _row phase  hosts   "Hosts & Node Manager"     "${nhosts} host(s) · ${NM_DOMAIN}@${NM_BIND}:${NM_PORT} ${NM_TYPE}" "$p_hosts"
    _row phase  cluster "How many engine servers"  "$([ -n "$DCOUNT" ] && echo "count ${DCOUNT}, max ${DMAX}" || echo —)" "$p_clu"
    _row phase  static  "Test engine (optional)"   "${STATIC:-—}" "$p_stat"
    _row head ""      "STEP 4 · Start it up (in order)"          "" "-"
    _row action n "Create & start Node Manager" "${NM_DOMAIN} — ${nm_state}" "$a_n"
    _row action c "Create the cluster domain"   "${DOMAIN:-?}" "$a_c"
    _row action s "Start the AdminServer"       "" "-"
    _row action x "Stop the AdminServer"        "" "-"
    _row action k "Stop Node Manager"           "" "-"
    _row head ""      "STEP 5 · TLS (optional)"                  "" "-"
    _row phase  tls "TLS settings"          "$(_sum_tls)" "$p_tls"
    _row action g "Make certificates"       "" "-"
    _row action t "Turn on HTTPS / SIP-TLS" "" "-"
    _row head ""      "STEP 6 · Deploy settings (build profile, SSH, admin URL)" "" "-"
    _row phase runtime "Build profile, SSH user, admin URL" "${BUILD_PROFILE} · ${ADMINURL}" "$p_run"
    local distlbl; distlbl="$(ls -1t "${SCRIPT_DIR}/dist" 2>/dev/null | head -1)"; distlbl="${distlbl:-no build — run ./build.sh}"
    _row head ""      "STEP 7 · Deploy to WebLogic (./build.sh first)" "" "-"
    _row action y "Deploy everything (shared library -> the 3 EARs)" "$distlbl" "-"
    _row action l "List current deployments" "" "-"
    _row action z "Undeploy everything" "" "-"
    _row head ""      "RESET · start a clean install (keeps Node Manager)" "" "-"
    _row action r "Remove this app domain (stop servers, delete, un-enroll)" "${DOMAIN:-?}" "$a_c"
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
        static)  phase_static;  save_profile ;;
        tls)     phase_tls;     save_profile ;;
        runtime) phase_runtime; save_profile ;;
        u) do_makeuser  || true ;;
        m) do_makedirs  || true ;;
        p) do_preflight || true ;;
        i) do_install   || warn "install returned an error" ;;
        n) do_nmdomain  || warn "nm-domain returned an error" ;;
        c) do_configure || warn "configure returned an error" ;;
        s) start_admin "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        x) stop_admin  "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        k) stop_nm || true ;;
        y) do_deploy_all || true ;;
        l) do_deploy_status || true ;;
        z) do_undeploy_all || true ;;
        r) do_remove_domain "$MWHOME" "$DOMAIN" "$ADMIN_USER" || true ;;
        g) "${SCRIPT_DIR}/tls/make-certs.sh" "$DEPLOY_CONF" || warn "make-certs returned an error" ;;
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
_read_key() {
    local k r
    IFS= read -rsn1 k 2>/dev/null || { printf 'quit'; return; }
    case "$k" in
        $'\e') IFS= read -rsn2 -t 0.05 r 2>/dev/null || r=""
               case "$r" in '[A'|'OA') printf 'up' ;; '[B'|'OB') printf 'down' ;; *) printf 'other' ;; esac ;;
        '')    printf 'enter' ;;
        ' ')   printf 'space' ;;
        d|D)   printf 'dry' ;;
        q|Q)   printf 'quit' ;;
        *)     printf 'other' ;;
    esac
}

# Cursor-driven TUI: ↑/↓ move, space toggles [x], Enter runs the checked set
# (or the highlighted row if none checked), d toggles dry-run, q quits.
dashboard_tui() {
    declare -A CHK=()
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

        printf '\e[2J\e[H'
        printf '  %sBLADE installer%s · %s        dry-run: %s\n' "$C_BOLD" "$C_RESET" "$NAME" "$DRY"
        for i in "${!MR_TYPE[@]}"; do
            if [ "${MR_TYPE[$i]}" = head ]; then
                printf '\n  %s%s%s\n' "$C_BOLD" "${MR_LABEL[$i]}" "$C_RESET"
                continue
            fi
            local box="[ ]"; [ -n "${CHK[${MR_ID[$i]}]:-}" ] && box="[x]"
            local g=" "; case "${MR_DONE[$i]}" in 1) g="✓" ;; 0) g="○" ;; esac
            local arrow="  "; [ "${MR_TYPE[$i]}" = action ] && arrow="→ "
            if [ "$i" = "$cur" ]; then
                printf '\e[7m   %s %s %s%-42s %s \e[0m\n' "$box" "$g" "$arrow" "${MR_LABEL[$i]}" "${MR_VAL[$i]}"
            else
                printf '   %s %s %s%-42s %s%s%s\n' "$box" "$g" "$arrow" "${MR_LABEL[$i]}" "$C_DIM" "${MR_VAL[$i]}" "$C_RESET"
            fi
        done
        printf '\n  %s↑/↓%s move · %sspace%s select · %senter%s run · %sd%s dry-run · %sq%s quit\n' \
               "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET" "$C_BOLD" "$C_RESET"

        case "$(_read_key)" in
            up)    sel=$((sel - 1)) ;;
            down)  sel=$((sel + 1)) ;;
            space) local id="${MR_ID[$cur]}"
                   if [ -n "${CHK[$id]:-}" ]; then unset 'CHK[$id]'; else CHK[$id]=1; fi ;;
            dry)   [ "$DRY" = "on" ] && DRY="off" || DRY="on" ;;
            enter) local runids=() j id
                   for j in "${!MR_TYPE[@]}"; do
                       [ "${MR_TYPE[$j]}" = head ] && continue
                       id="${MR_ID[$j]}"; [ -n "${CHK[$id]:-}" ] && runids+=("$id")
                   done
                   [ "${#runids[@]}" -eq 0 ] && runids=("${MR_ID[$cur]}")
                   printf '\e[?25h'; trap - INT
                   printf '\e[2J\e[H'
                   local rid; for rid in "${runids[@]}"; do dispatch_row "$rid"; done
                   CHK=()
                   printf '\n  %s[done] press Enter to return…%s' "$C_DIM" "$C_RESET"; IFS= read -r _ || true
                   load_profile
                   printf '\e[?25l'; trap 'printf "\e[?25h\n"' EXIT INT ;;
            quit)  break ;;
            *)     : ;;
        esac
    done
    printf '\e[?25h'; trap - EXIT INT
    log ""
    log "  ${C_DIM}Next: ./build.sh   then   ./deploy.sh ${NAME}${C_RESET}"
    return 0
}

# Typed fallback (no TTY): the same rows, numbered; run by number(s)/all/d/q.
dashboard_menu() {
    while :; do
        build_menu_rows
        log ""
        log "${C_BOLD}BLADE installer — profile '${NAME}'${C_RESET}    dry-run: ${DRY}"
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
                all) local k; for k in occas ident hosts cluster static tls runtime; do dispatch_row "$k"; done ;;
                d)   [ "$DRY" = "on" ] && DRY="off" || DRY="on"; log "  dry-run: ${DRY}" ;;
                q)   quit=1 ;;
                *[!0-9]*) warn "unknown choice: $tok" ;;
                *)   [ -n "${idmap[$tok]:-}" ] && dispatch_row "${idmap[$tok]}" || warn "no row ${tok}" ;;
            esac
        done
        [ "$quit" = 1 ] && break
    done
    log ""
    log "  ${C_DIM}Next: ./build.sh   then   ./deploy.sh ${NAME}${C_RESET}"
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
    command -v ss >/dev/null 2>&1 || { warn "need 'ss' to find the Node Manager PID — stop it manually."; return 1; }
    local pids p killed=0 cmd
    pids="$(ss -ltnpH "( sport = :${port} )" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)"
    [ -n "$pids" ] || { warn "couldn't resolve the PID on :${port} (try as root) — stop it manually."; return 1; }
    local killpids=""
    for p in $pids; do
        cmd="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)"
        case "$cmd" in
            *NodeManager*|*nodemanager*|*"${NM_DOMAIN}"*) kill "$p" 2>/dev/null && { killed=1; killpids="${killpids} ${p}"; } ;;
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
        for p in $killpids; do kill -9 "$p" 2>/dev/null || true; done
        i=0; while nm_listening "$port" && [ "$i" -lt 5 ]; do sleep 1; i=$((i + 1)); done
    fi
    if nm_listening "$port"; then warn "could not free :${port}."; return 1; fi
    ok "stopped Node Manager (${killpids# }); :${port} free."
    return 0
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
    local nmhome="${mw}/user_projects/domains/${nmdom}"
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
        local rc=0
        ( cd "$work" && "$wlst" "${work}/nmdomain.py" ) || rc=$?
        rm -rf "$work"
        [ "$rc" -eq 0 ] || { warn "WLST failed creating nmdomain (rc=${rc})"; return 1; }
        ok "nmdomain created at ${nmhome}"
    fi

    # 2. Point Node Manager at all interfaces on our port/type. nodemanager.properties
    #    is plain key=value, so set_conf_prop updates it in place (appends if absent).
    local nmprops="${nmhome}/nodemanager/nodemanager.properties"
    mkdir -p "$(dirname "$nmprops")"
    [ -f "$nmprops" ] || : > "$nmprops"
    set_conf_prop "$nmprops" ListenAddress  "$bind"
    set_conf_prop "$nmprops" ListenPort     "$port"
    set_conf_prop "$nmprops" SecureListener "$secure"
    # Use pure-Java process control: OCCAS ships no native Node Manager library
    # for every platform (e.g. aarch64), and the native one fails with
    # UnsatisfiedLinkError. Java-based control is portable and sufficient here.
    set_conf_prop "$nmprops" NativeVersionEnabled false
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
    JAVA_HOME="${JAVA_HOME_VAL:-${JAVA_HOME:-}}" nohup "${nmhome}/bin/startNodeManager.sh" > "$nmlog" 2>&1 &
    local pid=$!
    local i=0
    while [ "$i" -lt 30 ]; do
        if nm_listening "$port"; then ok "Node Manager up (pid ${pid}), listening on ${bind}:${port}."; log "  log: ${nmlog}"; return 0; fi
        kill -0 "$pid" 2>/dev/null || { warn "Node Manager exited early — tail of ${nmlog}:"; tail -n 15 "$nmlog" 2>/dev/null | sed 's/^/    /'; return 1; }
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
find_jdk() {
    local want="$1" h q d jbin
    [ -n "$want" ] || return 0
    # macOS: the system tool already indexes every installed JDK. It returns the
    # DEFAULT jdk when nothing matches (e.g. -v 8, since 8 registers as 1.8), so
    # always verify the returned home's actual major before trusting it.
    if [ -x /usr/libexec/java_home ]; then
        for q in "$want" "1.$want"; do
            h="$(/usr/libexec/java_home -v "$q" 2>/dev/null || true)"
            if [ -n "$h" ] && [ -x "${h}/bin/java" ] && [ "$(jdk_major "${h}/bin/java")" = "$want" ]; then
                printf '%s' "$h"; return 0
            fi
        done
    fi
    # Linux / common layouts: scan the usual JVM dirs, match by reported major.
    for d in /usr/lib/jvm/* /usr/java/* /opt/java/*; do
        [ -d "$d" ] || continue
        jbin="${d}/bin/java"; [ -x "$jbin" ] || continue
        [ "$(jdk_major "$jbin")" = "$want" ] && { printf '%s' "$d"; return 0; }
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

# Download + verify + unpack Oracle's NFTC JDK <major> into /usr/lib/jvm (using
# sudo if that dir isn't writable). On success sets JDK_DL_HOME to the resulting
# JAVA_HOME and returns 0; otherwise warns and returns 1. All chatter goes to
# stderr so callers can run it inline without capturing stdout.
JDK_DL_HOME=""
download_jdk() {
    JDK_DL_HOME=""
    local want="$1"
    jdk_dl_supported "$want" || {
        warn "Oracle only offers no-login downloads for JDK 17+ on Linux x64/aarch64;" >&2
        warn "JDK ${want} on $(uname -s)/$(uname -m) isn't available that way — install it manually." >&2
        return 1
    }
    local arch
    case "$(uname -m)" in x86_64|amd64) arch="x64" ;; aarch64|arm64) arch="aarch64" ;; esac
    local url="https://download.oracle.com/java/${want}/latest/jdk-${want}_linux-${arch}_bin.tar.gz"
    local dest="/usr/lib/jvm"

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
    info "Install user/group: ${user}:${grp}"
    [ "$(uname -s)" = "Linux" ] || { warn "user/group creation is Linux-only (host prep)."; return 0; }
    local SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && SUDO="sudo"

    if [ "$DRY" = "on" ]; then
        getent group "$grp" >/dev/null 2>&1 \
            && log "${C_DIM}  [dry-run] group ${grp} already exists${C_RESET}" \
            || log "${C_DIM}  [dry-run] ${SUDO:+sudo }groupadd ${grp}${C_RESET}"
        id "$user" >/dev/null 2>&1 \
            && log "${C_DIM}  [dry-run] user ${user} already exists${C_RESET}" \
            || log "${C_DIM}  [dry-run] ${SUDO:+sudo }useradd -g ${grp} -m ${user}${C_RESET}"
        return 0
    fi

    # group
    if getent group "$grp" >/dev/null 2>&1; then ok "group '${grp}' already exists."
    elif $SUDO groupadd "$grp"; then ok "created group '${grp}'."
    else warn "could not create group '${grp}' (need root?)."; return 1; fi

    # user (+ ensure membership in the group)
    if id "$user" >/dev/null 2>&1; then
        ok "user '${user}' already exists."
        if id -nG "$user" 2>/dev/null | tr ' ' '\n' | grep -qx "$grp"; then ok "user '${user}' is in '${grp}'."
        elif $SUDO usermod -aG "$grp" "$user"; then ok "added '${user}' to '${grp}'."
        else warn "could not add '${user}' to '${grp}'."; fi
    elif $SUDO useradd -g "$grp" -m "$user"; then ok "created user '${user}' (primary group ${grp})."
    else warn "could not create user '${user}' (need root?)."; return 1; fi
    return 0
}

# ----------------------------------------------------------------------------
# Create the install dirs (MW_HOME + Oracle inventory) and chown them to the
# install user:group. Idempotent; needs root or passwordless sudo. A populated
# MW_HOME is left untouched (we don't recursively chown an existing install).
# ----------------------------------------------------------------------------
do_makedirs() {
    local mw="$MWHOME" inv="${INV_LOC:-/home/oracle/oraInventory}"
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

    if [ -d "${mw}/wlserver" ]; then
        ok "MW_HOME already populated at ${mw} — leaving ownership as-is."
    elif $SUDO mkdir -p "$mw" && $SUDO chown -R "${user}:${grp}" "$mw"; then
        ok "created + chowned ${mw}."
    else
        warn "could not set up ${mw} (need root?)."; return 1
    fi
    if $SUDO mkdir -p "$inv" && $SUDO chown -R "${user}:${grp}" "$inv"; then
        ok "created + chowned ${inv}."
    else
        warn "could not set up ${inv} (need root?)."; return 1
    fi
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

    if [ -d "${mwhome}/wlserver" ]; then
        ok "OCCAS already present at ${mwhome} — skipping install."; return 0
    fi
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

    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] response file:${C_RESET}"; sed 's/^/    /' "$rsp"
        log "${C_DIM}  [dry-run] oraInst.loc:${C_RESET}";   sed 's/^/    /' "$inv"
        log "${C_DIM}  [dry-run] $(java_bin) -jar ${installer} -silent -responseFile <rsp> -invPtrLoc <loc> -ignoreSysPrereqs${C_RESET}"
        rm -f "$rsp" "$inv"; return 0
    fi
    if [ ! -f "$installer" ]; then rm -f "$rsp" "$inv"; warn "installer.jar not found: ${installer}"; return 1; fi
    if "$(java_bin)" -jar "$installer" -silent -responseFile "$rsp" -invPtrLoc "$inv" -ignoreSysPrereqs; then
        rm -f "$rsp" "$inv"; ok "Product installed at ${mwhome}"
    else
        rm -f "$rsp" "$inv"; warn "silent install failed"; return 1
    fi
}

# Emit the WLST that adds the optional static test engine as a configured member
# of BEA_ENGINE_TIER_CLUST (a configured server doesn't inherit the dynamic
# template, so its sip/sips channels are added by hand). Arg: name:mach:listen:sip:sips
emit_static_block() {
    local sname smach sport ssip ssips
    IFS=: read -r sname smach sport ssip ssips <<< "$1"
    [ -n "$sname" ] && [ -n "$smach" ] && [ -n "$sport" ] && [ -n "$ssip" ] && [ -n "$ssips" ] \
        || { warn "bad static.server '$1' (want name:machine:listen:sip:sips)"; return 1; }
    cat <<PYBLOCK
# --- BLADE: static test engine '${sname}' on machine '${smach}' ---
cd('/')
create('${sname}','Server')
cd('/Servers/${sname}')
set('Cluster','BEA_ENGINE_TIER_CLUST')
set('Machine','${smach}')
set('ListenPort',${sport})
create('sip','NetworkAccessPoint')
cd('/Servers/${sname}/NetworkAccessPoints/sip')
set('Protocol','sip')
set('ListenPort',${ssip})
cd('/Servers/${sname}')
create('sips','NetworkAccessPoint')
cd('/Servers/${sname}/NetworkAccessPoints/sips')
set('Protocol','sips')
set('ListenPort',${ssips})
PYBLOCK
}

# Admin password: env > occas.secret > prompt (skipped under dry-run).
get_admin_pw() {
    local v="${BLADE_WLS_PASSWORD:-}"
    [ -z "$v" ] && [ -f "$OCCAS_SECRET" ] && v="$(read_prop "$OCCAS_SECRET" admin.password)"
    if [ -z "$v" ] && [ "$DRY" != "on" ]; then
        read -rs -p "  Admin password for the new domain: " v || v=""; echo
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
    static="$(read_prop "$OCCAS_CONF" static.server)"
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
    [ -n "$static" ] && log "  static test engine: ${static}"

    local tmpl_dir="${mwhome}/occas/common/templates/scripts/wlst"
    local src_py="${tmpl_dir}/occas-replicated-dynamiccluster.py"

    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] stage ${src_py} + generated .properties${C_RESET}"
        log "${C_DIM}  [dry-run] generated .properties (password redacted):${C_RESET}"
        printf '%s\n' "$props" | sed 's/^/    /'
        log "${C_DIM}  [dry-run] sed .py: domainName='${domain}', ServerStartMode='${mode}'${C_RESET}"
        if [ -n "$static" ]; then
            log "${C_DIM}  [dry-run] inject static-engine WLST before writeDomain:${C_RESET}"
            emit_static_block "$static" | sed 's/^/    /'
        fi
        log "${C_DIM}  [dry-run] setWLSEnv + java weblogic.WLST occas-replicated-dynamiccluster.py${C_RESET}"
        return 0
    fi

    # The template writes with OverwriteDomain=true — an existing domain dir of
    # this name is CLOBBERED. Make that an explicit, confirmed choice.
    local domdir="${mwhome}/user_projects/domains/${domain}"
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

    sed "s/^domainName=.*/domainName='${domain}'/; \
         s/setOption('ServerStartMode', '[^']*')/setOption('ServerStartMode', '${mode}')/" \
        "${work}/occas-replicated-dynamiccluster.py" > "${work}/.py.tmp" \
        && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"

    if [ -n "$static" ]; then
        if ! emit_static_block "$static" > "${work}/static.block"; then rm -rf "$work"; return 1; fi
        awk 'NR==FNR { blk = blk $0 ORS; next }
             /OverwriteDomain/ && !ins { printf "%s", blk; ins = 1 }
             { print }' \
            "${work}/static.block" "${work}/occas-replicated-dynamiccluster.py" \
            > "${work}/.py.tmp" && mv "${work}/.py.tmp" "${work}/occas-replicated-dynamiccluster.py"
    fi

    local jh rc=0; jh="$(read_prop "$OCCAS_CONF" java.home)"
    (
        cd "$work"
        if [ -n "$jh" ] && [ -d "$jh" ]; then export JAVA_HOME="$jh"; PATH="${jh}/bin:$PATH"; fi
        export MW_HOME="$mwhome" BEA_HOME="$mwhome"   # Oracle env + the .py's domain dir
        # Oracle's setWLSEnv.sh/commEnv.sh reference unbound vars — they predate
        # 'set -u', so disable nounset before sourcing them (this is a subshell).
        set +u
        # shellcheck disable=SC1090
        . "$setwls" >/dev/null
        java weblogic.WLST occas-replicated-dynamiccluster.py
    ) || rc=$?
    rm -rf "$work"
    [ "$rc" -eq 0 ] || { warn "configure failed (WLST rc=${rc})"; return 1; }
    ok "Domain '${domain}' written under ${mwhome}/user_projects/domains/"
    # Give the domain's servers enough heap/metaspace (the admin EAR OOMs the
    # OCCAS dev default) via setUserOverrides.sh, which the NM start path sources.
    write_user_overrides "${mwhome}/user_projects/domains/${domain}"
    # Enroll the new app domain into the standalone Node Manager so it can start
    # the AdminServer/engines. No-op-with-hint if the NM domain isn't built yet.
    register_domain_with_nm "$domain" "${mwhome}/user_projects/domains/${domain}" || true
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
    PF_NEED=""

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
        1) warn "JDK: ${jdesc} — the installer needs a full JDK."; PF_NEED="yes" ;;
        *) warn "JDK: ${jdesc} — the installer needs one."; PF_NEED="yes" ;;
    esac
    # Validate the JDK major against the OCCAS release (version from the conf, or
    # detected from a real install). This is the runtime JDK, not the build JDK.
    cfgver="$(read_prop "$OCCAS_CONF" occas.version)"
    [ -z "$cfgver" ] && cfgver="$(detect_occas_version "$mwhome")"
    want="$(occas_jdk_major "$cfgver")"
    jmajor=""; [ -n "$jhome" ] && [ -x "${jhome}/bin/java" ] && jmajor="$(jdk_major "${jhome}/bin/java")"
    if [ -n "$want" ] && [ -n "$jmajor" ]; then
        if [ "$jmajor" = "$want" ]; then ok "JDK ${jmajor} matches OCCAS ${cfgver}."
        else warn "JDK is ${jmajor} but OCCAS ${cfgver} wants JDK ${want}."; PF_NEED="yes"; fi
    else
        log "  ${C_DIM}match the JDK to the OCCAS release per Oracle's certification matrix.${C_RESET}"
    fi

    # JDK missing or the wrong major? At this point PF_NEED reflects only the JDK
    # checks above (the host checks run below), so a set PF_NEED means the JDK is
    # the problem. If we can fetch the one OCCAS wants, offer it here too — same
    # path as the wizard — and write it back into the profile's java.home.
    if [ -n "$PF_NEED" ] && jdk_dl_supported "$want" \
       && yesno "Download JDK ${want} from Oracle into /usr/lib/jvm and set it as this profile's java.home?" "Y"; then
        if download_jdk "$want"; then
            set_conf_prop "$OCCAS_CONF" java.home "$JDK_DL_HOME"
            ok "java.home set to ${JDK_DL_HOME} in ${OCCAS_CONF#${SCRIPT_DIR}/}"
            PF_NEED=""   # the JDK prerequisite is now satisfied
        fi
    fi

    if [ "$os" = "Darwin" ]; then
        warn "macOS — skipping user/group/dir checks (host prep is for the Linux install target)."
    else
        if getent group "$inv_grp" >/dev/null 2>&1; then ok "group '${inv_grp}' exists"
        else warn "group '${inv_grp}' missing"; PF_NEED="yes"; fi
        if id -nG 2>/dev/null | tr ' ' '\n' | grep -qx "$inv_grp"; then ok "user '$(id -un)' is in '${inv_grp}'"
        else warn "user '$(id -un)' not in '${inv_grp}' — install as a user that is, or add it"; PF_NEED="yes"; fi
        # MW_HOME: present means already installed; else parent must be writable.
        if [ -d "${mwhome}/wlserver" ]; then ok "OCCAS already installed at ${mwhome}"
        elif [ -d "$mwhome" ] && [ -w "$mwhome" ]; then ok "MW_HOME exists and is writable: ${mwhome}"
        elif [ -d "$(dirname "$mwhome")" ] && [ -w "$(dirname "$mwhome")" ]; then ok "MW_HOME parent writable (dir will be created)"
        else warn "MW_HOME not writable: ${mwhome}"; PF_NEED="yes"; fi
        if [ -d "$inv_loc" ] && [ -w "$inv_loc" ]; then ok "inventory dir writable: ${inv_loc}"
        elif [ -d "$(dirname "$inv_loc")" ] && [ -w "$(dirname "$inv_loc")" ]; then ok "inventory parent writable (dir will be created)"
        else warn "inventory location not writable: ${inv_loc}"; PF_NEED="yes"; fi
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
                         || { warn "WLS basic template missing: ${nmtmpl} — 'n' (create NM domain) needs it."; PF_NEED="yes"; }
    fi
    if nm_listening "$nmport"; then ok "Node Manager already listening on :${nmport}."
    else log "  ${C_DIM}Node Manager port :${nmport} is free (it'll start with the 'n' step).${C_RESET}"; fi

    local pf_user; pf_user="$(read_prop "$OCCAS_CONF" install.user)"; pf_user="${pf_user:-oracle}"
    log ""
    if [ -n "$PF_NEED" ]; then
        warn "Prerequisites missing. Fix them from STEP 1 (these use sudo for you):"
        log  "    'u'  Create install user & group   (${pf_user}:${inv_grp})"
        log  "    'm'  Create install dirs & chown   (${mwhome} + ${inv_loc})"
        log  "  ${C_DIM}…or as root: groupadd ${inv_grp}; useradd -g ${inv_grp} -m ${pf_user}; mkdir -p; chown -R.${C_RESET}"
        log  "  Then re-run Preflight ('p')."
    elif [ "$os" != "Darwin" ]; then
        ok "Preflight looks good — ready for step 1 (install)."
    fi
}

# Register an app domain with the standalone Node Manager (nmdomain) so that
# nmConnect/nmStart can find it. Idempotent — updates nodemanager.domains, which
# Node Manager reads at (re)start. Falls back to the conf when globals are unset.
register_domain_with_nm() {
    local domname="$1" domhome="$2"
    local mw="${MWHOME:-$(read_prop "$OCCAS_CONF" oracle.home)}"
    local nmdom="${NM_DOMAIN:-$(read_prop "$OCCAS_CONF" nm.domain.name)}"
    [ -n "$mw" ] && [ -n "$nmdom" ] || { warn "cannot register: missing oracle.home / nm.domain.name"; return 1; }
    local nmfile="${mw}/user_projects/domains/${nmdom}/nodemanager/nodemanager.domains"
    if [ ! -d "$(dirname "$nmfile")" ]; then
        warn "Node Manager domain '${nmdom}' not set up yet — run the 'n' step first."
        return 1
    fi
    set_conf_prop "$nmfile" "$domname" "$domhome"
    ok "enrolled ${domname} → ${domhome} in ${nmdom}'s nodemanager.domains"
    if nm_listening "${NM_PORT:-$(read_prop "$OCCAS_CONF" nm.listen.port)}"; then
        warn "Node Manager is running — run 'n' to restart it so it picks up this enrollment, then 's'."
    fi
    return 0
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
    mem="$(read_prop "$OCCAS_CONF" server.mem.args)"
    mem="${mem:--Xms512m -Xmx1024m -XX:MaxMetaspaceSize=512m}"
    cat > "${domhome}/bin/setUserOverrides.sh" <<EOF
# BLADE - generated by blade.sh. Node Manager's start script sources
# setDomainEnv.sh, which sources this; USER_MEM_ARGS overrides the OCCAS dev
# default (-Xmx512m -XX:MaxMetaspaceSize=256m) that OOMs on Metaspace when the
# admin EAR deploys. Applies to every server; change server.mem.args in
# occas.conf and re-run configure (or 's') to update. To split AdminServer vs
# engines, branch on \$SERVER_NAME here.
USER_MEM_ARGS="${mem}"
export USER_MEM_ARGS
EOF
    chmod +x "${domhome}/bin/setUserOverrides.sh"
    log "  ${C_DIM}wrote setUserOverrides.sh — server memory: ${mem}${C_RESET}"
}

# Start or stop the AdminServer via Node Manager. action = start | kill.
nm_admin() {
    local action="$1" oh="$2" dom="$3" auser="$4"
    local domhome="${oh}/user_projects/domains/${dom}"
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
    # Starting needs the domain enrolled (no-op if already) + adequate launch memory.
    [ "$action" = "start" ] && { register_domain_with_nm "$dom" "$domhome" || true; write_user_overrides "$domhome"; }
    # NM credentials = the admin creds (env > occas.secret).
    local pw="${BLADE_WLS_PASSWORD:-}"
    [ -z "$pw" ] && [ -f "$OCCAS_SECRET" ] && pw="$(read_prop "$OCCAS_SECRET" admin.password)"
    info "${verb} AdminServer for '${dom}' via Node Manager localhost:${nmport} (${nmtype})"
    MW_HOME="$oh" DOMAIN_NAME="$dom" DOMAIN_HOME="$domhome" ADMIN_SERVER="AdminServer" NM_ACTION="$action" \
        NM_HOST="localhost" NM_PORT="$nmport" NM_USER="$auser" NM_TYPE="$nmtype" NM_PASSWORD="$pw" \
        bash "${SCRIPT_DIR}/misc/start-admin-nm.sh" || warn "start-admin-nm returned an error"
}
start_admin() { nm_admin start "$@"; }

# Stop the AdminServer (+ any of the domain's servers). nmKill is unreliable with
# pure-Java Node Manager (NativeVersionEnabled=false, required on aarch64) when a
# server is script-launched with a child JVM, so we stop at the OS level.
stop_admin() {
    local oh="$1" dom="$2"
    local domhome="${oh}/user_projects/domains/${dom}"
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
    for p in $(pgrep -f weblogic.Name 2>/dev/null || true); do
        cmd="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)"
        case "$cmd" in *"$home"*) pids="${pids} ${p}" ;; esac
    done
    [ -n "$pids" ] || { ok "no running servers for $(basename "$home")."; return 0; }
    for p in $pids; do kill "$p" 2>/dev/null && n=$((n + 1)); done
    ok "signaled ${n} server process(es) for $(basename "$home") — waiting for exit…"
    while [ "$i" -lt 20 ]; do
        local alive=0; for p in $pids; do kill -0 "$p" 2>/dev/null && alive=1; done
        [ "$alive" = 0 ] && { ok "servers stopped."; return 0; }
        sleep 1; i=$((i + 1))
    done
    warn "servers still up after ${i}s — SIGKILL."
    for p in $pids; do kill -9 "$p" 2>/dev/null || true; done
    return 0
}

# Reset: stop the app domain's servers, un-enroll it from NM, and delete it — so
# the next configure starts clean. The stable nmdomain is left running.
do_remove_domain() {
    local oh="$1" dom="$2" auser="${3:-weblogic}"
    local domhome="${oh}/user_projects/domains/${dom}"
    [ -n "$dom" ] || { warn "no domain name."; return 1; }
    [ -d "$domhome" ] || { ok "domain '${dom}' not present — nothing to remove."; return 0; }
    if [ "$DRY" = "on" ]; then
        log "${C_DIM}  [dry-run] stop AdminServer; kill stray JVMs; un-enroll ${dom}; rm -rf ${domhome}${C_RESET}"
        return 0
    fi
    yesno "Remove domain '${dom}' at ${domhome}? Stops its servers and DELETES it." "N" \
        || { warn "kept '${dom}' — nothing removed."; return 1; }
    stop_admin "$oh" "$dom" "$auser" || true
    kill_domain_procs "$domhome"
    local nmdom="${NM_DOMAIN:-$(read_prop "$OCCAS_CONF" nm.domain.name)}"
    local nmfile="${oh}/user_projects/domains/${nmdom}/nodemanager/nodemanager.domains"
    if [ -f "$nmfile" ] && grep -q "^${dom}=" "$nmfile"; then
        local tmp; tmp="$(mktemp)" && grep -v "^${dom}=" "$nmfile" > "$tmp" && mv "$tmp" "$nmfile" \
            && ok "un-enrolled '${dom}' from ${nmdom} (restart NM with 'k' then 'n' to apply)."
    fi
    rm -rf "$domhome" && ok "removed ${domhome}."
}

# ============================================================================
# Deploy — push the built artifacts to their WebLogic targets via WLST
# (misc/deploy-wls.sh; needs only the OCCAS install, no Maven). Maven's only job
# was building/bundling the framework into the WARs. Reads the newest
# dist/<ver>-<build>/ (override with $BLADE_DIST).
#   shared    blade-shared.war  (library) -> AdminServer + cluster
#   admin     blade-admin.ear              -> AdminServer
#   services  blade-services.ear           -> BEA_ENGINE_TIER_CLUST
#   test      blade-test.ear               -> the static engine (engine0)
# ============================================================================
_dist_dir() {
    if [ -n "${BLADE_DIST:-}" ]; then printf '%s' "${BLADE_DIST%/}"; return; fi
    local d; d="$(ls -1dt "${SCRIPT_DIR}/dist"/*/ 2>/dev/null | head -1)"
    printf '%s' "${d%/}"
}

# Authoritative AdminServer t3 URL from the live domain config (the server often
# binds the host IP, not localhost). Falls back to deploy.conf, then localhost.
_wls_adminurl() {
    local cfg="${MWHOME}/user_projects/domains/${DOMAIN}/config/config.xml" addr="" port="" blk
    if [ -f "$cfg" ]; then
        blk="$(awk '/<server>/{b=""} {b=b"\n"$0} /<\/server>/{ if (b ~ /<name>AdminServer<\/name>/){print b; exit} }' "$cfg")"
        addr="$(printf '%s' "$blk" | grep -om1 '<listen-address>[^<]*' | sed 's/.*>//')"
        port="$(printf '%s' "$blk" | grep -om1 '<listen-port>[0-9]*'  | sed 's/.*>//')"
    fi
    [ -n "$addr" ] || addr="$(read_prop "$DEPLOY_CONF" wls.adminurl | sed -E 's#^[a-z0-9]+://([^:/]+).*#\1#')"
    # NEVER localhost — the AdminServer is reached over the network, not the
    # loopback. If config/conf gave nothing or a loopback, use the host's own
    # routable name/IP.
    case "$addr" in ""|localhost|127.*|::1) addr="$(hostname -f 2>/dev/null || hostname)" ;; esac
    [ -n "$addr" ] || addr="$(hostname)"
    [ -n "$port" ] || port="7001"
    printf 't3://%s:%s' "$addr" "$port"
}

# The static engine / test server name (static.server field 1).
_test_target() { local s; s="$(read_prop "$OCCAS_CONF" static.server | cut -d: -f1)"; printf '%s' "${s:-engine0}"; }

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
    MW_HOME="$MWHOME" JAVA_HOME="${JAVA_HOME_VAL:-${JAVA_HOME:-}}" \
        WLS_ADMINURL="$url" WLS_USER="$auser" WLS_PASSWORD="$pw" \
        WLS_ACTION="$action" WLS_NAME="$name" WLS_SOURCE="$source" WLS_TARGETS="$targets" WLS_LIBRARY="$library" \
        bash "${SCRIPT_DIR}/misc/deploy-wls.sh" || { warn "deploy ${action} ${name} failed"; return 1; }
}

# Deploy/undeploy a single tier. tier = shared|admin|services|test.
do_deploy_tier() {
    local tier="$1" action="${2:-deploy}" dist; dist="$(_dist_dir)"
    [ -n "$dist" ] && [ -d "$dist" ] || { warn "no dist/ build found — run ./build.sh first."; return 1; }
    case "$tier" in
        shared)   _deploy_one "$action" blade-shared   "${dist}/blade-shared.war"  "AdminServer,BEA_ENGINE_TIER_CLUST" true ;;
        admin)    _deploy_one "$action" blade-admin    "${dist}/blade-admin.ear"    "AdminServer" ;;
        services) _deploy_one "$action" blade-services "${dist}/blade-services.ear" "BEA_ENGINE_TIER_CLUST" ;;
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
    # 'wizard' runs the full linear interview first; 'preflight' runs host checks
    # first; either way you land in the dashboard to iterate on individual phases.
    case "$JUMP" in
        wizard)    run_wizard ;;
        preflight) [ -f "$OCCAS_CONF" ] || die "no profile '${NAME}' yet — create it first: ./blade.sh ${NAME}"; do_preflight ;;
    esac
    dashboard
fi
