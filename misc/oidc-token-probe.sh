#!/usr/bin/env bash
# oidc-token-probe.sh - what an identity provider actually puts in its tokens.
#
#   misc/oidc-token-probe.sh <env> <app> <user> <password> [scope ...]
#   misc/oidc-token-probe.sh ashburn blade-recordings reviewer1 '...' openid
#   misc/oidc-token-probe.sh ashburn blade-recordings reviewer1 '...' openid groups
#
# Uses the client settings deploy.sh injects (~/.blade/<env>/oidc/<app>.properties)
# and the password grant, which the application allows for exactly this: seeing
# the claims before anything depends on them. Prints the decoded ID token and
# access token payloads, and says whether a `groups` claim is present and what
# shape it has, because the container's OpenID Connect provider requests only
# `openid`, reads a claim named `groups`, and needs it to be a JSON array.
set -euo pipefail
ENV_NAME="${1:?env}"; APP="${2:?app}"; USER_NAME="${3:?user}"; PASSWORD="${4:?password}"; shift 4
SCOPE="${*:-openid}"
P="${BLADE_HOME:-$HOME/.blade}/${ENV_NAME}/oidc/${APP}.properties"
[ -f "$P" ] || { echo "no $P" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$P" | tail -1; }
ISSUER=$(prop issuer); CLIENT_ID=$(prop clientId); CLIENT_SECRET=$(prop clientSecret)
TOKEN_URL=$(curl -sS "${ISSUER%/}/.well-known/openid-configuration" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token_endpoint"])')

RESP=$(curl -sS -u "${CLIENT_ID}:${CLIENT_SECRET}" -d grant_type=password \
    --data-urlencode "username=${USER_NAME}" --data-urlencode "password=${PASSWORD}" \
    --data-urlencode "scope=${SCOPE}" "$TOKEN_URL")

printf '%s' "$RESP" | python3 - "$SCOPE" <<'EOF'
import sys, json, base64
scope = sys.argv[1]
raw = sys.stdin.read()
try:
    r = json.loads(raw)
except ValueError:
    print("token endpoint did not answer with JSON (scope %r); first 600 bytes:" % scope)
    print(raw[:600])
    sys.exit(1)
if "error" in r:
    print("token endpoint refused (scope %r): %s" % (scope, json.dumps(r)))
    sys.exit(1)
def payload(t):
    p = t.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))
for name in ("id_token", "access_token"):
    if name not in r:
        print("== %s: not issued for scope %r" % (name, scope)); continue
    c = payload(r[name])
    print("== %s (scope %r)" % (name, scope))
    for k in sorted(c):
        v = c[k]
        print("   %-18s %s" % (k, json.dumps(v)[:120]))
    g = c.get("groups")
    if g is None:
        print("   -> no `groups` claim")
    elif isinstance(g, list) and all(isinstance(x, str) for x in g):
        print("   -> `groups` is an array of %d string(s): usable by the container's provider" % len(g))
    else:
        print("   -> `groups` is %s: NOT the string array the container's provider parses" % type(g).__name__)
EOF
