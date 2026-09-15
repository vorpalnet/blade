#!/usr/bin/env bash
# oci-identity-domain.sh - register BLADE with an OCI IAM identity domain.
#
#   misc/oci-identity-domain.sh <env> <domain-url> <app-name> <redirect-url> [test-user]
#
#   misc/oci-identity-domain.sh staging \
#       https://idcs-0123456789abcdef0123456789abcdef.identity.oraclecloud.com \
#       blade-recordings \
#       https://apps.example.com/blade/recordings/oidc/callback \
#       reviewer1
#
# Runs with the OCI CLI's own identity (~/.oci/config), which must be an
# administrator of the identity domain. Writes the client settings the
# framework's OidcLoginFilter reads to ~/.blade/<env>/oidc/<app>.properties,
# where deploy.sh picks them up. Nothing here touches the rig.
#
# What a BLADE deployment needs from an identity domain, and why:
#
# 1. A confidential OAuth client for the application: authorization code for
#    the browser, refresh tokens, and the password grant so an API client can
#    be exercised from a shell before there is a desktop to issue tokens.
# 2. The user's group names in the ID token as "groups". An identity domain
#    has that claim built in, tied to the "groups" scope, so the settings ask
#    for "openid groups"; the custom-claim step below only applies to a domain
#    that lacks the built-in one.
# 3. The domain URL as the domain's issuer and its signing keys readable
#    anonymously: what any relying party expects of discovery, and what the
#    container's own provider cannot do without (each step says why).
# 4. Groups named for BLADE's roles. The applications declare their roles
#    externally defined, so a group called Reviewer is the Reviewer role and
#    a group called Admin is the Admin role, with no mapping to maintain. A
#    deployment that must keep its directory's own names maps them with
#    role.<group>=<Role> lines in the settings file instead.
# 5. A test user in the Reviewer group, so the path can be proven end to end.
#
# Re-runnable: each step looks before it creates.
set -euo pipefail

ENV_NAME="${1:?env}"; DOMAIN="${2:?domain url}"; APP="${3:?app name}"; REDIRECT="${4:?redirect url}"
TEST_USER="${5:-}"
# The test user's password: BLADE_TEST_PASSWORD in the environment, or a prompt
# when there is a terminal to prompt on. The domain's default policy wants 12
# characters or more with upper case, lower case and a digit. Without either the user step is
# skipped and said so, rather than hanging a runner that cannot answer.
TEST_PASSWORD="${BLADE_TEST_PASSWORD:-}"
DOMAIN="${DOMAIN%/}"
# The container writes the port into every request URL it rebuilds, the
# default port included, and the provider compares the callback's URL with this
# one character for character. A redirect URL without an explicit port never
# matches; https://host:443/... matches whatever port the proxy in front used.
case "$REDIRECT" in
  https://*:*) ;;
  https://*) _h="${REDIRECT#https://}"; _h="${_h%%/*}"; REDIRECT="https://${_h}:443${REDIRECT#https://${_h}}" ;;
esac
BLADE_HOME="${BLADE_HOME:-$HOME/.blade}"
OUT="${BLADE_HOME}/${ENV_NAME}/oidc/${APP}.properties"
OCI=(oci identity-domains)

say() { printf '# %s\n' "$*"; }

# --- 1. the confidential application ---------------------------------------
APP_ID=$("${OCI[@]}" apps list --endpoint "$DOMAIN" --filter "displayName eq \"${APP}\"" \
    --query 'data.resources[0].id' --raw-output 2>/dev/null || true)
if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
  say "creating confidential application ${APP}"
  CREATED=$("${OCI[@]}" app create --endpoint "$DOMAIN" \
      --schemas '["urn:ietf:params:scim:schemas:oracle:idcs:App"]' \
      --display-name "$APP" \
      --description "BLADE ${ENV_NAME}: ${APP}" \
      --based-on-template '{"value":"CustomWebAppTemplateId"}' \
      --is-o-auth-client true --client-type confidential \
      --allowed-grants '["authorization_code","refresh_token","password"]' \
      --redirect-uris "[\"${REDIRECT}\"]" \
      --post-logout-redirect-uris "[\"${REDIRECT%/oidc/callback}/\"]" \
      --allow-offline true --bypass-consent true --active true)
  APP_ID=$(printf '%s' "$CREATED" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d["id"])')
  CLIENT_ID=$(printf '%s' "$CREATED" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d["name"])')
  CLIENT_SECRET=$(printf '%s' "$CREATED" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d.get("clientSecret",""))')
else
  say "application ${APP} exists (${APP_ID})"
  CLIENT_ID=$("${OCI[@]}" app get --endpoint "$DOMAIN" --app-id "$APP_ID" --query 'data.name' --raw-output)
  CLIENT_SECRET=$("${OCI[@]}" app get --endpoint "$DOMAIN" --app-id "$APP_ID" --attributes clientSecret \
      --query 'data."client-secret"' --raw-output 2>/dev/null || true)
  # An application registered with an earlier form of the redirect URL keeps
  # it; the current one is added beside it, since the provider sends exactly
  # what the properties file says and the domain accepts only what is listed.
  if ! "${OCI[@]}" app get --endpoint "$DOMAIN" --app-id "$APP_ID" --query 'data."redirect-uris"' 2>/dev/null \
        | grep -qF "\"${REDIRECT}\""; then
    say "adding redirect URL ${REDIRECT} to ${APP}"
    "${OCI[@]}" app patch --endpoint "$DOMAIN" --app-id "$APP_ID" \
        --schemas '["urn:ietf:params:scim:api:messages:2.0:PatchOp"]' \
        --operations "[{\"op\":\"add\",\"path\":\"redirectUris\",\"value\":[\"${REDIRECT}\"]}]" >/dev/null \
        || say "could not add the redirect URL; add it to ${APP} in the console"
  fi
fi
# an app created inactive stays unusable until activated
"${OCI[@]}" app-status-changer put --endpoint "$DOMAIN" --app-status-changer-id "$APP_ID" \
    --schemas '["urn:ietf:params:scim:schemas:oracle:idcs:AppStatusChanger"]' --active true >/dev/null 2>&1 || true

# --- 2. the groups claim -----------------------------------------------------
if ! oci raw-request --http-method GET --target-uri "${DOMAIN}/admin/v1/CustomClaims" 2>/dev/null \
      | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; sys.exit(0 if any(c.get("name")=="groups" for c in d.get("Resources",[])) else 1)'; then
  say "adding the groups custom claim to ID tokens"
  oci raw-request --http-method POST --target-uri "${DOMAIN}/admin/v1/CustomClaims" \
      --request-headers '{"Content-Type":"application/json","Accept":"application/json"}' \
      --request-body '{"schemas":["urn:ietf:params:scim:schemas:oracle:idcs:CustomClaim"],"name":"groups","value":"$(user.groups[*].display)","expression":true,"mode":"always","tokenType":"IT","allScopes":true}' \
      | python3 -c 'import sys,json; d=json.load(sys.stdin); r=d.get("data",{}); print("#   claim", r.get("name"), "id", r.get("id")) if r.get("id") else print("#   groups is a built-in claim in this domain; it is emitted when the client asks for the groups scope" if "duplicate" in json.dumps(r) else "#   claim NOT created; service said: " + json.dumps(r)[:600])'
else
  say "groups custom claim exists"
fi

# --- 2b. the issuer ----------------------------------------------------------
# A new identity domain advertises the global https://identity.oraclecloud.com/
# as its issuer, in its discovery document and in every token, while the
# discovery document itself is served only from the domain's own URL. The
# container's provider resolves the identity provider by issuer alone: it
# fetches <issuer>/.well-known/openid-configuration and requires the document
# to name that same issuer. With the global issuer that fetch has nowhere to
# go, and with the domain URL the document disagrees, so the provider refuses
# either way ("The returned issuer doesn't match the expected"). The domain's
# settings carry an issuer attribute for exactly this; setting it to the
# domain URL makes discovery and tokens agree with the client configuration.
# The domain records the previous issuer alongside, so tokens issued before
# the change keep verifying.
CUR_ISSUER=$("${OCI[@]}" setting get --endpoint "$DOMAIN" --setting-id Settings --query 'data.issuer' --raw-output 2>/dev/null || true)
if [ "$CUR_ISSUER" != "$DOMAIN" ]; then
  say "setting the domain's issuer to ${DOMAIN} (was ${CUR_ISSUER:-the global default})"
  "${OCI[@]}" setting patch --endpoint "$DOMAIN" --setting-id Settings \
      --schemas '["urn:ietf:params:scim:api:messages:2.0:PatchOp"]' \
      --operations "[{\"op\":\"replace\",\"path\":\"issuer\",\"value\":\"${DOMAIN}\"}]" \
      --query 'data.issuer' --raw-output | sed 's/^/#   issuer now /'
else
  say "issuer is the domain URL"
fi

# --- 2c. the signing keys --------------------------------------------------
# The provider verifies an ID token against the keys at the discovery
# document's jwks_uri, fetched anonymously, as every OpenID relying party
# does. A new identity domain answers that URL with 401 unless its
# signing-certificate public-access setting is on ("Could not retrieve Java
# Web Key Set" in the server log). The keys are public material; the switch
# only says so.
if [ "$("${OCI[@]}" setting get --endpoint "$DOMAIN" --setting-id Settings --query 'data."signing-cert-public-access"' --raw-output 2>/dev/null)" != "true" ]; then
  say "making the domain's signing certificate publicly readable (jwks_uri)"
  "${OCI[@]}" setting patch --endpoint "$DOMAIN" --setting-id Settings \
      --schemas '["urn:ietf:params:scim:api:messages:2.0:PatchOp"]' \
      --operations '[{"op":"replace","path":"signingCertPublicAccess","value":true}]' \
      --query 'data."signing-cert-public-access"' --raw-output | sed 's/^/#   public access now /'
else
  say "signing certificate is publicly readable"
fi

# --- 3. groups named for the roles ------------------------------------------
group_id() {
  "${OCI[@]}" groups list --endpoint "$DOMAIN" --filter "displayName eq \"$1\"" \
      --query 'data.resources[0].id' --raw-output 2>/dev/null | grep -v '^null$' || true
}
for g in Admin Reviewer; do
  if [ -z "$(group_id "$g")" ]; then
    say "creating group ${g}"
    "${OCI[@]}" group create --endpoint "$DOMAIN" \
        --schemas '["urn:ietf:params:scim:schemas:core:2.0:Group"]' --display-name "$g" >/dev/null
  else
    say "group ${g} exists"
  fi
done

# --- 4. a test reviewer ------------------------------------------------------
if [ -n "$TEST_USER" ]; then
  USER_ID=$("${OCI[@]}" users list --endpoint "$DOMAIN" --filter "userName eq \"${TEST_USER}\"" \
      --query 'data.resources[0].id' --raw-output 2>/dev/null | grep -v '^null$' || true)
  if [ -z "$USER_ID" ]; then
    if [ -z "$TEST_PASSWORD" ] && [ -t 0 ]; then
      read -r -s -p "password for ${TEST_USER}: " TEST_PASSWORD; echo
    fi
    if [ -z "$TEST_PASSWORD" ]; then
      say "no password for ${TEST_USER} (set BLADE_TEST_PASSWORD); user not created"
    else
      say "creating user ${TEST_USER}"
      USER_ID=$("${OCI[@]}" user create --endpoint "$DOMAIN" \
          --schemas '["urn:ietf:params:scim:schemas:core:2.0:User"]' \
          --user-name "$TEST_USER" --name '{"givenName":"Rita","familyName":"Reviewer"}' \
          --emails "[{\"value\":\"${TEST_USER}@example.com\",\"type\":\"work\",\"primary\":true}]" \
          --password "$TEST_PASSWORD" --query 'data.id' --raw-output)
    fi
  else
    say "user ${TEST_USER} exists"
  fi
  if [ -n "$USER_ID" ]; then
    RID=$(group_id Reviewer)
    say "adding ${TEST_USER} to Reviewer"
    "${OCI[@]}" group patch --endpoint "$DOMAIN" --group-id "$RID" \
        --schemas '["urn:ietf:params:scim:api:messages:2.0:PatchOp"]' \
        --operations "[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"${USER_ID}\",\"type\":\"User\"}]}]" >/dev/null \
        || say "could not add ${TEST_USER} to Reviewer; add the membership in the console"
  fi
fi

# --- 5. the client settings the container's provider reads ------------------
mkdir -p "$(dirname "$OUT")"; chmod 700 "$(dirname "$OUT")"
umask 077
cat > "$OUT" <<EOF
# ${APP} on ${ENV_NAME}: WEB-INF/blade-oidc.properties, added by deploy.sh.
# Read by the framework's OidcLoginFilter. An identity domain emits the groups
# claim only when the groups scope is requested.
issuer=${DOMAIN}
clientId=${CLIENT_ID}
clientSecret=${CLIENT_SECRET}
redirectUrl=${REDIRECT}
scope=openid groups
groupsClaim=groups
EOF
if [ -n "$CLIENT_SECRET" ]; then SECRET_STATE=present; else SECRET_STATE="MISSING: read it from the console and fill it in"; fi
say "wrote ${OUT} (client id ${CLIENT_ID}; secret ${SECRET_STATE})"
say "next: ./deploy.sh ${ENV_NAME} ${APP}.war (the file rides into the WAR as WEB-INF/blade-oidc.properties)"
