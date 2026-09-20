package org.vorpal.blade.framework.v3.security;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/// The OpenID Connect client settings [OidcLoginFilter] signs a browser in
/// with, read from `WEB-INF/blade-oidc.properties`.
///
/// The file is not part of the WAR in the repository: a client secret has no
/// place there. `deploy.sh` adds it from `~/.blade/<env>/oidc/<deployment-name>.properties`
/// at deploy time, so the same build signs in against a different identity
/// provider in every environment, and a WAR deployed without the file falls
/// back to the container's own login. The keys:
///
/// | key             | meaning                                                          |
/// |-----------------|------------------------------------------------------------------|
/// | `issuer`        | the `iss` every token must carry, matched exactly                |
/// | `discoveryUrl`  | where the provider's metadata lives, when it is not `<issuer>/.well-known/openid-configuration` |
/// | `clientId`, `clientSecret` | the confidential client the provider registered for this application |
/// | `redirectUrl`   | the callback, `https://<host>/<context-root>/oidc/callback`, exactly as registered with the provider |
/// | `scope`         | the scopes to request, default `openid`; an OCI identity domain emits its `groups` claim only for `openid groups` |
/// | `usernameClaim` | the claim that names the user, default `sub`                     |
/// | `groupsClaim`   | the claim that carries the user's groups, default `groups`       |
/// | `audience`      | the `aud` a bearer token must carry; unset, it must name this client (`clientId`). Set it to the API's audience when API clients send access tokens, or to `*` to skip the check |
/// | `maxSessionHours` | hours a sign-in lasts before the provider is asked again, however active the session; default 12 |
/// | `role.<group>`  | maps a group name from the provider to a BLADE role, for a directory that must keep its own names; a group already named for a role needs no entry |
///
/// `discoveryUrl` exists for the provider whose discovery document is served
/// from one URL while naming another as issuer. An OCI identity domain does
/// that out of the box: discovery lives under the domain's own URL and names
/// the global `https://identity.oraclecloud.com/`. The container's provider
/// cannot follow that split, which is one of the reasons this filter exists.
public final class OidcLoginConfig {

	/// Where [OidcLoginFilter] reads the settings from, relative to the WAR.
	public static final String RESOURCE = "/WEB-INF/blade-oidc.properties";

	private static final String WELL_KNOWN = "/.well-known/openid-configuration";

	private final String issuer;
	private final String discoveryUrl;
	private final String clientId;
	private final String clientSecret;
	private final String redirectUrl;
	private final String scope;
	private final String usernameClaim;
	private final String groupsClaim;
	private final String audience;
	private final int maxSessionHours;
	private final Map<String, String> roleMappings;

	private OidcLoginConfig(Properties p) {
		this.issuer = required(p, "issuer");
		this.clientId = required(p, "clientId");
		this.clientSecret = required(p, "clientSecret");
		this.redirectUrl = required(p, "redirectUrl");
		String discovery = trimmed(p, "discoveryUrl");
		this.discoveryUrl = (discovery != null) ? discovery : stripSlash(issuer) + WELL_KNOWN;
		String s = trimmed(p, "scope");
		this.scope = (s != null) ? s : "openid";
		String u = trimmed(p, "usernameClaim");
		this.usernameClaim = (u != null) ? u : "sub";
		String g = trimmed(p, "groupsClaim");
		this.groupsClaim = (g != null) ? g : "groups";
		this.audience = trimmed(p, "audience");
		String hours = trimmed(p, "maxSessionHours");
		this.maxSessionHours = (hours != null) ? Integer.parseInt(hours) : 12;
		Map<String, String> roles = new LinkedHashMap<>();
		for (String key : p.stringPropertyNames()) {
			if (key.startsWith("role.") && key.length() > 5) {
				String value = p.getProperty(key).trim();
				if (!value.isEmpty()) {
					roles.put(key.substring(5), value);
				}
			}
		}
		this.roleMappings = roles;
	}

	/// Parse a properties file. Throws when a required key is missing, so a
	/// half-written file fails the deployment loudly rather than signing
	/// nobody in.
	public static OidcLoginConfig parse(Properties p) {
		return new OidcLoginConfig(p);
	}

	/// Read the settings from a stream, or return null for a null stream, which
	/// is what a WAR deployed without the file yields.
	public static OidcLoginConfig read(InputStream in) throws IOException {
		if (in == null) {
			return null;
		}
		Properties p = new Properties();
		try (InputStream s = in) {
			p.load(s);
		}
		return parse(p);
	}

	public String issuer() {
		return issuer;
	}

	public String discoveryUrl() {
		return discoveryUrl;
	}

	public String clientId() {
		return clientId;
	}

	public String clientSecret() {
		return clientSecret;
	}

	public String redirectUrl() {
		return redirectUrl;
	}

	public String scope() {
		return scope;
	}

	public String usernameClaim() {
		return usernameClaim;
	}

	public String groupsClaim() {
		return groupsClaim;
	}

	public String audience() {
		return audience;
	}

	public int maxSessionHours() {
		return maxSessionHours;
	}

	public Map<String, String> roleMappings() {
		return roleMappings;
	}

	/// The path part of the redirect URL, which is what an incoming request is
	/// compared against: the scheme, host and port a proxy presents to the
	/// browser are not what the server sees, and they are not what makes the
	/// callback the callback.
	public String callbackPath() {
		String url = redirectUrl;
		int scheme = url.indexOf("://");
		int path = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
		if (path < 0) {
			return "/";
		}
		int query = url.indexOf('?', path);
		return (query < 0) ? url.substring(path) : url.substring(path, query);
	}

	/// The [JwtAuthConfig] the shared validator needs to check an ID token: the
	/// configured issuer, this client as the audience, and the user and group
	/// claims and role mappings from the file. The JWKS URI is the provider's,
	/// found by discovery.
	public JwtAuthConfig idTokenConfig(String jwksUri) {
		JwtAuthConfig cfg = baseConfig(jwksUri);
		cfg.setAudience(clientId);
		return cfg;
	}

	/// The [JwtAuthConfig] for a bearer token from an API client: the `audience`
	/// key when set, otherwise this client's id, and no check only for an
	/// explicit `*`.
	///
	/// Skipping the check by default let any application registered with the
	/// same identity provider replay a user's token, minted for itself, as an
	/// admin credential here; the issuer check alone does not tell BLADE's
	/// tokens from theirs.
	public JwtAuthConfig bearerConfig(String jwksUri) {
		JwtAuthConfig cfg = baseConfig(jwksUri);
		if (audience == null) {
			cfg.setAudience(clientId);
		} else if (!"*".equals(audience)) {
			cfg.setAudience(audience);
		}
		return cfg;
	}

	private JwtAuthConfig baseConfig(String jwksUri) {
		JwtAuthConfig cfg = new JwtAuthConfig();
		cfg.setEnabled(true);
		cfg.setIssuer(issuer);
		cfg.setJwksUri(jwksUri);
		cfg.setUsernameClaim(usernameClaim);
		cfg.setRolesClaim(groupsClaim);
		cfg.setRoleMappings(new LinkedHashMap<>(roleMappings));
		return cfg;
	}

	private static String required(Properties p, String key) {
		String v = trimmed(p, key);
		if (v == null) {
			throw new IllegalArgumentException(RESOURCE + ": '" + key + "' is required");
		}
		return v;
	}

	private static String trimmed(Properties p, String key) {
		String v = p.getProperty(key);
		if (v == null) {
			return null;
		}
		v = v.trim();
		return v.isEmpty() ? null : v;
	}

	private static String stripSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	@Override
	public String toString() {
		return "OidcLoginConfig[issuer=" + issuer + ", clientId=" + clientId + ", redirectUrl=" + redirectUrl
				+ ", scope=" + scope + "]";
	}
}
