#!/usr/bin/env bash
# ============================================================================
# install.sh - Guided installer/configurator for BLADE (OCCAS + BLADE).
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
# This script does everything EXCEPT build and deploy:
#   - create/edit a profile (the interview)
#   - install OCCAS + create the dynamic-cluster domain (install-occas.sh)
#   - start the AdminServer via Node Manager (misc/start-admin-nm.sh)
#   - set up TLS  (tls/make-certs.sh, tls/install-ssl.sh)
# Build with ./build.sh and deploy with ./deploy.sh <profile> afterwards.
#
# Usage:
#   ./install.sh                 pick a profile (or create one), then a menu
#   ./install.sh <name>          create/edit profile <name>, then a menu
#   ./install.sh <name> wizard      jump straight into the interview
#   ./install.sh <name> preflight   run host-prerequisite checks, then the menu
#   ./install.sh -h
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
# The interview
# ============================================================================
run_wizard() {
    local editing="no"
    [ -n "$NAME" ] && [ -f "$OCCAS_CONF" ] && editing="yes"

    log ""
    log "${C_BOLD}BLADE installer — new profile${C_RESET}"
    [ "$editing" = "yes" ] && log "  editing existing profile '${NAME}' (its values are offered as defaults)"
    log ""

    # ----- What I found (environment scan, before any question) ---------------
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

    # ----- 1. WebLogic domain name (the centerpiece question) -----------------
    log "${C_BOLD}1) Choose WebLogic domain...${C_RESET}"
    help <<'EOF'
A WebLogic "domain" is a unique name for this deployment. It is NOT a DNS or
internet domain. It is simply the name of the directory in which all files go.
EOF
    local DOMAIN
    while :; do
        ask DOMAIN "Domain" "$(d domain.name "")"
        case "$DOMAIN" in
            "")        warn "a domain name is required." ;;
            *[\ ]*)    warn "no spaces in a domain name." ;;
            *.*)       warn "that has a dot — a WebLogic domain is not a DNS name. Use it only if you're sure."; break ;;
            *)         break ;;
        esac
    done
    # New domains come up in 'dev' mode (no boot.properties prompt, simpler
    # first run); the Tuning app switches to production for performance/security.
    local START_MODE ADMIN_USER
    START_MODE="$(d server.start.mode dev)"
    ask ADMIN_USER "Admin username" "$(d admin.username weblogic)"

    # ----- 2. Where OCCAS lives ----------------------------------------------
    log ""
    log "${C_BOLD}2) OCCAS version & install location${C_RESET}"
    help <<'EOF'
OCCAS is versioned and customers upgrade over time, so the version stamps the
install path — versions sit side by side and sync-occas.sh flips a "current"
symlink between them on upgrade.

MW_HOME is the middleware home, the variable OCCAS/WebLogic actually use
(WL_HOME is MW_HOME/wlserver). If $MW_HOME is set in your shell it is the
default below.
EOF
    local OCCAS_VERSION MWHOME INSTALLER_JAR INV_LOC INV_GRP INSTALL_TYPE
    local _envmw="${MW_HOME:-}"
    while :; do
        ask MWHOME "MW_HOME (install location)" "$(d oracle.home "${_envmw}")"
        [ -n "$MWHOME" ] && break
        warn "MW_HOME is required — the directory OCCAS is (or will be) installed in."
    done

    # Branch on whether OCCAS is REALLY installed at MW_HOME. If so, read the
    # version and skip every install-only question (and the install step). If
    # not, it's a fresh install — gather the install inputs. Never assume.
    if occas_installed "$MWHOME"; then
        OCCAS_VERSION="$(detect_occas_version "$MWHOME")"
        if [ -n "$OCCAS_VERSION" ]; then
            ok "OCCAS ${OCCAS_VERSION} already installed at ${MWHOME} — the install step will be skipped."
        else
            warn "OCCAS is installed at ${MWHOME} but its version is unreadable."
            while :; do ask OCCAS_VERSION "OCCAS version" ""; [ -n "$OCCAS_VERSION" ] && break; warn "OCCAS version is required."; done
        fi
        # Install-only keys are unused once installed — keep quiet/prior defaults
        # so the written occas.conf keeps a stable shape.
        INSTALLER_JAR="$(d installer.jar "")"
        INV_LOC="$(d inventory.loc /home/oracle/oraInventory)"
        INV_GRP="$(d inventory.group oinstall)"
        INSTALL_TYPE="$(d install.type 'Complete with Examples')"
    else
        log "  ${C_DIM}no OCCAS at ${MWHOME} — configuring a fresh install.${C_RESET}"
        local _ver; _ver="$(basename "$MWHOME" | grep -oE '[0-9]+\.[0-9]+' | head -1)"
        while :; do
            ask OCCAS_VERSION "OCCAS version" "$_ver"
            [ -n "$OCCAS_VERSION" ] && break
            warn "OCCAS version is required (e.g. 8.1, 8.3)."
        done
        ask INSTALLER_JAR "Installer jar (occas_generic.jar) — needed only where you run 'install'" \
                          "$(d installer.jar "/home/oracle/install/occas-${OCCAS_VERSION}/occas_generic.jar")"
        ask INV_LOC      "Oracle inventory location" "$(d inventory.loc /home/oracle/oraInventory)"
        ask INV_GRP      "Inventory group"           "$(d inventory.group oinstall)"
        ask INSTALL_TYPE "Install type"              "$(d install.type 'Complete with Examples')"
    fi
    # JDK for the INSTALLER + servers — version-locked to the OCCAS release
    # (8.x -> 8/11/17/21). We recommend the right major and find it for you.
    # This is NOT the build JDK: ./build.sh wants 23+ (it emits Java 11 bytecode).
    log ""
    local JAVA_HOME_VAL _want _found
    _want="$(occas_jdk_major "$OCCAS_VERSION")"
    _found="$(find_jdk "$_want")"
    if [ -n "$_want" ]; then
        if [ -n "$_found" ]; then ok "OCCAS ${OCCAS_VERSION} runs on JDK ${_want} — found one at ${_found}"
        else warn "OCCAS ${OCCAS_VERSION} runs on JDK ${_want} — none found here; install it or point me at one."; fi
    else
        log "  ${C_DIM}no JDK recommendation on file for OCCAS ${OCCAS_VERSION} — see Oracle's certification matrix.${C_RESET}"
    fi
    log "  ${C_DIM}(the build JDK is separate — ./build.sh wants 23+.)${C_RESET}"
    local _jdef; _jdef="$(d java.home "${_found:-${JAVA_HOME:-}}")"
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
    local OCCAS_BASE OCCAS_CURRENT KEYSTORE_DIR APPROUTER_DIR
    OCCAS_BASE="$(dirname "$MWHOME")"
    OCCAS_CURRENT="${OCCAS_BASE}/current"
    KEYSTORE_DIR="${MWHOME}/security"
    APPROUTER_DIR="${MWHOME}/user_projects/domains/${DOMAIN}/approuter"

    # ----- 3. Hosts, DNS & IP addresses --------------------------------------
    log ""
    log "${C_BOLD}3) Hosts, DNS & IP addresses${C_RESET}"
    help <<'EOF'
NOW the network part. Each physical/virtual host runs a Node Manager. One host
is the AdminServer (console + a static test engine); the others run the SIP
engine servers of the dynamic cluster.

Addresses can be IPs or hostnames. For a local Mac dry-run, accept 127.0.0.1
everywhere — you'll put the real OCI addresses in when you copy this profile up.
EOF
    # arrays: index 0 = admin host, 1..N = engine hosts
    local H_NAME=() H_ADDR=() H_PORT=() H_TYPE=() H_PUB=() H_FQDN=() H_ROLE=()
    local prefix match
    ask prefix "SIP engine server name prefix" "$(d server.name.prefix engine)"
    match="$(d machine.match.expression "${prefix}*")"

    log ""
    log "  ${C_BOLD}AdminServer host${C_RESET}"
    local aname aaddr aport atype apub afqdn
    ask aname "  host name (machine name, NOT a server)" "$(d machine.1.name admin)"
    # machine.1 stored as name:addr:port:type — recover pieces for edit defaults
    local m1; m1="$(exget machine.1)"
    local m1addr m1port m1type
    IFS=: read -r _ m1addr m1port m1type <<< "${m1:-:::}"
    ask aaddr "  Node Manager listen address (IP or host)" "${m1addr:-127.0.0.1}"
    ask aport "  Node Manager listen port"                 "${m1port:-5556}"
    ask atype "  Node Manager type (ssl|plain)"            "${m1type:-ssl}"
    ask apub  "  public IP (for the cert SAN; Enter to skip)" ""
    ask afqdn "  fully-qualified DNS name (for SAN; Enter to skip)" ""
    H_NAME+=("$aname"); H_ADDR+=("$aaddr"); H_PORT+=("$aport"); H_TYPE+=("$atype")
    H_PUB+=("$apub");   H_FQDN+=("$afqdn"); H_ROLE+=("admin")

    local neng
    log ""
    ask neng "Number of SIP engine hosts" "$(d _engine_count 2)"
    case "$neng" in ''|*[!0-9]*) neng=0 ;; esac
    local i ename eaddr eport etype epub efqdn mk maddr mport mtype
    i=1
    while [ "$i" -le "$neng" ]; do
        log ""
        log "  ${C_BOLD}Engine host ${i}${C_RESET}"
        mk="$(exget "machine.$((i+1))")"
        IFS=: read -r _ maddr mport mtype <<< "${mk:-:::}"
        ask ename "  host name" "${prefix}${i}"
        ask eaddr "  Node Manager listen address (IP or host)" "${maddr:-127.0.0.1}"
        ask eport "  Node Manager listen port"                 "${mport:-5556}"
        ask etype "  Node Manager type (ssl|plain)"            "${mtype:-ssl}"
        ask epub  "  public IP (for SAN; Enter to skip)" ""
        ask efqdn "  fully-qualified DNS name (for SAN; Enter to skip)" ""
        H_NAME+=("$ename"); H_ADDR+=("$eaddr"); H_PORT+=("$eport"); H_TYPE+=("$etype")
        H_PUB+=("$epub");   H_FQDN+=("$efqdn"); H_ROLE+=("engine")
        i=$((i + 1))
    done

    # engine.nodes = the engine-role host names
    local ENGINE_NODES="" idx=0
    while [ "$idx" -lt "${#H_NAME[@]}" ]; do
        [ "${H_ROLE[$idx]}" = "engine" ] && ENGINE_NODES="${ENGINE_NODES:+${ENGINE_NODES},}${H_NAME[$idx]}"
        idx=$((idx + 1))
    done

    # ----- 4. Dynamic cluster shape ------------------------------------------
    log ""
    log "${C_BOLD}4) Dynamic cluster shape${C_RESET}"
    help <<'EOF'
The engine cluster (BEA_ENGINE_TIER_CLUST) generates its servers from a count
rather than a hand-written list, so adding capacity later is a number bump.
EOF
    local DCOUNT DMAX defcount defmax
    defcount="$neng"; [ "$defcount" -lt 1 ] && defcount=1
    defmax=8; [ "$defcount" -gt "$defmax" ] && defmax=$((defcount * 2))
    ask DCOUNT "Dynamic server count (now)"        "$(d dynamic.server.count "$defcount")"
    ask DMAX   "Max dynamic cluster size (ceiling)" "$(d max.dynamic.cluster.size "$defmax")"

    # ----- 5. Static test engine on the admin box ----------------------------
    log ""
    log "${C_BOLD}5) Static test engine${C_RESET}"
    help <<'EOF'
A fixed engine-tier member on the AdminServer box, for testing and BLADE config
file generation. It is never SBC-targeted. Recommended.
EOF
    local STATIC=""
    if yesno "Add a static test engine on '${H_NAME[0]}'?" "Y"; then
        local sname slisten ssip ssips
        # recover edit defaults from existing static.server (name:machine:listen:sip:sips)
        local sv; sv="$(exget static.server)"; local sv_name sv_listen sv_sip sv_sips
        IFS=: read -r sv_name _ sv_listen sv_sip sv_sips <<< "${sv:-::::}"
        ask sname   "  test engine server name" "${sv_name:-${prefix}0}"
        ask slisten "  HTTP listen port"        "${sv_listen:-8001}"
        ask ssip    "  SIP (udp/tcp) port"      "${sv_sip:-5060}"
        ask ssips   "  SIPS (tls) port"         "${sv_sips:-5061}"
        STATIC="${sname}:${H_NAME[0]}:${slisten}:${ssip}:${ssips}"
    fi

    # ----- 6. Runtime / deploy facts -----------------------------------------
    log ""
    log "${C_BOLD}6) Runtime${C_RESET}"
    local SHARED_FS BUILD_PROFILE SSH_USER ADMINURL
    if yesno "Shared filesystem across nodes (install/domain artifacts copy once)?" "Y"; then SHARED_FS=true; else SHARED_FS=false; fi
    ask BUILD_PROFILE "Build profile to deploy (production|minimal|full)" "$(d build.profile production)"
    ask SSH_USER      "SSH user for pushing to engine nodes"              "$(d ssh.user oracle)"
    ask ADMINURL      "WebLogic admin URL (deploy runs ON the AdminServer)" "$(d wls.adminurl t3://localhost:7001)"

    # ----- 7. TLS -------------------------------------------------------------
    log ""
    log "${C_BOLD}7) TLS (HTTPS + SIP TLS)${C_RESET}"
    help <<'EOF'
Optional now — you can run the TLS steps later. If you set it up, the cert's
SAN list is built from every host name / FQDN / IP you entered above so one
identity cert satisfies hostname verification however a client connects.
EOF
    local SSL_PORT SIP_TLS SIP_PORT SIP_VER SIP_TWOWAY CA_CN ID_CN
    local P_CA="" P_KS="" P_TR=""
    SSL_PORT="$(d tls.ssl.port 7002)"
    SIP_PORT="$(d sip.tls.port 5061)"
    SIP_VER="$(d sip.tls.versions TLSv1.2)"
    SIP_TLS="$(d sip.tls.enabled true)"
    SIP_TWOWAY="$(d sip.tls.twoway false)"
    CA_CN="$(d tls.ca.cn 'BLADE Internal CA')"
    # identity CN defaults to the admin host's FQDN, else its name
    ID_CN="$(d tls.identity.cn "${H_FQDN[0]:-${H_NAME[0]}}")"
    if yesno "Set up TLS settings now?" "Y"; then
        ask SSL_PORT "  HTTPS / t3s SSL port" "$SSL_PORT"
        if yesno "  Enable SIP TLS (sips channel on the engines)?" "Y"; then SIP_TLS=true; else SIP_TLS=false; fi
        if [ "$SIP_TLS" = "true" ]; then
            ask SIP_PORT "  SIPS port"               "$SIP_PORT"
            ask SIP_VER  "  Enabled TLS versions"    "$SIP_VER"
            if yesno "  Mutual TLS to the SBC (two-way)?" "N"; then SIP_TWOWAY=true; else SIP_TWOWAY=false; fi
        fi
        ask CA_CN "  Internal CA common name"       "$CA_CN"
        ask ID_CN "  Identity cert common name"     "$ID_CN"
        # passphrases: generate by default, no burden on the user
        P_CA="$(gen_pass)"; P_KS="$(gen_pass)"; P_TR="$(gen_pass)"
        ok "generated 3 random TLS keystore passphrases (saved to deploy.secret)"
    fi

    # Build the SAN list from all host facts.
    local SAN="" seen=" " tok host
    add_san() { case "$seen" in *" $1 "*) : ;; *) SAN="${SAN:+${SAN},}$1"; seen="${seen}$1 " ;; esac; }
    idx=0
    while [ "$idx" -lt "${#H_NAME[@]}" ]; do
        add_san "dns:${H_NAME[$idx]}"
        [ -n "${H_FQDN[$idx]}" ] && add_san "dns:${H_FQDN[$idx]}"
        if is_ip "${H_ADDR[$idx]}"; then add_san "ip:${H_ADDR[$idx]}"; else add_san "dns:${H_ADDR[$idx]}"; fi
        [ -n "${H_PUB[$idx]}" ] && add_san "ip:${H_PUB[$idx]}"
        idx=$((idx + 1))
    done
    add_san "dns:localhost"; add_san "ip:127.0.0.1"

    # ----- 8. Admin password (fills both secrets) ----------------------------
    log ""
    log "${C_BOLD}8) Admin password${C_RESET}"
    help <<'EOF'
The password for the admin user above. It is set into the new domain by the
configure step and reused by deploy.sh to connect. Stored only in the
gitignored secret files (mode 600). Enter to skip and set it later.
EOF
    local ADMIN_PW
    ask_secret ADMIN_PW "Password for '${ADMIN_USER}'"

    # ----- 9. Save ------------------------------------------------------------
    log ""
    log "${C_BOLD}9) Save profile${C_RESET}"
    if [ -z "$NAME" ]; then ask NAME "Save profile as" "$DOMAIN"; set_paths; fi
    [ -n "$NAME" ] || die "a profile name is required."
    if [ -f "$OCCAS_CONF" ] && [ "$editing" != "yes" ]; then
        yesno "Profile '${NAME}' exists — overwrite?" "N" || die "aborted — nothing written."
    fi

    ensure_gitignore
    mkdir -p "$PROFILE_DIR"
    local stamp; stamp="$(date '+%Y-%m-%d %H:%M')"

    # --- occas.conf ---
    {
        echo "# BLADE — OCCAS silent install + dynamic-cluster domain. Profile '${NAME}'."
        echo "# Generated by install.sh on ${stamp}. Re-run: ./install.sh ${NAME}"
        echo "# Consumed by ./install-occas.sh. Admin password lives in occas.secret."
        echo ""
        echo "# --- Step 1: silent product install (runs once; MW_HOME may be shared) ---"
        echo "oracle.home=${MWHOME}"
        echo "occas.version=${OCCAS_VERSION}"
        echo "installer.jar=${INSTALLER_JAR}"
        echo "inventory.loc=${INV_LOC}"
        echo "inventory.group=${INV_GRP}"
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
        echo ""
        echo "# --- Dynamic cluster shape (BEA_ENGINE_TIER_CLUST) ---"
        echo "server.name.prefix=${prefix}"
        echo "machine.match.expression=${match}"
        echo "dynamic.server.count=${DCOUNT}"
        echo "max.dynamic.cluster.size=${DMAX}"
        echo ""
        echo "# --- Physical machines + Node Managers (name:nmAddr:nmPort:nmType) ---"
        idx=0
        while [ "$idx" -lt "${#H_NAME[@]}" ]; do
            echo "machine.$((idx+1))=${H_NAME[$idx]}:${H_ADDR[$idx]}:${H_PORT[$idx]}:${H_TYPE[$idx]}"
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
        echo "# BLADE — deploy + TLS profile '${NAME}'. Generated by install.sh on ${stamp}."
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

    ok "wrote ${OCCAS_CONF#${SCRIPT_DIR}/}"
    ok "wrote ${DEPLOY_CONF#${SCRIPT_DIR}/}"

    # --- secrets ---
    if ! git -C "$SCRIPT_DIR" check-ignore -q "$DEPLOY_SECRET" 2>/dev/null; then
        warn "${PROFILE_DIR#${SCRIPT_DIR}/} is not gitignored — NOT writing secrets. Fix .gitignore, re-run."
    else
        if [ -n "$ADMIN_PW" ]; then
            { echo "# OCCAS install secret — profile '${NAME}' (gitignored). chmod 600."; \
              echo "admin.password=${ADMIN_PW}"; } > "$OCCAS_SECRET"
            chmod 600 "$OCCAS_SECRET"; ok "wrote ${OCCAS_SECRET#${SCRIPT_DIR}/} (600)"
        fi
        if [ -n "$ADMIN_PW" ] || [ -n "$P_CA" ]; then
            {
                echo "# BLADE deploy/TLS secrets — profile '${NAME}' (gitignored). chmod 600."
                echo "wls.password=${ADMIN_PW:-REPLACE_ME}"
                if [ -n "$P_CA" ]; then
                    echo "tls.ca.passphrase=${P_CA}"
                    echo "tls.keystore.passphrase=${P_KS}"
                    echo "tls.trust.passphrase=${P_TR}"
                fi
            } > "$DEPLOY_SECRET"
            chmod 600 "$DEPLOY_SECRET"; ok "wrote ${DEPLOY_SECRET#${SCRIPT_DIR}/} (600)"
        fi
        [ -z "$ADMIN_PW" ] && warn "no password set — the install/deploy steps will prompt for it."
    fi

    log ""
    ok "Profile '${NAME}' ready."
}

# ============================================================================
# Step menu — drives the existing scripts (install/configure/start/TLS).
# Build & deploy are intentionally out of scope.
# ============================================================================
DRY="off"
main_menu() {
    set_paths
    [ -f "$OCCAS_CONF" ] || { warn "profile '${NAME}' has no occas.conf — running the interview."; run_wizard; }
    local OH DOM AUSER
    OH="$(read_prop "$OCCAS_CONF" oracle.home)"
    DOM="$(read_prop "$OCCAS_CONF" domain.name)"
    AUSER="$(read_prop "$OCCAS_CONF" admin.username)"

    while :; do
        local s_install="\xe2\x97\x8b" s_domain="\xe2\x97\x8b"   # ○ pending
        [ -d "${OH}/wlserver" ] && s_install="${C_GREEN}\xe2\x9c\x93${C_RESET}"   # ✓ done
        [ -d "${OH}/user_projects/domains/${DOM}" ] && s_domain="${C_GREEN}\xe2\x9c\x93${C_RESET}"
        log ""
        log "${C_BOLD}BLADE install — profile '${NAME}'${C_RESET}   domain=${DOM}  home=${OH}"
        log "  dry-run: ${DRY}   (toggle with 'd')"
        rule
        log "  [e] Edit / rebuild this profile (re-run the interview)"
        log ""
        log "  OCCAS  (run these ON the AdminServer box)"
        log "      [p] Preflight — check host prerequisites"
        local inst_note=""; [ -d "${OH}/wlserver" ] && inst_note="  (already installed — will skip)"
        printf '  %b [1] Install OCCAS binaries%s\n' "$s_install" "$inst_note"
        printf '  %b [2] Create the dynamic-cluster domain\n' "$s_domain"
        log "      [3] Start AdminServer via Node Manager"
        log ""
        log "  TLS  (optional)"
        log "      [4] Generate CA + identity/trust keystores"
        log "      [5] Install keystores + enable HTTPS / SIP TLS"
        rule
        log "  [q] Quit    (then: ./build.sh  and  ./deploy.sh ${NAME})"
        local c; read -r -p "  choose: " c || c="q"
        local dr=""; [ "$DRY" = "on" ] && dr="--dry-run"
        case "$c" in
            e) run_wizard; OH="$(read_prop "$OCCAS_CONF" oracle.home)"; DOM="$(read_prop "$OCCAS_CONF" domain.name)"; AUSER="$(read_prop "$OCCAS_CONF" admin.username)" ;;
            p) do_preflight ;;
            1) do_install   || warn "install step returned an error" ;;
            2) do_configure || warn "configure step returned an error" ;;
            3) start_admin "$OH" "$DOM" "$AUSER" ;;
            4) "${SCRIPT_DIR}/tls/make-certs.sh" "$DEPLOY_CONF" || warn "make-certs returned an error" ;;
            5) "${SCRIPT_DIR}/tls/install-ssl.sh" "$DEPLOY_CONF" $dr || warn "install-ssl returned an error" ;;
            d) [ "$DRY" = "on" ] && DRY="off" || DRY="on" ;;
            q|"") break ;;
            *) warn "unknown choice: $c" ;;
        esac
    done
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
    grep -oE 'name="Converged Application Server" version="[0-9]+\.[0-9]+' "$1/inventory/registry.xml" 2>/dev/null \
        | grep -oE '[0-9]+\.[0-9]+$' | head -1
}

# Describe the effective JDK (arg java.home > $JAVA_HOME > PATH). Echoes a
# one-line description; returns 0 real JDK, 1 JRE-only, 2 none found.
jdk_describe() {
    local jh="$1" jbin jdir ver
    [ -z "$jh" ] && jh="${JAVA_HOME:-}"
    if [ -n "$jh" ] && [ -x "${jh}/bin/java" ]; then jbin="${jh}/bin/java"; jdir="$jh"
    elif command -v java >/dev/null 2>&1; then jbin="$(command -v java)"; jdir="$(cd "$(dirname "$jbin")/.." 2>/dev/null && pwd)" || jdir=""
    else echo "no JDK found (set JAVA_HOME or put one on PATH)"; return 2; fi
    ver="$("$jbin" -version 2>&1 | head -1 | sed 's/"//g')"
    if [ -n "$jdir" ] && [ -x "${jdir}/bin/javac" ]; then echo "${ver}  (${jdir})"; return 0
    else echo "${ver}  (${jdir:-$jbin}) — JRE only, no javac"; return 1; fi
}

# Major version number reported by a java binary: 8, 11, 17, 21...
# Handles both the old "1.8.0_x" scheme and the modern "21.0.1" scheme.
jdk_major() {
    local raw
    raw="$("$1" -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')" || raw=""
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
        # shellcheck disable=SC1090
        . "$setwls" >/dev/null
        export BEA_HOME="$mwhome"          # the .py uses BEA_HOME for the domain dir
        java weblogic.WLST occas-replicated-dynamiccluster.py
    ) || rc=$?
    rm -rf "$work"
    [ "$rc" -eq 0 ] || { warn "configure failed (WLST rc=${rc})"; return 1; }
    ok "Domain '${domain}' written under ${mwhome}/user_projects/domains/"
    warn "Next: start the NodeManager on each machine, then the AdminServer (menu option 3)."
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

    log ""
    if [ -n "$PF_NEED" ]; then
        warn "Run these as root on the install host, then re-check (option p):"
        log  "    sudo groupadd ${inv_grp}"
        log  "    sudo useradd -g ${inv_grp} -m oracle      ${C_DIM}# or add your own user to ${inv_grp}${C_RESET}"
        log  "    sudo mkdir -p '$(dirname "$mwhome")' '${inv_loc}'"
        log  "    sudo chown -R oracle:${inv_grp} '$(dirname "$mwhome")' '${inv_loc}'"
    elif [ "$os" != "Darwin" ]; then
        ok "Preflight looks good — ready for step 1 (install)."
    fi
}

start_admin() {
    local oh="$1" dom="$2" auser="$3"
    local addr port type
    addr="$(read_prop "$OCCAS_CONF" machine.1 | cut -d: -f2)"
    port="$(read_prop "$OCCAS_CONF" machine.1 | cut -d: -f3)"
    type="$(read_prop "$OCCAS_CONF" machine.1 | cut -d: -f4)"
    info "Starting AdminServer via Node Manager (${addr:-localhost}:${port:-5556}, ${type:-ssl})"
    MW_HOME="$oh" DOMAIN_NAME="$dom" ADMIN_SERVER="AdminServer" \
        NM_HOST="${addr:-localhost}" NM_PORT="${port:-5556}" NM_USER="$auser" NM_TYPE="${type:-ssl}" \
        "${SCRIPT_DIR}/misc/start-admin-nm.sh" || warn "start-admin-nm returned an error"
}

# ============================================================================
# Entry
# ============================================================================
if [ -z "$NAME" ]; then
    # No name given: list profiles, let the user pick or create.
    profiles=()
    if [ -d "$CONF_BASE" ]; then
        for dpath in "$CONF_BASE"/*/; do
            [ -f "${dpath}occas.conf" ] && profiles+=("$(basename "$dpath")")
        done
    fi
    if [ "${#profiles[@]}" -eq 0 ]; then
        info "No profiles yet — let's build one."
        run_wizard
        main_menu
    else
        log "${C_BOLD}BLADE install — profiles${C_RESET}"
        n=1; for p in "${profiles[@]}"; do log "  [$n] $p"; n=$((n+1)); done
        log "  [c] create a new profile"
        read -r -p "  choose: " pick || pick="c"
        case "$pick" in
            c|"") NAME=""; run_wizard; main_menu ;;
            *[!0-9]*) die "invalid choice" ;;
            *) NAME="${profiles[$((pick-1))]:-}"; [ -n "$NAME" ] || die "no such profile"; main_menu ;;
        esac
    fi
else
    set_paths
    case "$JUMP" in
        wizard)    run_wizard ;;
        preflight) [ -f "$OCCAS_CONF" ] || die "no profile '${NAME}' yet — create it first: ./install.sh ${NAME}"; do_preflight ;;
        *)         [ -f "$OCCAS_CONF" ] || run_wizard ;;
    esac
    main_menu
fi
