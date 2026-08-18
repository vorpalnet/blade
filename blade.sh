#!/usr/bin/env bash
# ============================================================================
# blade.sh — DEPRECATED shim. The installer was renamed to install.sh so the
# three tools read as verbs for the three lifecycle stages:
#
#   install.sh   stand up the server (OCCAS, domain, cluster, Node Manager, TLS)
#   build.sh     compile the artifacts
#   deploy.sh    push the artifacts to the server
#
# This forwards to install.sh so existing muscle memory and docs keep working.
# It will be removed in a future release — call install.sh directly.
# ============================================================================
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
printf '\033[33m\xe2\x9a\xa0 blade.sh is now install.sh — forwarding. Please call install.sh directly.\033[0m\n' >&2
exec "${here}/install.sh" "$@"
