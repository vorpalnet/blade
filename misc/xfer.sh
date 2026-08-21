# misc/xfer.sh - privileged file transfer to engine hosts. Sourced by install.sh,
# sync-occas.sh and tls/install-ssl.sh (needs `warn` from the host script).
#
# The problem this solves: the local trees (Oracle home, domains, keystores)
# are owned by the install user and hold 0600 secrets, while ssh reaches the
# far side as the LOGIN user — the only key cloud images plant. Neither user
# can do the copy alone. So: local root reads, remote root writes
# (--rsync-path="sudo rsync"), and --chown lands everything under the right
# owner BY NAME on the receiving side — no numeric uid agreement required.
# When the invoker already owns the tree (legacy installs), every call here
# degrades to today's plain rsync with no sudo anywhere.

# Owner of a path as "user:group"; empty when the path doesn't exist.
# (GNU stat first, then BSD/macOS — the scripts dry-run on a Mac.)
# -L follows symlinks: ORACLE_HOME is the '<base>/current' link, and the link
# itself is root-owned while the install tree it points at is the install user's.
# Without -L this reports root:root and provisioning replicates root, never the
# install user (no oracle account, root-owned OCCAS on the engine hosts).
xfer_owner_of() {
    stat -L -c '%U:%G' "$1" 2>/dev/null || stat -L -f '%Su:%Sg' "$1" 2>/dev/null || true
}

# Run a command as <user>: plain when we already are them, else sudo. -H so
# anything the command scribbles under $HOME lands in THEIR home, which is the
# only one they can traverse (home dirs are 0700 on OL8+).
xfer_run_as() {
    local u="$1"; shift
    if [ "$(id -un)" = "$u" ] || ! command -v sudo >/dev/null 2>&1; then "$@"
    else sudo -H -u "$u" "$@"; fi
}

# ssh command for a ROOT-side rsync: root has no keys or known_hosts of its
# own, so point it at the invoker's agent (root may open the socket) or, with
# no agent, at the invoker's key files.
xfer_ssh_cmd() {
    local c="ssh -o BatchMode=yes -o UserKnownHostsFile=${HOME}/.ssh/known_hosts"
    if [ -n "${SSH_AUTH_SOCK:-}" ]; then
        c="${c} -o IdentityAgent=${SSH_AUTH_SOCK}"
    else
        local k; for k in id_ed25519 id_ecdsa id_rsa; do
            [ -f "${HOME}/.ssh/${k}" ] && c="${c} -o IdentityFile=${HOME}/.ssh/${k}"
        done
    fi
    printf '%s' "$c"
}

# Does this rsync support --chown (3.1+)? Decide by the VERSION number, not by
# grepping `rsync --help` for the flag — the help text's format varies between
# builds and that grep was seen to misfire on a 3.1.3 that clearly has --chown,
# which then aborted a perfectly capable copy. Fall back to the help probe only
# if the version line can't be parsed.
xfer_rsync_has_chown() {
    local v maj min
    v="$(rsync --version 2>/dev/null | sed -n 's/^rsync  *version \([0-9][0-9.]*\).*/\1/p' | head -1)"
    case "$v" in
        [0-9]*.[0-9]*)
            maj="${v%%.*}"; min="${v#*.}"; min="${min%%.*}"
            [ "$maj" -gt 3 ] && return 0
            [ "$maj" -eq 3 ] && [ "$min" -ge 1 ] && return 0
            return 1 ;;
    esac
    rsync --help 2>&1 | grep -q -- --chown
}

# xfer_rsync <owner[:group]> <src> <login@host:dst> [extra rsync options...]
xfer_rsync() {
    local own="$1" src="$2" dst="$3"; shift 3
    if [ "${own%%:*}" = "$(id -un)" ]; then
        rsync -a "$@" "$src" "$dst"
        return
    fi
    if xfer_rsync_has_chown; then
        sudo rsync -a "$@" -e "$(xfer_ssh_cmd)" --rsync-path="sudo rsync" --chown "$own" "$src" "$dst"
        return
    fi
    # Pre-3.1 rsync (no --chown): copy as remote root, then set ownership BY NAME
    # on the receiving side. Same end state as --chown, one extra ssh; no hard
    # dependency on the local rsync version, so provisioning never dead-ends here.
    warn "local rsync has no --chown (pre-3.1) — copying, then chown ${own} on the remote."
    sudo rsync -a "$@" -e "$(xfer_ssh_cmd)" --rsync-path="sudo rsync" "$src" "$dst" || return 1
    local host="${dst%%:*}" rpath="${dst#*:}"
    ssh -o BatchMode=yes "$host" "sudo chown -R '${own}' '${rpath}'"
}
