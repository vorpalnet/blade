# configure-oidc.py - add the container's OpenID Connect provider to the realm.
#
# This is the alternative to the framework's own OidcLoginFilter, for a
# deployment whose identity provider the container's provider was built for
# (the vendor tested Keycloak and Azure). It does not work with an OCI identity
# domain: see IAM.md, "The container's own provider, and why it is not the
# default". An application opts in with provider=container in its settings
# file, which deploy.sh then places as WEB-INF/oidcAuth.properties.
#
# WLST, online, ASCII only (Jython 2). Run on the admin box as the domain owner:
#
#   $ORACLE_HOME/oracle_common/common/bin/wlst.sh configure-oidc.py \
#       t3://localhost:7001 weblogic '<password>'|- [UserNameTokenClaim] [debug-server|-debug-server]
#
# A password of '-' is read from BLADE_WLS_PASSWORD instead of the command line.
#
# What it does, and why each step:
#
# 1. Creates an OIDCIdentityAsserter in the default realm if there is none.
#    OCCAS 8.3 (WebLogic 14.1.2) ships it in mbeantypes/oidc-identity-asserter.jar.
#    The provider is a full relying party: it runs the authorization-code flow
#    itself, discovers the provider's endpoints, caches its keys, and asserts
#    identity from the ID token. It is also a servlet authentication filter, so
#    the redirect happens whatever a web application's login-config says.
#
# 2. Sets UserNameTokenClaim. The provider's default is "upn", which an OCI
#    identity domain does not emit; "sub" carries the login id there.
#    VirtualUserAllowed stays true: a federated user needs no account in the
#    embedded directory, which is the whole point.
#
# 3. Makes the DefaultAuthenticator SUFFICIENT. At REQUIRED it demands that
#    every asserted user also exist in the embedded directory, which turns
#    every federated login into a failure with a misleading message.
#
# Which application uses the provider is decided per application, by its
# WEB-INF/oidcAuth.properties (issuer, clientId, clientSecret, redirectUrl);
# deploy.sh adds that file from ~/.blade/<env>/oidc/<app>.properties.
#
# A realm change takes effect on restart: the AdminServer and every managed
# server. The script says so at the end and does not restart anything.
import sys

if len(sys.argv) < 4:
    print "usage: wlst.sh configure-oidc.py <adminurl> <user> <password> [UserNameTokenClaim] [debug-server]"
    sys.exit(2)

url, user, password = sys.argv[1], sys.argv[2], sys.argv[3]
if password == '-':   # keep it off the command line: BLADE_WLS_PASSWORD in the environment
    import os
    password = os.environ.get('BLADE_WLS_PASSWORD', '')
userClaim = (len(sys.argv) > 4 and sys.argv[4]) or 'sub'
debugServer = (len(sys.argv) > 5 and sys.argv[5]) or ''   # a server name: turn on its authentication debug; -name turns it off
debugOn = not debugServer.startswith('-')
debugServer = debugServer.lstrip('-')
PROVIDER = 'OIDCIdentityAsserter'
TYPE = 'weblogic.security.providers.authentication.OIDCIdentityAsserter'

connect(user, password, url)
edit()
startEdit()
try:
    realm = cmo.getSecurityConfiguration().getDefaultRealm()
    oidc = realm.lookupAuthenticationProvider(PROVIDER)
    if oidc is None:
        oidc = realm.createAuthenticationProvider(PROVIDER, TYPE)
        print 'created', PROVIDER
    else:
        print 'found', PROVIDER
    oidc.setUserNameTokenClaim(userClaim)
    oidc.setVirtualUserAllowed(true)
    dflt = realm.lookupAuthenticationProvider('DefaultAuthenticator')
    if dflt is not None and dflt.getControlFlag() != 'SUFFICIENT':
        dflt.setControlFlag('SUFFICIENT')
        print 'DefaultAuthenticator control flag -> SUFFICIENT'
    if debugServer:
        # The provider says what it did with the token only at debug level:
        # "Obtained user name from JWT", "Error getting groups from token",
        # "ID token contains invalid groups object". Worth having on while a
        # new identity provider is being proven, and off afterwards.
        cd('/Servers/%s/ServerDebug/%s' % (debugServer, debugServer))
        cmo.setDebugSecurityAtn(debugOn)
        print 'authentication debug', (debugOn and 'on' or 'off'), 'for', debugServer, '(DebugSecurityAtn)'
    save()
    activate(block='true')
    print 'activated: %s UserNameTokenClaim=%s VirtualUserAllowed=true' % (PROVIDER, userClaim)
    print 'restart the AdminServer and the managed servers for the realm change to take effect'
except:
    print 'failed:', sys.exc_info()[1]
    stopEdit('y')
    sys.exit(1)
disconnect()
