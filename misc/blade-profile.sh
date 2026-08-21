# blade-profile.sh — shared profile listing / picking / key-upsert for the three
# BLADE scripts. Sourced (never executed) by build.sh, deploy.sh and install.sh so
# all three enumerate the SAME ~/.blade/<name>/profile.conf pool and prompt the
# same way when no profile is named on the command line.
#
# What is shared here is only the part whose behaviour must MATCH across the
# scripts: listing profiles, the numbered picker, and a safe key upsert. The
# concern that DIFFERS stays in each script — deploy.sh owns the connection
# interview, install.sh owns the full wizard generator, and build.sh owns the
# app/EAR tree editor. A profile accretes keys from whichever tool touches it.
#
# Interaction (the picker) is done over /dev/tty, not stdout: build.sh tees its
# stdout into a build log, so a prompt printed to stdout would land in the log and
# a result read from stdout would be swallowed. /dev/tty reaches the real terminal
# in every caller. The chosen value comes back on stdout so callers can capture it
# with $(...).

: "${BLADE_HOME:=$HOME/.blade}"

# Every profile name, one per line, deduped and sorted. Covers the new layout
# (~/.blade/<name>/profile.conf) and the legacy flat file (~/.blade/<name>.conf).
blade_list_profiles() {
    {
        local d f
        for d in "$BLADE_HOME"/*/profile.conf; do
            [ -f "$d" ] && basename "$(dirname "$d")"
        done
        for f in "$BLADE_HOME"/*.conf; do
            [ -f "$f" ] && basename "${f%.conf}"
        done
    } 2>/dev/null | sort -u || true
    # `|| true`: the loops end on a `[ -f ]` test that is false when a glob matches
    # nothing, and with pipefail that would make this function return non-zero — which
    # under a caller's `set -e` turns `x="$(blade_list_profiles)"` into a script abort.
}

# Resolve a profile NAME to its conf path (new layout wins over legacy); empty if
# neither exists. Same resolution the three scripts already use inline.
blade_profile_conf_path() {
    if   [ -f "${BLADE_HOME}/$1/profile.conf" ]; then printf '%s' "${BLADE_HOME}/$1/profile.conf"
    elif [ -f "${BLADE_HOME}/$1.conf" ];         then printf '%s' "${BLADE_HOME}/$1.conf"
    fi
    return 0   # empty output (not a non-zero exit) signals "no such profile" — a
               # non-zero return would abort a caller's `x="$(...)"` under set -e.
}

# Upsert key=value into a conf, preserving every other line. Creates the file (and
# its directory) if missing. The value is written verbatim — a caller that wants an
# ENC(...) secret passes it already wrapped. Used by build.sh to write the app/EAR
# selection into the shared profile without disturbing the connection/TLS keys.
blade_set_prop() {
    local conf="$1" key="$2" val="$3" tmp
    mkdir -p "$(dirname "$conf")"
    [ -f "$conf" ] || : > "$conf"
    tmp="$(mktemp)"
    grep -v "^${key}=" "$conf" > "$tmp" 2>/dev/null || true
    printf '%s=%s\n' "$key" "$val" >> "$tmp"
    mv -f "$tmp" "$conf"
}

# Interactive numbered picker over /dev/tty. Echoes the chosen profile name to
# stdout, "__create__" to signal "make a new one", or returns 1 on quit/EOF.
# Optional $1 = "key|label" adds one extra menu row (build.sh uses it for
# "build the full set, no profile"); choosing it echoes "__<key>__".
# Callers must gate on a TTY themselves; over a closed /dev/tty this returns 1.
blade_pick_profile() {
    local extra="${1:-}" ekey="" elabel=""
    [ -n "$extra" ] && { ekey="${extra%%|*}"; elabel="${extra#*|}"; }

    local -a names=()
    local n
    while IFS= read -r n; do [ -n "$n" ] && names+=("$n"); done < <(blade_list_profiles)

    {
        echo ""
        echo "  BLADE profiles (~/.blade):"
        echo ""
        if [ "${#names[@]}" -eq 0 ]; then
            echo "   (none yet)"
        else
            local i=1
            for n in "${names[@]}"; do printf '  %2d  %s\n' "$i" "$n"; i=$((i + 1)); done
        fi
        [ -n "$ekey" ] && printf '   %s  %s\n' "$ekey" "$elabel"
        echo "   c  create a new profile"
        echo "   q  quit"
        echo ""
    } > /dev/tty 2>/dev/null || return 1

    local choice
    while true; do
        printf '  profile: ' > /dev/tty
        IFS= read -r choice < /dev/tty || return 1
        if [ -n "$ekey" ] && [ "$choice" = "$ekey" ]; then printf '__%s__' "$ekey"; return 0; fi
        case "$choice" in
            q|Q|"")   return 1 ;;
            c|C)      printf '__create__'; return 0 ;;
            *[!0-9]*) echo "  enter a number, or c / q." > /dev/tty ;;
            *)        if [ "$choice" -ge 1 ] && [ "$choice" -le "${#names[@]}" ]; then
                          printf '%s' "${names[$((choice - 1))]}"; return 0
                      fi
                      echo "  out of range." > /dev/tty ;;
        esac
    done
}
