# blade-paths.sh — shared BLADE profile-path resolution + legacy migration.
#
# Sourced by install.sh, deploy.sh, make-certs.sh and certs.sh so every tool
# agrees on where a profile and its certs live, and migrates the legacy layout
# identically. build.sh reads a profile too but never migrates (it is read-only);
# it resolves the path itself, tolerating both layouts.
#
#   New layout:   ~/.blade/<name>/profile.conf   +   ~/.blade/<name>/certs/
#   Legacy:       ~/.blade/<name>.conf           +   ~/.blade/<name>/  (loose certs,
#                 beside <name>.log / <name>.urls)
#
# The migration is idempotent and safe: nothing to do once migrated, and the
# authored config file is moved LAST, so a failure part-way is retried cleanly on
# the next run (profile.conf still absent → migrate again; the cert moves are
# no-ops the second time).

: "${BLADE_HOME:=$HOME/.blade}"

# Migrate <name> from the legacy layout in place, if needed.
blade_migrate_profile() {
    local name="$1"
    local dir="${BLADE_HOME}/${name}"
    local legacy="${BLADE_HOME}/${name}.conf"
    [ -f "${dir}/profile.conf" ] && return 0   # already the new layout
    [ -f "$legacy" ] || return 0               # nothing to migrate (or brand new)
    mkdir -p "${dir}/certs" || return 1
    # Loose cert material sat directly in ~/.blade/<name>/; tuck it under certs/.
    # The .log / .urls files are NOT certs and stay put.
    local f
    for f in "${dir}"/*.p12 "${dir}"/*.pem "${dir}"/*.jks "${dir}"/*.crt "${dir}"/*.key "${dir}"/*.csr; do
        [ -f "$f" ] && mv -f "$f" "${dir}/certs/" 2>/dev/null || true
    done
    mv -f "$legacy" "${dir}/profile.conf"
}

# Echo the profile conf path for <name>, migrating the legacy layout first.
blade_profile_conf() {
    blade_migrate_profile "$1"
    printf '%s' "${BLADE_HOME}/$1/profile.conf"
}

# Echo the default cert dir for <name> (a certs.dir= in the conf overrides this).
blade_certs_dir() {
    printf '%s' "${BLADE_HOME}/$1/certs"
}

# Derive the default cert dir from a profile conf PATH — for tools handed the path
# rather than a name (install.sh passes the resolved path to make-certs/certs.sh).
# New layout (…/profile.conf) → …/certs ; legacy (…/<name>.conf) → …/<name>/.
blade_certs_dir_for_conf() {
    case "$1" in
        */profile.conf) printf '%s' "$(dirname "$1")/certs" ;;
        *)              printf '%s' "${1%.conf}" ;;
    esac
}

# Derive the env NAME from a profile conf PATH (…/<name>/profile.conf → <name>;
# legacy …/<name>.conf → <name>).
blade_name_for_conf() {
    case "$1" in
        */profile.conf) basename "$(dirname "$1")" ;;
        *)              basename "${1%.conf}" ;;
    esac
}
