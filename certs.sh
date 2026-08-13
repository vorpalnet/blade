#!/usr/bin/env bash
# ============================================================================
# certs.sh - TLS keystore tooling for OCCAS/BLADE environments.
#
# Produces the two keystores every server needs — identity (its cert + key)
# and trust (the CA set) — either self-generated for test rigs, or packaged
# from customer-issued material (e.g. a corporate certificate process that
# hands you PEM or PKCS12 files). The output feeds:
#
#   ./blade.sh <profile>  (row: Turn on HTTPS / SIP-TLS)  wires the keystores
#                                       the domain (admin, engine template,
#                                       static engine)
#
# Usage:
#   ./certs.sh <env> <mode> [--dry-run]
#     <env>   name → build-profiles/occas/<env>.conf (+ <env>.secret), or a path
#     <mode>  generate | import | show
#
#   generate   Self-signed test PKI: a local CA, one server identity keystore
#              whose SAN covers every host in the env (admin + machines +
#              certs.hosts extras), and a trust keystore holding the CA.
#              The server cert carries EKU serverAuth *and* clientAuth, so the
#              same identity keystore works as the client certificate when an
#              endpoint demands mutual TLS. Test/dev only — customers with a
#              real certificate process use import.
#   import     Package customer-issued material into the same keystore layout:
#                cert.import.p12=<file>        a ready-made PKCS12 (validated,
#                                              copied into place), or
#                cert.import.cert=<server.pem> PEM cert +
#                cert.import.key=<server.key>  PEM private key (needs openssl)
#                cert.import.chain=<chain.pem> CA chain PEM → trust keystore
#   show       List the contents of the env's keystores.
#
# Output matches tls/make-certs.sh exactly, so import and generate are
# interchangeable and install-ssl / emit_tls_block consume either.
#
# Conf keys (~/.blade/<env>.conf):
#   certs.dir     output directory — default tls/out/<env> (gitignored). Any
#                 other in-repo path is refused (keys must not be committable).
#   certs.hosts   extra SAN entries (CSV of dns:/ip: or bare hostnames)
#   tls.identity.alias  key alias (default blade-identity)
#   cert.import.* see import above
# Secrets (keys in the same conf, ENC()-wrapped; prompted-and-persisted if unset):
#   tls.keystore.passphrase   identity keystore + its private key
#   tls.trust.passphrase      trust keystore
#   (WebLogic wants keystore pass == key pass, so each store's key reuses it.)
#
# Files produced in certs.dir:
#   blade-ca.p12        test CA key (generate only — guard it like a password)
#   blade-ca.pem        CA certificate, PEM — import into client browsers, the
#                       JVM truststore of callers, and hand to peers
#   blade-identity.p12  server identity (cert + key) → CustomIdentityKeyStore
#   blade-trust.p12     trusted CAs                  → CustomTrustKeyStore
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OCCAS_DIR="${SCRIPT_DIR}/build-profiles/occas"

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
ENV_ARG=""; MODE=""; DRY_RUN=false
POSITIONAL=()
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,55p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        --dry-run) DRY_RUN=true ;;
        -*) die "Unknown option: $1" ;;
        *)  POSITIONAL+=("$1") ;;
    esac
    shift
done
[ ${#POSITIONAL[@]} -ge 2 ] || die "Usage: ./certs.sh <env> <generate|import|show> [--dry-run]"
ENV_ARG="${POSITIONAL[0]}"; MODE="${POSITIONAL[1]}"
case "$MODE" in generate|import|show) ;; *) die "Unknown mode: ${MODE}" ;; esac

# --- Resolve conf + secret (same convention as blade.sh profiles) ---
# One config file per env holds config + secrets: ~/.blade/<env>.conf (legacy
# build-profiles path is a fallback). Secrets are keys in the same file.
BLADE_HOME="${BLADE_HOME:-$HOME/.blade}"
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"; ENV_NAME="$(basename "${ENV_ARG%.conf}")"
else
    ENV_NAME="$ENV_ARG"; CONF_FILE="${BLADE_HOME}/${ENV_NAME}.conf"
    [ -f "$CONF_FILE" ] || CONF_FILE="${OCCAS_DIR}/${ENV_NAME}.conf"
fi
SECRET_FILE="$CONF_FILE"
[ -f "$CONF_FILE" ] || die "Conf not found: ${CONF_FILE}"
read_prop() {
    local file="$1" key="$2" v
    v="$({ grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$v" in ENC\(*\)) v="${v#ENC(}"; v="${v%)}" ;; esac   # secret: strip ENC() wrapper
    printf '%s' "$v"
}

# Output goes to the SAME place make-certs uses (tls/out/<env>), so install-ssl
# and blade.sh's emit_tls_block consume import and generate output identically.
# tls/out is gitignored; any other in-repo certs.dir is refused (uncommittable).
CERTS_DIR="$(read_prop "$CONF_FILE" "certs.dir")"; CERTS_DIR="${CERTS_DIR/#\~/$HOME}"
[ -z "$CERTS_DIR" ] && CERTS_DIR="${SCRIPT_DIR}/tls/out/${ENV_NAME}"
case "$CERTS_DIR" in
    "${SCRIPT_DIR}/tls/out"/*) ;;                                   # the gitignored default — fine
    "$SCRIPT_DIR"/*) die "certs.dir is inside the repo (${CERTS_DIR}) but not under tls/out — keys must not be committable. Use ~/.blade/certs/${ENV_NAME} or the default." ;;
esac

# Passphrases live in the one env conf as key=ENC(value); a prompted value is
# persisted so make-certs, install-ssl and emit_tls_block all read the same
# secret (they REQUIRE these keys present). WebLogic wants keystore pass == key
# pass, so each store's key uses the same passphrase as the store.
get_secret() {  # $1=conf-key  $2=prompt-label
    local v="" from_file=""
    [ -f "$SECRET_FILE" ] && { v=$(read_prop "$SECRET_FILE" "$1"); [ -n "$v" ] && from_file=1; }
    if [ -z "$v" ]; then
        [ "$DRY_RUN" = true ] && { printf 'dry-run-passphrase'; return 0; }
        read -rs -p "$2: " v; echo
        [ -n "$v" ] || die "No value for $2"
    fi
    [ "$DRY_RUN" = true ] || [ "${#v}" -ge 6 ] || die "keytool requires a passphrase of 6+ characters."
    [ -z "$from_file" ] && [ "$DRY_RUN" = false ] && printf '%s=ENC(%s)\n' "$1" "$v" >> "$CONF_FILE"
    printf '%s' "$v"
}

# --- SAN list: every machine's name + address + reverse-DNS FQDN, + certs.hosts ---
build_san() {
    local san="dns:localhost,ip:127.0.0.1" entry addr name fqdn
    local i=1 m
    while :; do
        m=$(read_prop "$CONF_FILE" "machine.${i}")
        [ -n "$m" ] || break
        IFS=: read -r name addr _ _ <<< "$m"
        # The machine's own name is a short-hostname SAN (engine1, engine2, …).
        case "$name" in *[a-zA-Z]*) san="${san},dns:${name}" ;; esac
        if [ -n "$addr" ]; then
            case "$addr" in
                *[a-zA-Z]*) san="${san},dns:${addr}" ;;
                *)
                    san="${san},ip:${addr}"
                    # Reverse-resolve the IP to its FQDN (OCI VCN DNS gives e.g.
                    # engine1.sub….oraclevcn.com) and add it + its short form, so
                    # the cert matches how servers verify each other by hostname.
                    # Without this the FQDN is absent and AdminServer→NodeManager
                    # SSL fails hostname verification. (certs.hosts still overrides
                    # for anything reverse-DNS can't reach, e.g. public IPs.)
                    fqdn="$(getent hosts "$addr" 2>/dev/null | awk '{print $2}' | head -1)"
                    if [ -n "$fqdn" ] && [ "$fqdn" != "$addr" ]; then
                        san="${san},dns:${fqdn}"
                        case "${fqdn%%.*}" in *[a-zA-Z]*) san="${san},dns:${fqdn%%.*}" ;; esac
                    fi
                    ;;
            esac
        fi
        i=$((i + 1))
    done
    local extras; extras="$(read_prop "$CONF_FILE" "certs.hosts")"
    if [ -n "$extras" ]; then
        IFS=, read -ra EX <<< "$extras"
        for entry in "${EX[@]}"; do
            entry="$(echo "$entry" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
            [ -z "$entry" ] && continue
            case "$entry" in
                dns:*|ip:*) san="${san},${entry}" ;;
                *[a-zA-Z]*) san="${san},dns:${entry}" ;;
                *)          san="${san},ip:${entry}" ;;
            esac
        done
    fi
    # dedup, order-preserving (machine name + FQDN short form can collide).
    printf '%s' "$san" | tr ',' '\n' | awk 'NF && !seen[$0]++' | paste -sd, -
}

KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL="keytool"
command -v "$KEYTOOL" >/dev/null 2>&1 || die "keytool not found (need a JDK on PATH or JAVA_HOME set)."

# Import sources can be root-only — Let's Encrypt keeps /etc/letsencrypt/{live,
# archive} at 0700 root, so certs.sh (running as the login user) can't read
# fullchain.pem or privkey.pem directly. Stage such a file through sudo into a
# private temp and hand back that readable path; the temp is shredded on exit.
STAGE_DIR=""; STAGE_N=0
cleanup_stage() { [ -n "$STAGE_DIR" ] && rm -rf "$STAGE_DIR"; }
trap cleanup_stage EXIT
stage_readable() {  # $1=path  ->  echoes a readable path; non-zero if truly absent/unreadable
    local src="$1" S=""
    [ -r "$src" ] && { printf '%s' "$src"; return 0; }
    [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && S="sudo"
    [ -n "$S" ] || return 1
    $S test -e "$src" 2>/dev/null || return 1
    [ -n "$STAGE_DIR" ] || { STAGE_DIR="$(mktemp -d)"; chmod 700 "$STAGE_DIR"; }
    STAGE_N=$((STAGE_N + 1))
    local dst="${STAGE_DIR}/stage-${STAGE_N}-$(basename "$src")"
    ( umask 077; $S cat "$src" > "$dst" ) || return 1
    printf '%s' "$dst"
}

do_generate() {
    local san id_pw trust_pw alias
    san="$(build_san)"
    alias="$(read_prop "$CONF_FILE" tls.identity.alias)"; alias="${alias:-blade-identity}"
    info "Self-signed test PKI for '${ENV_NAME}' → ${CERTS_DIR}"
    log  "  SAN: ${san}"
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] keytool: test CA (bc:c, 10y) → blade-ca.p12 / blade-ca.pem${C_RESET}"
        log "${C_DIM}  [dry-run] keytool: server keypair + CA-signed cert (san, eku serverAuth+clientAuth) → blade-identity.p12 (alias ${alias})${C_RESET}"
        log "${C_DIM}  [dry-run] keytool: trust store with CA cert → blade-trust.p12${C_RESET}"
        return 0
    fi
    id_pw="$(get_secret tls.keystore.passphrase "Identity keystore passphrase for ${ENV_NAME}")"
    trust_pw="$(get_secret tls.trust.passphrase "Trust keystore passphrase for ${ENV_NAME}")"
    mkdir -p "$CERTS_DIR"; chmod 700 "$CERTS_DIR"
    [ -f "${CERTS_DIR}/blade-identity.p12" ] && die "${CERTS_DIR}/blade-identity.p12 exists — refusing to overwrite. Delete it to re-generate."

    # 1. Test CA (self-signed, CA basic constraint). Its key is never consumed
    #    downstream (only the public blade-ca.pem is), so it shares the id passphrase.
    "$KEYTOOL" -genkeypair -alias ca -keyalg RSA -keysize 3072 -validity 3650 \
        -dname "CN=BLADE Test CA (${ENV_NAME}), O=Vorpal" -ext bc:c \
        -keystore "${CERTS_DIR}/blade-ca.p12" -storetype PKCS12 -storepass "$id_pw" -keypass "$id_pw"
    "$KEYTOOL" -exportcert -alias ca -rfc \
        -keystore "${CERTS_DIR}/blade-ca.p12" -storepass "$id_pw" > "${CERTS_DIR}/blade-ca.pem"

    # 2. Server identity, signed by the CA. EKU includes clientAuth so the
    #    same keystore serves as the client certificate for mutual TLS.
    "$KEYTOOL" -genkeypair -alias "$alias" -keyalg RSA -keysize 3072 -validity 825 \
        -dname "CN=blade-${ENV_NAME}, O=Vorpal" \
        -keystore "${CERTS_DIR}/blade-identity.p12" -storetype PKCS12 -storepass "$id_pw" -keypass "$id_pw"
    "$KEYTOOL" -certreq -alias "$alias" \
        -keystore "${CERTS_DIR}/blade-identity.p12" -storepass "$id_pw" \
    | "$KEYTOOL" -gencert -alias ca -validity 825 -rfc \
        -ext "san=${san}" -ext "ku:c=digitalSignature,keyEncipherment" -ext "eku=serverAuth,clientAuth" \
        -keystore "${CERTS_DIR}/blade-ca.p12" -storepass "$id_pw" > "${CERTS_DIR}/blade-server.pem"
    # Import chain: CA first, then the signed server cert.
    "$KEYTOOL" -importcert -alias ca -noprompt -file "${CERTS_DIR}/blade-ca.pem" \
        -keystore "${CERTS_DIR}/blade-identity.p12" -storepass "$id_pw"
    "$KEYTOOL" -importcert -alias "$alias" -file "${CERTS_DIR}/blade-server.pem" \
        -keystore "${CERTS_DIR}/blade-identity.p12" -storepass "$id_pw"

    # 3. Trust store = just the CA.
    "$KEYTOOL" -importcert -alias ca -noprompt -file "${CERTS_DIR}/blade-ca.pem" \
        -keystore "${CERTS_DIR}/blade-trust.p12" -storetype PKCS12 -storepass "$trust_pw"

    chmod 600 "${CERTS_DIR}"/*.p12
    ok "Wrote ${CERTS_DIR}/{blade-ca.p12,blade-ca.pem,blade-identity.p12,blade-trust.p12}"
    next_steps
}

do_import() {
    local p12 cert key chain pw
    p12="$(read_prop "$CONF_FILE" "cert.import.p12")"
    cert="$(read_prop "$CONF_FILE" "cert.import.cert")"
    key="$(read_prop "$CONF_FILE" "cert.import.key")"
    chain="$(read_prop "$CONF_FILE" "cert.import.chain")"
    [ -n "$p12" ] || [ -n "$cert" ] || die "${CONF_FILE}: set cert.import.p12=<file>  OR  cert.import.cert= + cert.import.key= (+ cert.import.chain=)"

    info "Import customer-issued certs for '${ENV_NAME}' → ${CERTS_DIR}"
    if [ "$DRY_RUN" = true ]; then
        [ -n "$p12" ]   && log "${C_DIM}  [dry-run] validate + copy ${p12} → blade-identity.p12${C_RESET}"
        [ -n "$cert" ]  && log "${C_DIM}  [dry-run] openssl pkcs12 -export ${cert} + ${key} → blade-identity.p12${C_RESET}"
        [ -n "$chain" ] && log "${C_DIM}  [dry-run] import ${chain} → blade-trust.p12${C_RESET}"
        return 0
    fi
    local id_pw trust_pw
    id_pw="$(get_secret tls.keystore.passphrase "Identity keystore passphrase for ${ENV_NAME}")"
    mkdir -p "$CERTS_DIR"; chmod 700 "$CERTS_DIR"

    # Stage any root-only sources (e.g. Let's Encrypt under /etc/letsencrypt).
    local p12r="" certr="" keyr="" chainr=""
    [ -n "$p12" ]   && { p12r="$(stage_readable "$p12")"     || die "cert.import.p12 not found or unreadable (need sudo?): ${p12}"; }
    [ -n "$cert" ]  && { certr="$(stage_readable "$cert")"   || die "cert.import.cert not found or unreadable (need sudo?): ${cert}"; }
    [ -n "$key" ]   && { keyr="$(stage_readable "$key")"     || die "cert.import.key not found or unreadable (need sudo?): ${key}"; }
    [ -n "$chain" ] && { chainr="$(stage_readable "$chain")" || die "cert.import.chain not found or unreadable (need sudo?): ${chain}"; }

    if [ -n "$p12" ]; then
        # Validate it opens with tls.keystore.passphrase before installing it —
        # the supplied p12's password MUST equal that passphrase.
        "$KEYTOOL" -list -keystore "$p12r" -storetype PKCS12 -storepass "$id_pw" >/dev/null \
            || die "Cannot open ${p12} with tls.keystore.passphrase. Re-key the p12 to that passphrase, or set tls.keystore.passphrase to the p12's password."
        cp "$p12r" "${CERTS_DIR}/blade-identity.p12"
    else
        [ -n "$certr" ] || die "${CONF_FILE}: cert.import.cert required when cert.import.p12 is unset"
        [ -n "$keyr" ]  || die "${CONF_FILE}: cert.import.key required with cert.import.cert"
        command -v openssl >/dev/null 2>&1 || die "PEM import needs openssl (PKCS12 assembly from a bare key). Ask for a .p12 instead, or install openssl."
        local alias; alias="$(read_prop "$CONF_FILE" tls.identity.alias)"; alias="${alias:-blade-identity}"
        openssl pkcs12 -export -name "$alias" -in "$certr" -inkey "$keyr" \
            ${chainr:+-certfile "$chainr"} \
            -out "${CERTS_DIR}/blade-identity.p12" -passout "pass:${id_pw}"
    fi

    if [ -n "$chain" ]; then
        trust_pw="$(get_secret tls.trust.passphrase "Trust keystore passphrase for ${ENV_NAME}")"
        rm -f "${CERTS_DIR}/blade-trust.p12"
        # Split the chain into individual certs; import each under ca-N.
        awk -v dir="$CERTS_DIR" 'BEGIN{n=0} /BEGIN CERT/{n++} n{print > (dir "/.chain-" n ".pem")}' "$chainr"
        local f i=0
        for f in "${CERTS_DIR}"/.chain-*.pem; do
            [ -f "$f" ] || die "No certificates found in ${chain}"
            i=$((i + 1))
            "$KEYTOOL" -importcert -alias "ca-${i}" -noprompt -file "$f" \
                -keystore "${CERTS_DIR}/blade-trust.p12" -storetype PKCS12 -storepass "$trust_pw"
            rm -f "$f"
        done
    else
        warn "No cert.import.chain — blade-trust.p12 not (re)built. The 'secure' step needs one; point cert.import.chain at the issuing CA chain PEM."
    fi

    chmod 600 "${CERTS_DIR}"/*.p12 2>/dev/null || true
    ok "Wrote ${CERTS_DIR}/blade-identity.p12$([ -n "$chain" ] && echo ' and blade-trust.p12')"
    next_steps
}

do_show() {
    local id_pw trust_pw f pw
    id_pw="$(get_secret tls.keystore.passphrase "Identity keystore passphrase for ${ENV_NAME}")"
    trust_pw="$(get_secret tls.trust.passphrase "Trust keystore passphrase for ${ENV_NAME}")"
    for f in blade-ca.p12 blade-identity.p12 blade-trust.p12; do
        case "$f" in blade-trust.p12) pw="$trust_pw" ;; *) pw="$id_pw" ;; esac
        if [ -f "${CERTS_DIR}/${f}" ]; then
            info "${CERTS_DIR}/${f}"
            "$KEYTOOL" -list -v -keystore "${CERTS_DIR}/${f}" -storetype PKCS12 -storepass "$pw" \
                | grep -E 'Alias|Owner|Issuer|Valid|DNSName|IPAddress|ExtendedKeyUsage|serverAuth|clientAuth' \
                | sed 's/^/  /'
        else
            warn "missing: ${CERTS_DIR}/${f}"
        fi
    done
}

next_steps() {
    log ""
    log "${C_BOLD}Next steps${C_RESET}"
    log "  1. Place on the servers:   ./blade.sh ${ENV_NAME}  -> install TLS ('t')"
    log "                             (configure/nmdomain also place them automatically)."
    log "  2. Trust the CA on any box that CALLS this env over t3s/https that isn't"
    log "     already covered (peers for REST; deploy.sh auto-trusts blade-trust.p12):"
    log "       keytool -importcert -cacerts -alias blade-${ENV_NAME} -file ${CERTS_DIR}/blade-ca.pem"
}

log "${C_BOLD}BLADE certs${C_RESET}"
log "  environment:  ${ENV_NAME}  (${CONF_FILE})"
log "  certs.dir:    ${CERTS_DIR}"
log "  mode:         ${MODE}"
[ "$DRY_RUN" = true ] && log "  ${C_YELLOW}** DRY RUN — no changes will be made **${C_RESET}"
log ""

case "$MODE" in
    generate) do_generate ;;
    import)   do_import ;;
    show)     do_show ;;
esac

log ""
ok "${MODE}: done"
