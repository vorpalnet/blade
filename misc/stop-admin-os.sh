#!/usr/bin/env bash
# stop-admin-os.sh — OS-level stop of the WebLogic servers belonging to a domain,
# matched by domain home in each JVM's cmdline (never a blind pkill).
#
# Used as the systemd ExecStop for the BLADE AdminServer unit (weblogic.service):
# the AdminServer is launched THROUGH Node Manager via misc/start-admin-nm.sh, so
# it isn't a child of the systemd unit and can't be stopped by KillMode. nmKill is
# unreliable with the pure-Java Node Manager (NativeVersionEnabled=false, required
# on aarch64) when a server is script-launched with a child JVM, so we stop at the
# OS level — the same thing blade.sh's stop_admin ('x') does.
#
# Required env: DOMAIN_HOME (the app domain whose servers to stop).
set -euo pipefail

: "${DOMAIN_HOME:?set DOMAIN_HOME to the app domain home whose servers to stop}"
command -v pgrep >/dev/null 2>&1 || { echo "no pgrep — cannot OS-stop servers" >&2; exit 1; }

pids=""
for p in $(pgrep -f weblogic.Name 2>/dev/null || true); do
    cmd="$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null || true)"
    case "$cmd" in *"$DOMAIN_HOME"*) pids="${pids} ${p}" ;; esac
done
pids="${pids# }"
[ -n "$pids" ] || { echo "no servers running under ${DOMAIN_HOME}"; exit 0; }

echo "stopping servers under ${DOMAIN_HOME}: ${pids}"
# shellcheck disable=SC2086
kill ${pids} 2>/dev/null || true

# Wait for graceful exit, then escalate to SIGKILL.
for _ in $(seq 1 30); do
    still=""
    for p in ${pids}; do kill -0 "$p" 2>/dev/null && still="${still} ${p}"; done
    [ -n "$still" ] || { echo "stopped."; exit 0; }
    sleep 1
done
echo "escalating SIGKILL:${still}" >&2
# shellcheck disable=SC2086
kill -9 ${still} 2>/dev/null || true
exit 0
