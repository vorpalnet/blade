#!/usr/bin/env bash
# ============================================================================
# certs.sh - TLS keystore tooling for OCCAS/BLADE environments.
#
# Produces the two keystores every server needs — identity (its cert + key)
# and trust (the CA set) — either self-generated for test rigs, or packaged
# from customer-issued material (e.g. a corporate certificate process that
# hands you PEM or PKCS12 files). The output feeds:
#
#   ./install-occas.sh <env> secure     wire the keystores + SSL ports into
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
# Conf keys (build-profiles/occas/<env>.conf):
#   certs.dir     output directory — default ~/.blade/certs/<env>
#                 NEVER a path inside the repo; keys must not be committable.
#   certs.hosts   extra SAN entries (CSV of dns:/ip: or bare hostnames)
#   cert.import.* see import above
# Secrets (<env>.secret or environment):
#   store.password / $BLADE_STORE_PASSWORD — one password for all stores +
#                 keys (WebLogic wants keystore pass = key pass anyway).
#
# Files produced in certs.dir:
#   ca.p12            test CA key (generate only — guard it like a password)
#   ca.pem            CA certificate, PEM — import this into client browsers,
#                     the JVM truststore of callers, and hand to peers
#   identity.p12      server identity (cert + key) → CustomIdentityKeyStore
#   trust.p12         trusted CAs                  → CustomTrustKeyStore
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

# --- Resolve conf + secret (same convention as install-occas.sh) ---
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"; ENV_NAME="$(basename "${ENV_ARG%.conf}")"; SECRET_FILE="${ENV_ARG%.conf}.secret"
else
    ENV_NAME="$ENV_ARG"; CONF_FILE="${OCCAS_DIR}/${ENV_NAME}.conf"; SECRET_FILE="${OCCAS_DIR}/${ENV_NAME}.secret"
fi
[ -f "$CONF_FILE" ] || die "Conf not found: ${CONF_FILE}"
read_prop() {
    local file="$1" key="$2"
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

CERTS_DIR="$(read_prop "$CONF_FILE" "certs.dir")"; CERTS_DIR="${CERTS_DIR/#\~/$HOME}"
[ -z "$CERTS_DIR" ] && CERTS_DIR="${HOME}/.blade/certs/${ENV_NAME}"
case "$CERTS_DIR" in "$SCRIPT_DIR"/*) die "certs.dir is inside the repo (${CERTS_DIR}) — keys must not be committable. Move it out (default: ~/.blade/certs/${ENV_NAME})." ;; esac

get_store_pw() {
    local v="${BLADE_STORE_PASSWORD:-}"
    [ -z "$v" ] && [ -f "$SECRET_FILE" ] && v=$(read_prop "$SECRET_FILE" "store.password")
    if [ -z "$v" ] && [ "$DRY_RUN" = false ]; then
        read -rs -p "Keystore password for ${ENV_NAME} (stores + keys): " v; echo
        [ -n "$v" ] || die "No password provided."
    fi
    [ "${#v}" -ge 6 ] || [ "$DRY_RUN" = true ] || die "keytool requires a password of 6+ characters."
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

do_generate() {
    local san pw
    san="$(build_san)"
    info "Self-signed test PKI for '${ENV_NAME}' → ${CERTS_DIR}"
    log  "  SAN: ${san}"
    if [ "$DRY_RUN" = true ]; then
        log "${C_DIM}  [dry-run] keytool: test CA (bc:c, 10y) → ca.p12 / ca.pem${C_RESET}"
        log "${C_DIM}  [dry-run] keytool: server keypair + CA-signed cert (san, eku serverAuth+clientAuth) → identity.p12${C_RESET}"
        log "${C_DIM}  [dry-run] keytool: trust store with CA cert → trust.p12${C_RESET}"
        return 0
    fi
    pw="$(get_store_pw)"
    mkdir -p "$CERTS_DIR"; chmod 700 "$CERTS_DIR"
    [ -f "${CERTS_DIR}/identity.p12" ] && die "${CERTS_DIR}/identity.p12 exists — refusing to overwrite. Delete the directory to re-generate."

    # 1. Test CA (self-signed, CA basic constraint).
    "$KEYTOOL" -genkeypair -alias ca -keyalg RSA -keysize 3072 -validity 3650 \
        -dname "CN=BLADE Test CA (${ENV_NAME}), O=Vorpal" -ext bc:c \
        -keystore "${CERTS_DIR}/ca.p12" -storetype PKCS12 -storepass "$pw" -keypass "$pw"
    "$KEYTOOL" -exportcert -alias ca -rfc \
        -keystore "${CERTS_DIR}/ca.p12" -storepass "$pw" > "${CERTS_DIR}/ca.pem"

    # 2. Server identity, signed by the CA. EKU includes clientAuth so the
    #    same keystore serves as the client certificate for mutual TLS.
    "$KEYTOOL" -genkeypair -alias server -keyalg RSA -keysize 3072 -validity 825 \
        -dname "CN=blade-${ENV_NAME}, O=Vorpal" \
        -keystore "${CERTS_DIR}/identity.p12" -storetype PKCS12 -storepass "$pw" -keypass "$pw"
    "$KEYTOOL" -certreq -alias server \
        -keystore "${CERTS_DIR}/identity.p12" -storepass "$pw" \
    | "$KEYTOOL" -gencert -alias ca -validity 825 -rfc \
        -ext "san=${san}" -ext "ku:c=digitalSignature,keyEncipherment" -ext "eku=serverAuth,clientAuth" \
        -keystore "${CERTS_DIR}/ca.p12" -storepass "$pw" > "${CERTS_DIR}/server.pem"
    # Import chain: CA first, then the signed server cert.
    "$KEYTOOL" -importcert -alias ca -noprompt -file "${CERTS_DIR}/ca.pem" \
        -keystore "${CERTS_DIR}/identity.p12" -storepass "$pw"
    "$KEYTOOL" -importcert -alias server -file "${CERTS_DIR}/server.pem" \
        -keystore "${CERTS_DIR}/identity.p12" -storepass "$pw"

    # 3. Trust store = just the CA.
    "$KEYTOOL" -importcert -alias ca -noprompt -file "${CERTS_DIR}/ca.pem" \
        -keystore "${CERTS_DIR}/trust.p12" -storetype PKCS12 -storepass "$pw"

    chmod 600 "${CERTS_DIR}"/*.p12
    ok "Wrote ${CERTS_DIR}/{ca.p12,ca.pem,identity.p12,trust.p12}"
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
        [ -n "$p12" ]   && log "${C_DIM}  [dry-run] validate + copy ${p12} → identity.p12${C_RESET}"
        [ -n "$cert" ]  && log "${C_DIM}  [dry-run] openssl pkcs12 -export ${cert} + ${key} → identity.p12${C_RESET}"
        [ -n "$chain" ] && log "${C_DIM}  [dry-run] import ${chain} → trust.p12${C_RESET}"
        return 0
    fi
    pw="$(get_store_pw)"
    mkdir -p "$CERTS_DIR"; chmod 700 "$CERTS_DIR"

    if [ -n "$p12" ]; then
        [ -f "$p12" ] || die "cert.import.p12 not found: ${p12}"
        # Validate it opens with the store password before installing it.
        "$KEYTOOL" -list -keystore "$p12" -storetype PKCS12 -storepass "$pw" >/dev/null \
            || die "Cannot open ${p12} with the configured store.password."
        cp "$p12" "${CERTS_DIR}/identity.p12"
    else
        [ -f "$cert" ] || die "cert.import.cert not found: ${cert}"
        [ -f "$key" ]  || die "cert.import.key not found: ${key}"
        command -v openssl >/dev/null 2>&1 || die "PEM import needs openssl (PKCS12 assembly from a bare key). Ask for a .p12 instead, or install openssl."
        openssl pkcs12 -export -name server -in "$cert" -inkey "$key" \
            ${chain:+-certfile "$chain"} \
            -out "${CERTS_DIR}/identity.p12" -passout "pass:${pw}"
    fi

    if [ -n "$chain" ]; then
        [ -f "$chain" ] || die "cert.import.chain not found: ${chain}"
        rm -f "${CERTS_DIR}/trust.p12"
        # Split the chain into individual certs; import each under ca-N.
        awk -v dir="$CERTS_DIR" 'BEGIN{n=0} /BEGIN CERT/{n++} n{print > (dir "/.chain-" n ".pem")}' "$chain"
        local f i=0
        for f in "${CERTS_DIR}"/.chain-*.pem; do
            [ -f "$f" ] || die "No certificates found in ${chain}"
            i=$((i + 1))
            "$KEYTOOL" -importcert -alias "ca-${i}" -noprompt -file "$f" \
                -keystore "${CERTS_DIR}/trust.p12" -storetype PKCS12 -storepass "$pw"
            rm -f "$f"
        done
    else
        warn "No cert.import.chain — trust.p12 not (re)built. The 'secure' step needs one; point cert.import.chain at the issuing CA chain PEM."
    fi

    chmod 600 "${CERTS_DIR}"/*.p12 2>/dev/null || true
    ok "Wrote ${CERTS_DIR}/identity.p12$([ -n "$chain" ] && echo ' and trust.p12')"
    next_steps
}

do_show() {
    local pw f
    pw="$(get_store_pw)"
    for f in ca.p12 identity.p12 trust.p12; do
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
    log "  1. Wire the domain:        ./install-occas.sh ${ENV_NAME} secure"
    log "  2. Trust the CA JVM-wide on every box that CALLS this environment"
    log "     (deploy hosts for t3s, peers for REST):"
    log "       keytool -importcert -cacerts -alias blade-${ENV_NAME} -file ${CERTS_DIR}/ca.pem"
    log "  3. Point the deploy conf at the trust store (wls.adminurl=t3s://…,"
    log "     wls.truststore=${CERTS_DIR}/trust.p12) — see build-profiles/deploy/*.conf"
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
