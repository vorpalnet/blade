#!/usr/bin/env bash
# ============================================================================
# make-certs.sh - Generate the TLS material for a BLADE/OCCAS environment.
#
# Produces a PKCS12 identity keystore (server private key + cert chain) and a
# PKCS12 trust keystore (the CA, plus the SBC's CA when SIP mTLS is enabled),
# from a private CA this script creates and then reuses on later runs.
#
# One identity cert with a SAN list covering every node (short name, FQDN, both
# IP planes, localhost) serves ALL listeners — admin HTTPS, t3s, and SIP TLS.
#
# Usage:
#   ./tls/make-certs.sh <env> [--new-ca] [--csr-only] [--force]
#
#   <env>        a NAME → ../build-profiles/deploy/<name>.conf (+ <name>.secret)
#                or a PATH to a .conf file directly
#   --new-ca     regenerate the CA even if one already exists (INVALIDATES every
#                cert previously issued by the old CA — clients must re-trust)
#   --csr-only   stop after writing the identity CSR; do NOT self-sign. Hand the
#                CSR to a real CA, then drop the signed chain back in and re-run
#                without --csr-only to assemble the keystores (see README.md).
#   --force      overwrite an existing identity/trust keystore without asking
#
# Reads from the env conf:
#   tls.san              CSV of SAN entries, keytool form: dns:host,ip:1.2.3.4
#   tls.ca.cn            CA subject CN              (default "BLADE Internal CA")
#   tls.identity.cn      identity cert subject CN   (default first dns: in SAN)
#   tls.identity.alias   key alias                  (default blade-identity)
#   tls.validity.days    leaf cert validity         (default 825)
#   tls.key.size         RSA key size               (default 2048)
#   sip.tls.twoway       true → import the SBC CA into the trust store
#   sbc.ca.cert          path to the SBC's CA cert (PEM), required if twoway
#
# Reads from the env secret (../build-profiles/deploy/<env>.secret):
#   tls.ca.passphrase         protects the CA keystore (KEEP OFFLINE)
#   tls.keystore.passphrase   protects the identity keystore + private key
#   tls.trust.passphrase      protects the trust keystore
# Missing passphrases are prompted for (read -s).
#
# Output (gitignored):  tls/out/<env>/
#   blade-ca.p12        the CA keystore — KEEP OFFLINE, never deployed
#   blade-ca.pem        the CA public cert — import into browsers / clients
#   blade-identity.p12  deploy to every node (install-ssl.sh keystores)
#   blade-trust.p12     deploy to every node (install-ssl.sh keystores)
#   blade-identity.csr  the CSR (kept for the record / real-CA path)
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEPLOY_DIR="${REPO_ROOT}/build-profiles/deploy"

CA_ALIAS="blade-ca"
NEW_CA=false
CSR_ONLY=false
FORCE=false
ENV_ARG=""

for arg in "$@"; do
    case "$arg" in
        --new-ca)   NEW_CA=true ;;
        --csr-only) CSR_ONLY=true ;;
        --force)    FORCE=true ;;
        -h|--help)  sed -n '2,60p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)         echo "Unknown option: $arg" >&2; exit 1 ;;
        *)          [ -z "$ENV_ARG" ] && ENV_ARG="$arg" || { echo "Unexpected argument: $arg" >&2; exit 1; } ;;
    esac
done

[ -n "$ENV_ARG" ] || { echo "Usage: ./tls/make-certs.sh <env> [--new-ca] [--csr-only] [--force]" >&2; exit 1; }

# --- Resolve <env> to conf + secret (name or path), mirroring deploy.sh ---
if [ -f "$ENV_ARG" ]; then
    CONF_FILE="$ENV_ARG"; ENV_NAME="$(basename "${ENV_ARG%.conf}")"
    SECRET_FILE="${ENV_ARG%.conf}.secret"
else
    ENV_NAME="$ENV_ARG"
    CONF_FILE="${DEPLOY_DIR}/${ENV_NAME}.conf"
    SECRET_FILE="${DEPLOY_DIR}/${ENV_NAME}.secret"
fi
[ -f "$CONF_FILE" ] || { echo "Conf not found: ${CONF_FILE}" >&2; exit 1; }

read_prop() {
    local file="$1" key="$2"
    { grep "^${key}=" "$file" 2>/dev/null || true; } | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

SAN=$(read_prop          "$CONF_FILE" "tls.san")
CA_CN=$(read_prop        "$CONF_FILE" "tls.ca.cn");           CA_CN="${CA_CN:-BLADE Internal CA}"
ID_CN=$(read_prop        "$CONF_FILE" "tls.identity.cn")
ID_ALIAS=$(read_prop     "$CONF_FILE" "tls.identity.alias");  ID_ALIAS="${ID_ALIAS:-blade-identity}"
VALIDITY=$(read_prop     "$CONF_FILE" "tls.validity.days");   VALIDITY="${VALIDITY:-825}"
KEYSIZE=$(read_prop      "$CONF_FILE" "tls.key.size");        KEYSIZE="${KEYSIZE:-2048}"
SIP_TWOWAY=$(read_prop   "$CONF_FILE" "sip.tls.twoway")
SBC_CA_CERT=$(read_prop  "$CONF_FILE" "sbc.ca.cert")

[ -n "$SAN" ] || { echo "${CONF_FILE}: missing tls.san (the SAN list)" >&2; exit 1; }
# Default the identity CN to the first dns: entry in the SAN, else the first entry.
if [ -z "$ID_CN" ]; then
    ID_CN=$(printf '%s' "$SAN" | tr ',' '\n' | sed 's/^[[:space:]]*//' | grep -i '^dns:' | head -1 | cut -d: -f2)
    [ -n "$ID_CN" ] || ID_CN=$(printf '%s' "$SAN" | tr ',' '\n' | head -1 | cut -d: -f2)
fi

# --- Passphrases: env > secret file > prompt ---
get_secret() {  # $1=env-var-name $2=secret-key $3=prompt-label
    local v="${!1:-}"
    [ -z "$v" ] && [ -f "$SECRET_FILE" ] && v=$(read_prop "$SECRET_FILE" "$2")
    if [ -z "$v" ]; then
        read -rs -p "$3: " v; echo >&2
        [ -n "$v" ] || { echo "No value for $3" >&2; exit 1; }
    fi
    printf '%s' "$v"
}
CA_PASS=$(get_secret      BLADE_TLS_CA_PASS       "tls.ca.passphrase"       "CA keystore passphrase")
KS_PASS=$(get_secret      BLADE_TLS_KEYSTORE_PASS "tls.keystore.passphrase" "Identity keystore passphrase")
TRUST_PASS=$(get_secret   BLADE_TLS_TRUST_PASS    "tls.trust.passphrase"    "Trust keystore passphrase")

if [ "$SIP_TWOWAY" = "true" ]; then
    [ -n "$SBC_CA_CERT" ] || { echo "sip.tls.twoway=true but sbc.ca.cert is unset — need the SBC's CA cert for mTLS." >&2; exit 1; }
    [ -f "$SBC_CA_CERT" ] || { echo "sbc.ca.cert not found: ${SBC_CA_CERT}" >&2; exit 1; }
fi

OUTDIR="${SCRIPT_DIR}/out/${ENV_NAME}"
mkdir -p "$OUTDIR"
CA_P12="${OUTDIR}/blade-ca.p12"
CA_PEM="${OUTDIR}/blade-ca.pem"
ID_P12="${OUTDIR}/blade-identity.p12"
TRUST_P12="${OUTDIR}/blade-trust.p12"
CSR="${OUTDIR}/blade-identity.csr"
SIGNED="${OUTDIR}/blade-identity-signed.pem"

echo "make-certs: env=${ENV_NAME}"
echo "  SAN:        ${SAN}"
echo "  identity CN:${ID_CN}   alias=${ID_ALIAS}   ${KEYSIZE}-bit RSA   ${VALIDITY}d"
echo "  SBC mTLS:   ${SIP_TWOWAY:-false}${SBC_CA_CERT:+  (SBC CA: ${SBC_CA_CERT})}"
echo "  output:     ${OUTDIR}/"
echo ""

# --- 1. CA (create once, then reuse) -----------------------------------------
if [ "$NEW_CA" = true ] && [ -f "$CA_P12" ]; then
    echo "==> --new-ca: removing existing CA (every prior cert becomes untrusted)"
    rm -f "$CA_P12" "$CA_PEM"
fi
if [ -f "$CA_P12" ]; then
    echo "==> Reusing existing CA: ${CA_P12}"
else
    echo "==> Creating private CA"
    keytool -genkeypair -alias "$CA_ALIAS" -keyalg RSA -keysize "$KEYSIZE" \
        -dname "CN=${CA_CN}" -validity 3650 \
        -ext "bc:c=ca:true,pathlen:0" -ext "ku:c=keyCertSign,cRLSign" \
        -keystore "$CA_P12" -storetype PKCS12 \
        -storepass "$CA_PASS" -keypass "$CA_PASS"
fi
# (Re)export the CA public cert for clients/browsers.
keytool -exportcert -rfc -alias "$CA_ALIAS" -keystore "$CA_P12" \
    -storepass "$CA_PASS" -file "$CA_PEM" >/dev/null
echo "    CA cert → ${CA_PEM}"

# --- 2. Identity keypair + CSR ------------------------------------------------
if [ -f "$ID_P12" ] && [ "$FORCE" != true ]; then
    echo "==> ${ID_P12} exists. Use --force to overwrite." >&2; exit 1
fi
rm -f "$ID_P12"
echo "==> Generating identity keypair"
keytool -genkeypair -alias "$ID_ALIAS" -keyalg RSA -keysize "$KEYSIZE" \
    -dname "CN=${ID_CN}" -validity "$VALIDITY" -ext "san=${SAN}" \
    -keystore "$ID_P12" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KS_PASS"
keytool -certreq -alias "$ID_ALIAS" -keystore "$ID_P12" \
    -storepass "$KS_PASS" -file "$CSR"
echo "    CSR → ${CSR}"

if [ "$CSR_ONLY" = true ]; then
    echo ""
    echo "--csr-only: stopping. Send ${CSR} to your CA, save the signed chain as"
    echo "  ${SIGNED}, then re-run WITHOUT --csr-only to assemble the keystores."
    exit 0
fi

# --- 3. Sign the identity with our CA (skip if a real-CA chain is present) ----
if [ -f "$SIGNED" ]; then
    echo "==> Using existing signed chain: ${SIGNED} (real-CA path)"
else
    echo "==> Signing identity with private CA"
    keytool -gencert -alias "$CA_ALIAS" -keystore "$CA_P12" -storepass "$CA_PASS" \
        -infile "$CSR" -outfile "$SIGNED" -validity "$VALIDITY" -rfc \
        -ext "san=${SAN}" \
        -ext "ku=digitalSignature,keyEncipherment" \
        -ext "eku=serverAuth,clientAuth" \
        -ext "bc=ca:false"
fi

# --- 4. Import the chain back into the identity keystore ----------------------
# Import the CA first so the reply's chain validates, then the signed leaf
# (same alias → replaces the self-signed cert with the CA-issued chain).
echo "==> Assembling identity chain"
keytool -importcert -noprompt -alias "$CA_ALIAS" -file "$CA_PEM" \
    -keystore "$ID_P12" -storepass "$KS_PASS" >/dev/null
keytool -importcert -noprompt -alias "$ID_ALIAS" -file "$SIGNED" \
    -keystore "$ID_P12" -storepass "$KS_PASS" >/dev/null

# --- 5. Trust store: our CA, plus the SBC CA when mTLS is on -----------------
echo "==> Building trust store"
rm -f "$TRUST_P12"
keytool -importcert -noprompt -alias "$CA_ALIAS" -file "$CA_PEM" \
    -keystore "$TRUST_P12" -storetype PKCS12 -storepass "$TRUST_PASS" >/dev/null
if [ "$SIP_TWOWAY" = "true" ]; then
    keytool -importcert -noprompt -alias "sbc-ca" -file "$SBC_CA_CERT" \
        -keystore "$TRUST_P12" -storetype PKCS12 -storepass "$TRUST_PASS" >/dev/null
    echo "    + SBC CA imported (alias sbc-ca) for SIP mTLS"
fi

chmod 600 "$CA_P12" "$ID_P12" "$TRUST_P12" 2>/dev/null || true

echo ""
echo "Done. Generated in ${OUTDIR}/:"
echo "  blade-ca.p12        CA keystore — KEEP OFFLINE, never deploy"
echo "  blade-ca.pem        import into browsers / clients to trust BLADE certs"
echo "  blade-identity.p12  identity (alias ${ID_ALIAS}) → deploy to every node"
echo "  blade-trust.p12     trust store → deploy to every node"
echo ""
echo "Next:  ./tls/install-ssl.sh ${ENV_NAME} --dry-run"
