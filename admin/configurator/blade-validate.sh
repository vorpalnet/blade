#!/usr/bin/env bash
# ============================================================================
# blade-validate.sh - Validate and deploy BLADE configuration files
#
# Usage:
#   blade-validate.sh [options]
#
# Options:
#   --deploy           Validate and deploy (propagate + reload via JMX)
#   --app <name>       Target a specific app (default: all apps)
#   --host <host:port> AdminServer host (default: localhost:7001)
#   --user <user>      WebLogic username (default: weblogic)
#   --password <pass>  WebLogic password (required)
#   --context <path>   Configurator context root (default: vorpal-blade-admin-configurator)
#
# Examples:
#   blade-validate.sh --password secret
#   blade-validate.sh --app proxy-registrar --password secret
#   blade-validate.sh --deploy --password secret
#   blade-validate.sh --deploy --app transfer --host admin.example.com:7001 --password secret
# ============================================================================

set -euo pipefail

HOST="localhost:7001"
USER="weblogic"
PASSWORD=""
CONTEXT="vorpal-blade-admin-configurator"
APP=""
DEPLOY=false

while [ $# -gt 0 ]; do
    case "$1" in
        --deploy)   DEPLOY=true; shift ;;
        --app)      APP="$2"; shift 2 ;;
        --host)     HOST="$2"; shift 2 ;;
        --user)     USER="$2"; shift 2 ;;
        --password) PASSWORD="$2"; shift 2 ;;
        --context)  CONTEXT="$2"; shift 2 ;;
        --help|-h)
            sed -n '2,/^# =====/p' "$0" | grep '^#' | sed 's/^# \?//'
            exit 0 ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1 ;;
    esac
done

if [ -z "$PASSWORD" ]; then
    echo "Error: --password is required" >&2
    echo "Usage: blade-validate.sh --password <pass> [--deploy] [--app <name>]" >&2
    exit 1
fi

BASE_URL="http://${HOST}/${CONTEXT}"
COOKIE_JAR=$(mktemp)
trap 'rm -f "$COOKIE_JAR"' EXIT

# Authenticate via form login
echo "Authenticating to ${HOST}..."
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -L -d "j_username=${USER}&j_password=${PASSWORD}" \
    "${BASE_URL}/j_security_check")

if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "302" ]; then
    echo "Authentication failed (HTTP ${HTTP_CODE})" >&2
    exit 1
fi
echo "Authenticated."
echo ""

# Build API path
if [ "$DEPLOY" = true ]; then
    if [ -n "$APP" ]; then
        API_PATH="api/v1/deploy/${APP}"
        METHOD="-X POST"
    else
        API_PATH="api/v1/deploy"
        METHOD="-X POST"
    fi
else
    if [ -n "$APP" ]; then
        API_PATH="api/v1/validate/${APP}"
        METHOD=""
    else
        API_PATH="api/v1/validate"
        METHOD=""
    fi
fi

# Call API
RESPONSE=$(curl -s $METHOD -b "$COOKIE_JAR" "${BASE_URL}/${API_PATH}")

# Pretty-print if python3 is available, otherwise raw output
if command -v python3 &>/dev/null; then
    echo "$RESPONSE" | python3 -m json.tool
else
    echo "$RESPONSE"
fi

# Exit with error if validation failed
if echo "$RESPONSE" | grep -q '"valid" *: *false\|"deployed" *: *false'; then
    exit 1
fi
