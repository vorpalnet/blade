#!/usr/bin/env bash
# check-domain-config.sh — refuse the failure mode where one unreadable file under
# $DOMAIN_HOME/config stops EVERY managed server from starting.
#
# A managed server downloads the whole domain config from the AdminServer at boot. The
# AdminServer lists each file (so its length comes from the directory entry) and then streams
# the bytes. A file it cannot READ yields a short stream, the managed server hits
#
#     java.io.IOException: Reached EOF for file: FileInfo(name=config/custom/...)
#
# treats it as a critical service failure and shuts itself down. Node Manager reports only
# "Server failed to start up but Node Manager was not aware of the reason", and the AdminServer
# logs BEA-290087 naming the file. One `sudo cp` into config/ is enough to cause it, because the
# copy lands as root:root while the AdminServer runs as the install user.
#
# The server on the AdminServer host never shows the problem: it reads the domain directory
# directly instead of downloading it. So the symptom is always "the remote engines won't start".
#
# Also flags hand-made backups sitting beside a live config (`*.bak*`). The supported backup
# path is VersionedFileStore's `.versions/` subdirectory; a `.bak` sibling is dead weight in
# every config download and is usually how the root-owned file arrives.
#
#   misc/check-domain-config.sh /opt/oracle/domains/ashburn            # report, exit 1 if broken
#   misc/check-domain-config.sh /opt/oracle/domains/ashburn --fix      # chown + park the .bak files
#   DOMAIN_HOME=... INSTALL_USER=oracle misc/check-domain-config.sh
set -uo pipefail

FIX=0
DOMAIN_HOME="${DOMAIN_HOME:-}"
for a in "$@"; do
    case "$a" in
        --fix) FIX=1 ;;
        -*)    printf 'unknown option: %s\n' "$a" >&2; exit 2 ;;
        *)     DOMAIN_HOME="$a" ;;
    esac
done
[ -n "$DOMAIN_HOME" ] || { printf 'usage: %s <domain-home> [--fix]\n' "$(basename "$0")" >&2; exit 2; }
[ -d "$DOMAIN_HOME/config" ] || { printf 'not a domain home: %s\n' "$DOMAIN_HOME" >&2; exit 2; }

# The user the AdminServer runs as: whoever owns config.xml. That is the identity that must be
# able to read every file, whatever the profile says.
OWNER="${INSTALL_USER:-$(stat -c %U "$DOMAIN_HOME/config/config.xml" 2>/dev/null)}"
[ -n "$OWNER" ] || { printf 'cannot determine the domain owner\n' >&2; exit 2; }

sudo_as() { if [ "$(id -un)" = "$OWNER" ]; then "$@"; else sudo -n -u "$OWNER" "$@" 2>/dev/null; fi; }

bad=0
printf 'domain %s (owner %s)\n' "$DOMAIN_HOME" "$OWNER"

# 1. Unreadable by the owner — the fatal case.
while IFS= read -r f; do
    [ -n "$f" ] || continue
    if ! sudo_as test -r "$f"; then
        printf '  UNREADABLE by %-8s %s (owner %s, mode %s)\n' \
            "$OWNER" "${f#$DOMAIN_HOME/}" "$(stat -c %U "$f")" "$(stat -c %a "$f")"
        bad=$((bad + 1))
        [ "$FIX" = 1 ] && sudo -n chown "$OWNER" "$f" && printf '    fixed: chown %s\n' "$OWNER"
    fi
done < <(find "$DOMAIN_HOME/config" -type f 2>/dev/null)

# 2. Hand-made backups beside BLADE's own configs — advisory, never fatal, and scoped to
# config/custom/vorpal because WebLogic ships its own .bak files elsewhere under config/
# (readme.txt.bak, sipserver.xml.bak, config.xml.bak.<epoch>, ...) which are none of our business.
strays=$(find "$DOMAIN_HOME/config/custom/vorpal" -type f -name '*.bak*' ! -path '*/.versions/*' 2>/dev/null)
if [ -n "$strays" ]; then
    n=$(printf '%s\n' "$strays" | wc -l | tr -d ' ')
    printf '  %s hand-made backup file(s) inside config/ (supported path is .versions/)\n' "$n"
    printf '%s\n' "$strays" | sed "s|^$DOMAIN_HOME/|    |"
    if [ "$FIX" = 1 ]; then
        park="$DOMAIN_HOME/config-backups"
        sudo -n mkdir -p "$park" && sudo -n chown "$OWNER" "$park"
        printf '%s\n' "$strays" | while IFS= read -r f; do sudo -n mv "$f" "$park/" && printf '    parked: %s\n' "${f#$DOMAIN_HOME/}"; done
    fi
fi

if [ "$bad" -gt 0 ] && [ "$FIX" != 1 ]; then
    printf '\n%s unreadable file(s): remote managed servers will fail to start.\n' "$bad"
    printf 'Re-run with --fix, or chown them to %s by hand.\n' "$OWNER"
    exit 1
fi
[ "$bad" -eq 0 ] && printf '  every file under config/ is readable by %s\n' "$OWNER"
exit 0
