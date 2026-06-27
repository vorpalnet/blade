#!/usr/bin/env bash
# ============================================================================
# sync-occas.sh - Distribute a validated, patched OCCAS home to the engine
#                 nodes and switch to it atomically (with rollback).
#
# Patching OCCAS/WebLogic is manual and uncertain (README-custom, OPatch grind,
# never sure it'll work) — so this DOES NOT patch. You patch ONCE, out-of-place,
# into a versioned home on the builder/admin host, validate it, and then this
# ships that known-good home to the other nodes and flips a stable symlink.
#
#   <base>/8.3.0            GA home
#   <base>/8.3.0_p<n>       patched home (a COPY, patched once, validated)
#   <base>/current  ->      symlink each node resolves; the domain uses THIS path
#
# Switching versions = repoint 'current' + restart. Rollback = repoint back.
# Binaries are node-LOCAL (engines don't depend on the shared FS at runtime);
# only this script's copy step crosses the network.
#
# Usage:
#   ./sync-occas.sh <env> <action> [version] [options]
#     <env>      name → build-profiles/deploy/<name>.conf, or a path
#     <action>   distribute | switch | status
#     version    the versioned dir name (e.g. 8.3.0_p1); required for
#                distribute/switch
#
#   distribute <ver>   rsync <base>/<ver>/ from HERE to each engine node (same path)
#   switch <ver>       repoint <base>/current -> <base>/<ver> on each node
#                      (rollback = switch back to the previous version)
#   status             show each node's current -> target + available versions
#
# Options:
#   --nodes a,b   act on only these engine nodes (canary / rolling: do one, verify,
#                 then the rest). Default: all engine.nodes.
#   --local       also act on THIS (admin) host's symlink — affects AdminServer
#                 AND engine0, so do this as its own step, not as the canary.
#   --delete      rsync with --delete (mirror exactly; off by default for safety)
#   --dry-run     print what would happen; run nothing
#
# Conf keys (build-profiles/deploy/<env>.conf):
#   engine.nodes      CSV of engine hosts (reused from deploy.sh)
#   ssh.user          ssh user for rsync/ssh (reused)
#   occas.base.dir    dir holding the versioned homes (default /opt/oracle/occas)
#   occas.current.link the stable symlink (default <occas.base.dir>/current)
#
# RESTART is intentionally manual: after 'switch', restart the WLS server(s) on
# each node so they pick up the new binaries, validating as you go. This script
# won't bounce servers for you — for an uncertain patch you want eyes on each one.
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="${SCRIPT_DIR}/build-profiles/deploy"

if [ -z "${NO_COLOR:-}" ] && [ -t 1 ]; then
    C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'
    C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else C_BLUE=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_DIM=""; C_BOLD=""; C_RESET=""; fi
log()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()   { printf '%s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s⚠%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()  { printf '%s✗%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

# --- Parse args ---
ENV_ARG=""; ACTION=""; VERSION=""; NODES_OVERRIDE=""; DO_LOCAL=false; DELETE=false; DRY_RUN=false
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,60p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --nodes)   shift; NODES_OVERRIDE="${1:-}"; [ -n "$NODES_OVERRIDE" ] || die "--nodes needs a value" ;;
        --nodes=*) NODES_OVERRIDE="${1#--nodes=}" ;;
        --local)   DO_LOCAL=true ;;
        --delete)  DELETE=true ;;
        --dry-run) DRY_RUN=true ;;
        -*)        die "Unknown option: $1" ;;
        *)         POSITIONAL+=("$1") ;;
    esac
    shift
done
[ ${#POSITIONAL[@]} -ge 2 ] || die "Usage: ./sync-occas.sh <env> <distribute|switch|status> [version] [options]"
ENV_ARG="${POSITIONAL[0]}"
ACTION="${POSITIONAL[1]}"
VERSION="${POSITIONAL[2]:-}"
case "$ACTION" in
    distribute|switch) [ -n "$VERSION" ] || die "'${ACTION}' requires a version (e.g. 8.3.0_p1)." ;;
    status) ;;
    *) die "Unknown action: ${ACTION} (distribute|switch|status)" ;;
esac

# --- Resolve conf ---
if [ -f "$ENV_ARG" ]; then CONF_FILE="$ENV_ARG"; ENV_NAME="$(basename "${ENV_ARG%.conf}")"
else ENV_NAME="$ENV_ARG"; CONF_FILE="${DEPLOY_DIR}/${ENV_NAME}.conf"; fi
[ -f "$CONF_FILE" ] || die "Conf not found: ${CONF_FILE}"

read_prop() {
    local file="$1" key="$2"
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

SSH_USER=$(read_prop  "$CONF_FILE" "ssh.user")
ENGINE_NODES_RAW=$(read_prop "$CONF_FILE" "engine.nodes")
BASE_DIR=$(read_prop  "$CONF_FILE" "occas.base.dir");      BASE_DIR="${BASE_DIR:-/opt/oracle/occas}"
CURRENT_LINK=$(read_prop "$CONF_FILE" "occas.current.link"); CURRENT_LINK="${CURRENT_LINK:-${BASE_DIR}/current}"

# --- Node list (override > conf) ---
NODES=()
if [ -n "$NODES_OVERRIDE" ]; then IFS=', ' read -r -a NODES <<< "$NODES_OVERRIDE"
elif [ -n "$ENGINE_NODES_RAW" ]; then IFS=', ' read -r -a NODES <<< "$ENGINE_NODES_RAW"; fi

VER_PATH="${BASE_DIR}/${VERSION}"

# --- Header ---
log "${C_BOLD}OCCAS binary sync${C_RESET}"
log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
log "  action:       ${ACTION}${VERSION:+  version=${VERSION}}"
log "  base dir:     ${BASE_DIR}    current -> ${CURRENT_LINK}"
[ ${#NODES[@]} -gt 0 ] && log "  engine nodes: ${NODES[*]} (ssh ${SSH_USER})"
[ "$DO_LOCAL" = true ] && log "  ${C_YELLOW}+ local (admin) host — affects AdminServer AND engine0${C_RESET}"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

need_ssh_user() { [ -n "$SSH_USER" ] || die "${CONF_FILE}: ssh.user required for engine-node operations"; }

# ----------------------------------------------------------------------------
do_distribute() {
    if [ ! -d "$VER_PATH" ]; then
        # Don't block a preview just because you haven't patched yet.
        if [ "$DRY_RUN" = true ]; then warn "Source home ${VER_PATH} not present yet — dry-run continues anyway."
        else die "Source home not found here: ${VER_PATH} (patch it out-of-place first)."; fi
    fi
    [ ${#NODES[@]} -ge 1 ] || die "No engine.nodes to distribute to."
    need_ssh_user
    local rsync_opts=(-a -h --stats); [ "$DELETE" = true ] && rsync_opts+=(--delete)
    local n
    for n in "${NODES[@]}"; do
        info "distribute ${VERSION} → ${SSH_USER}@${n}:${VER_PATH}/"
        if [ "$DRY_RUN" = true ]; then
            log "${C_DIM}  [dry-run] ssh ${SSH_USER}@${n} mkdir -p ${VER_PATH}${C_RESET}"
            log "${C_DIM}  [dry-run] rsync ${rsync_opts[*]} ${VER_PATH}/ ${SSH_USER}@${n}:${VER_PATH}/${C_RESET}"
        else
            ssh "${SSH_USER}@${n}" "mkdir -p '${VER_PATH}'"
            rsync "${rsync_opts[@]}" "${VER_PATH}/" "${SSH_USER}@${n}:${VER_PATH}/"
        fi
    done
    ok "distributed ${VERSION}. Next: ./sync-occas.sh ${ENV_NAME} switch ${VERSION} --nodes <canary>"
}

# Build the remote/local switch command (atomic symlink repoint, guarded on the
# target version existing so we never point 'current' at a missing home).
switch_cmd() {
    printf "test -d '%s' && ln -sfn '%s' '%s' && readlink '%s'" "$VER_PATH" "$VER_PATH" "$CURRENT_LINK" "$CURRENT_LINK"
}

do_switch() {
    local cmd; cmd="$(switch_cmd)"
    if [ "$DO_LOCAL" = true ]; then
        info "switch (local/admin) ${CURRENT_LINK} → ${VER_PATH}"
        if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] ${cmd}${C_RESET}"
        else eval "$cmd" || die "local switch failed (does ${VER_PATH} exist here?)"; fi
    fi
    local n
    for n in "${NODES[@]}"; do
        need_ssh_user
        info "switch ${SSH_USER}@${n}: ${CURRENT_LINK} → ${VER_PATH}"
        if [ "$DRY_RUN" = true ]; then log "${C_DIM}  [dry-run] ssh ${SSH_USER}@${n} \"${cmd}\"${C_RESET}"
        else ssh "${SSH_USER}@${n}" "$cmd" || die "switch failed on ${n} (is ${VER_PATH} present there? run 'distribute' first)"; fi
    done
    ok "switched to ${VERSION}."
    warn "RESTART required for new binaries to take effect:"
    warn "  engine nodes — restart the WLS server(s) on each switched node and validate before the next;"
    [ "$DO_LOCAL" = true ] && warn "  local host  — restart AdminServer and engine0."
    warn "Rollback any node: ./sync-occas.sh ${ENV_NAME} switch <previous-version> --nodes <node>"
}

do_status() {
    show() {  # show "label" "ssh-prefix-or-empty"
        local label="$1" pfx="$2" cur vers
        if [ -z "$pfx" ]; then
            cur="$(readlink "$CURRENT_LINK" 2>/dev/null || echo '(none)')"
            vers="$(ls -1d "${BASE_DIR}"/*/ 2>/dev/null | xargs -n1 basename 2>/dev/null | grep -v '^current$' | tr '\n' ' ')"
        else
            cur="$($pfx "readlink '${CURRENT_LINK}' 2>/dev/null || echo '(none)'")"
            vers="$($pfx "ls -1d '${BASE_DIR}'/*/ 2>/dev/null | xargs -n1 basename 2>/dev/null | grep -v '^current\$' | tr '\n' ' '")"
        fi
        printf '  %-22s current -> %s\n' "$label" "$cur"
        printf '  %-22s versions: %s\n' "" "${vers:-(none)}"
    }
    if [ "$DO_LOCAL" = true ]; then show "(local/admin)" ""; fi
    local n
    for n in "${NODES[@]}"; do
        need_ssh_user
        show "${n}" "ssh ${SSH_USER}@${n}"
    done
    [ ${#NODES[@]} -eq 0 ] && [ "$DO_LOCAL" = false ] && warn "No nodes (set engine.nodes or pass --local/--nodes)."
}

case "$ACTION" in
    distribute) do_distribute ;;
    switch)     do_switch ;;
    status)     do_status ;;
esac
