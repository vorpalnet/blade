package org.vorpal.blade.framework.v3.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;

/// Signs a browser in with an OpenID Connect provider, and accepts bearer
/// tokens from API clients, in front of an application that would otherwise
/// rely on the container's login.
///
/// The container's own OpenID Connect provider does this job for a provider
/// that behaves like the two the vendor tested against. An OCI identity domain
/// does not: it publishes its signing key without the `use` field that
/// provider's key matcher insists on, and it emits the `groups` claim only for
/// a `groups` scope that provider never requests. Those are the two gaps this
/// filter closes, with the pieces the framework already had: [JwtValidator]
/// verifies the ID token through a key selector that accepts the key as
/// published, and the scope is whatever the properties file says.
///
/// ## Which door a request comes through
///
/// 1. `Authorization: Bearer <token>`: the token is verified and the request
///    proceeds as that identity, with no session. This is the API client's
///    path, and it needs no browser.
/// 2. A request the container has already authenticated, by form, certificate
///    or basic login, passes untouched. The container's login keeps working
///    beside this one.
/// 3. With no `WEB-INF/blade-oidc.properties` in the WAR the filter asks the
///    container to authenticate the request, which is the login the
///    descriptor's `login-config` names. The application declares no
///    `auth-constraint` of its own, so this is what keeps an unconfigured
///    deployment from answering the world.
/// 4. A session that holds a signed-in identity proceeds as that identity.
/// 5. Anything else starts a login: the return address, a state, a nonce and
///    a PKCE verifier go into the session and the browser goes to the
///    provider. A request that is plainly not a browser navigation, one that
///    does not accept HTML, gets `401` with a JSON body instead, since an
///    `XMLHttpRequest` cannot usefully follow a redirect to a login page.
///
/// The callback compares the state to the session's, trades the code for the
/// ID token with the PKCE verifier, verifies the token, checks the nonce, and
/// stores the identity. `/oidc/logout` under the application's context root
/// drops the session and, when the provider publishes one, visits its
/// end-session endpoint.
///
/// ## What the application sees
///
/// The request is wrapped so that `getUserPrincipal()` is the [JwtIdentity],
/// `getRemoteUser()` its name, and `isUserInRole(r)` true for a role the
/// token's group claim carried, mapped or verbatim. A `web.xml` role named
/// `Reviewer` is therefore held by anyone in a provider group called
/// `Reviewer`, the same reading of "externally defined" the container gives a
/// realm group. Resource code that resolves the caller through
/// [SubjectAttributes#of(JwtIdentity)] sees the token's groups and string
/// claims, so an access rule may name the customer's own group names.
///
/// ## Registration
///
/// The filter ships in the framework jar, so an application names it in
/// `web.xml`, mapped to `/*`. Nothing is read until the first request, so a
/// WAR without the properties file costs one attribute lookup per request.
public class OidcLoginFilter implements Filter {

	/// Session attribute holding the signed-in [JwtIdentity].
	public static final String IDENTITY_ATTR = "org.vorpal.blade.oidc.identity";
	/// Path under the context root that signs the browser out.
	public static final String LOGOUT_PATH = "/oidc/logout";
	/// Authentication scheme reported by the wrapped request.
	public static final String AUTH_TYPE = "OIDC";

	static final String STATE_ATTR = "org.vorpal.blade.oidc.state";
	static final String NONCE_ATTR = "org.vorpal.blade.oidc.nonce";
	static final String VERIFIER_ATTR = "org.vorpal.blade.oidc.verifier";
	static final String RETURN_ATTR = "org.vorpal.blade.oidc.return";

	/// When the identity in this session signed in, epoch milliseconds.
	static final String SIGNED_IN_ATTR = "org.vorpal.blade.oidc.signedIn";

	private static final String BEARER_PREFIX = "Bearer ";
	private static final Logger logger = Logger.getLogger(OidcLoginFilter.class.getName());
	private static final SecureRandom random = new SecureRandom();

	/// Finds the provider for a configuration. Production discovers it over
	/// HTTPS; a test hands one in.
	interface Discovery {
		OidcProvider discover(OidcLoginConfig cfg) throws IOException;
	}

	/// Trades a code for the tokens. Production calls the token endpoint; a
	/// test returns a token it signed itself.
	interface Exchange {
		OidcProvider.Tokens exchange(OidcProvider provider, OidcLoginConfig cfg, String code, String verifier)
				throws IOException;
	}

	/// Builds the validator for a token configuration. Production fetches keys
	/// from the JWKS URI; a test supplies an in-memory key set.
	interface Validators {
		JwtValidator build(JwtAuthConfig cfg) throws JwtAuthException;
	}

	private final Discovery discovery;
	private final Exchange exchange;
	private final Validators validators;
	private final OidcLoginConfig fixedConfig;

	private FilterConfig filterConfig;
	private volatile boolean configRead;
	private volatile OidcLoginConfig config;
	private volatile OidcProvider provider;
	private volatile JwtValidator idTokenValidator;
	private volatile JwtValidator bearerValidator;

	/// The container's constructor: discovery over HTTPS, exchange at the
	/// provider, keys from its JWKS.
	public OidcLoginFilter() {
		this(false, null, cfg -> OidcProvider.discover(cfg.discoveryUrl()),
				(p, cfg, code, verifier) -> p.exchange(cfg, code, verifier), JwtValidator::forConfig);
	}

	/// The test seam: a fixed configuration and stand-ins for every network
	/// step. A null configuration behaves like a WAR without the file.
	OidcLoginFilter(OidcLoginConfig fixedConfig, Discovery discovery, Exchange exchange, Validators validators) {
		this(true, fixedConfig, discovery, exchange, validators);
	}

	private OidcLoginFilter(boolean fixed, OidcLoginConfig fixedConfig, Discovery discovery, Exchange exchange,
			Validators validators) {
		this.fixedConfig = fixedConfig;
		this.discovery = discovery;
		this.exchange = exchange;
		this.validators = validators;
		if (fixed) {
			this.config = fixedConfig;
			this.configRead = true;
		}
	}

	@Override
	public void init(FilterConfig filterConfig) {
		this.filterConfig = filterConfig;
	}

	@Override
	public void destroy() {
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
			chain.doFilter(request, response);
			return;
		}
		HttpServletRequest req = (HttpServletRequest) request;
		HttpServletResponse res = (HttpServletResponse) response;
		OidcLoginConfig cfg = currentConfig();

		// 1. an API client with a token
		String authorization = req.getHeader("Authorization");
		if (cfg != null && authorization != null
				&& authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			bearer(req, res, chain, cfg, authorization.substring(BEARER_PREFIX.length()).trim());
			return;
		}

		// 2. the container already knows who this is
		if (req.getUserPrincipal() != null) {
			chain.doFilter(req, res);
			return;
		}

		// 3. no provider configured: the container's login
		if (cfg == null) {
			if (req.authenticate(res)) {
				chain.doFilter(req, res);
			}
			return;
		}

		String path = req.getRequestURI();
		if (path.equals(req.getContextPath() + LOGOUT_PATH)) {
			logout(req, res, cfg);
			return;
		}

		// 4. signed in earlier
		HttpSession session = req.getSession(false);
		JwtIdentity identity = (session == null) ? null : (JwtIdentity) session.getAttribute(IDENTITY_ATTR);
		if (identity != null && signInExpired(session, cfg)) {
			// A session kept busy never times out, so without an absolute limit a
			// sign-in outlives a disabled account or a removed group indefinitely.
			logger.info("sign-in of " + identity + " is older than " + cfg.maxSessionHours() + "h; signing in again");
			session.invalidate();
			session = null;
			identity = null;
		}
		if (identity != null) {
			if (path.equals(cfg.callbackPath())) {
				res.sendRedirect(returnTo(session, req));
				return;
			}
			chain.doFilter(new OidcRequest(req, identity, cfg.roleMappings()), res);
			return;
		}

		// the provider sending the browser back
		if (path.equals(cfg.callbackPath())) {
			callback(req, res, cfg);
			return;
		}

		// 5. start a login
		if (!acceptsHtml(req)) {
			deny(res, HttpServletResponse.SC_UNAUTHORIZED, "login required", true);
			return;
		}
		login(req, res, cfg);
	}

	/// True when the session's sign-in is older than the configured maximum. A
	/// session from before this limit existed carries no sign-in time and counts
	/// as expired, so it signs in once more.
	static boolean signInExpired(HttpSession session, OidcLoginConfig cfg) {
		Object signedIn = session.getAttribute(SIGNED_IN_ATTR);
		if (!(signedIn instanceof Long)) {
			return true;
		}
		return System.currentTimeMillis() - (Long) signedIn > cfg.maxSessionHours() * 3_600_000L;
	}

	private void bearer(HttpServletRequest req, HttpServletResponse res, FilterChain chain, OidcLoginConfig cfg,
			String token) throws IOException, ServletException {
		JwtValidator validator;
		try {
			validator = bearerValidator(cfg);
		} catch (IOException | JwtAuthException e) {
			logger.log(Level.WARNING, "OpenID provider unavailable: " + e.getMessage(), e);
			deny(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "identity provider unavailable", false);
			return;
		}
		try {
			JwtIdentity identity = validator.validate(token);
			chain.doFilter(new OidcRequest(req, identity, cfg.roleMappings()), res);
		} catch (JwtAuthException e) {
			logger.log(Level.FINE, "bearer token rejected", e);
			deny(res, HttpServletResponse.SC_UNAUTHORIZED, "invalid bearer token", true);
		}
	}

	private void login(HttpServletRequest req, HttpServletResponse res, OidcLoginConfig cfg) throws IOException {
		OidcProvider p;
		try {
			p = provider(cfg);
		} catch (IOException e) {
			logger.log(Level.WARNING, "OpenID provider discovery failed: " + e.getMessage(), e);
			deny(res, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "identity provider unavailable", false);
			return;
		}
		String state = randomToken();
		String nonce = randomToken();
		CodeVerifier verifier = new CodeVerifier();
		HttpSession session = req.getSession(true);
		session.setAttribute(STATE_ATTR, state);
		session.setAttribute(NONCE_ATTR, nonce);
		session.setAttribute(VERIFIER_ATTR, verifier.getValue());
		StringBuffer back = req.getRequestURL();
		if (req.getQueryString() != null) {
			back.append('?').append(req.getQueryString());
		}
		session.setAttribute(RETURN_ATTR, back.toString());

		String challenge = CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue();
		StringBuilder url = new StringBuilder(p.authorizationEndpoint().toString());
		url.append(p.authorizationEndpoint().getQuery() == null ? '?' : '&');
		url.append("response_type=code");
		url.append("&client_id=").append(encode(cfg.clientId()));
		url.append("&redirect_uri=").append(encode(cfg.redirectUrl()));
		url.append("&scope=").append(encode(cfg.scope()));
		url.append("&state=").append(encode(state));
		url.append("&nonce=").append(encode(nonce));
		url.append("&code_challenge=").append(encode(challenge));
		url.append("&code_challenge_method=S256");
		res.sendRedirect(url.toString());
	}

	private void callback(HttpServletRequest req, HttpServletResponse res, OidcLoginConfig cfg) throws IOException {
		HttpSession session = req.getSession(false);
		String expected = (session == null) ? null : (String) session.getAttribute(STATE_ATTR);
		String state = req.getParameter("state");
		if (expected == null || state == null || !expected.equals(state)) {
			// No session, or not the one that started this login: a stale
			// bookmark, a cookie the browser refused, or a forged callback.
			deny(res, HttpServletResponse.SC_BAD_REQUEST, "login state does not match; start again", false);
			return;
		}
		String nonce = (String) session.getAttribute(NONCE_ATTR);
		String verifier = (String) session.getAttribute(VERIFIER_ATTR);
		session.removeAttribute(STATE_ATTR);
		session.removeAttribute(NONCE_ATTR);
		session.removeAttribute(VERIFIER_ATTR);

		String error = req.getParameter("error");
		if (error != null) {
			String description = req.getParameter("error_description");
			logger.info("OpenID provider refused the login: " + error
					+ (description == null ? "" : " (" + description + ")"));
			deny(res, HttpServletResponse.SC_UNAUTHORIZED, "identity provider refused the login: " + error, false);
			return;
		}
		String code = req.getParameter("code");
		if (code == null || code.isEmpty()) {
			deny(res, HttpServletResponse.SC_BAD_REQUEST, "callback carries no code", false);
			return;
		}

		JwtIdentity identity;
		try {
			OidcProvider p = provider(cfg);
			OidcProvider.Tokens tokens = exchange.exchange(p, cfg, code, verifier);
			JwtValidator validator = idTokenValidator(cfg);
			identity = validator.validate(tokens.idToken);
			if (identity.groups().isEmpty()) {
				identity = groupsFromElsewhere(cfg, p, validator, tokens, identity);
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, "OpenID token exchange failed: " + e.getMessage(), e);
			deny(res, HttpServletResponse.SC_BAD_GATEWAY, "could not obtain a token from the identity provider", false);
			return;
		} catch (JwtAuthException e) {
			logger.log(Level.WARNING, "ID token rejected: " + e.getMessage(), e);
			deny(res, HttpServletResponse.SC_UNAUTHORIZED, "identity provider's token was rejected", false);
			return;
		}
		if (nonce == null || !nonce.equals(identity.claim("nonce"))) {
			logger.warning("ID token nonce does not match the login that started it; user " + identity.getName());
			deny(res, HttpServletResponse.SC_UNAUTHORIZED, "identity provider's token was rejected", false);
			return;
		}

		try {
			req.changeSessionId(); // the identity goes into a fresh session id, not the one the login page saw
		} catch (RuntimeException e) {
			logger.log(Level.FINE, "session id not changed", e);
		}
		session.setAttribute(IDENTITY_ATTR, identity);
		session.setAttribute(SIGNED_IN_ATTR, System.currentTimeMillis());
		// One line per sign-in, with the groups as the token carried them: the
		// first thing to look at when a rule that names a group does not match.
		logger.info("signed in " + identity + " from " + req.getRemoteAddr());
		res.sendRedirect(returnTo(session, req));
	}

	/// The groups a provider leaves out of its ID token. An OCI identity domain
	/// answers `openid groups` with an ID token that has none; they are in the
	/// access token's claims, or failing that at the userinfo endpoint. Both
	/// are tried, in that order, and when neither has any the sign-in line
	/// names the claims that did arrive, which is what an operator needs to
	/// see next.
	private JwtIdentity groupsFromElsewhere(OidcLoginConfig cfg, OidcProvider p, JwtValidator validator,
			OidcProvider.Tokens tokens, JwtIdentity identity) throws IOException {
		if (tokens.accessToken != null && tokens.accessToken.chars().filter(ch -> ch == '.').count() == 2) {
			try {
				JwtIdentity fromAccess = bearerValidator(cfg).validate(tokens.accessToken);
				if (!fromAccess.groups().isEmpty()) {
					return validator.withGroups(identity, fromAccess.groups());
				}
			} catch (JwtAuthException e) {
				logger.log(Level.FINE, "access token is not a verifiable JWT; trying userinfo", e);
			}
		}
		com.fasterxml.jackson.databind.JsonNode info = p.userinfo(tokens.accessToken);
		if (info != null) {
			com.fasterxml.jackson.databind.JsonNode claim = info.get(cfg.groupsClaim());
			Object raw = (claim == null) ? null
					: new com.fasterxml.jackson.databind.ObjectMapper().convertValue(claim, Object.class);
			java.util.List<String> groups = JwtValidator.groupValues(raw);
			if (!groups.isEmpty()) {
				return validator.withGroups(identity, groups);
			}
			logger.info("no '" + cfg.groupsClaim() + "' for " + identity.getName() + "; ID token claims "
					+ identity.claims().keySet() + ", userinfo claims " + fieldNames(info));
		} else {
			logger.info("no '" + cfg.groupsClaim() + "' for " + identity.getName() + "; ID token claims "
					+ identity.claims().keySet() + ", and the provider publishes no userinfo endpoint");
		}
		return identity;
	}

	private static java.util.List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
		java.util.List<String> names = new java.util.ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private void logout(HttpServletRequest req, HttpServletResponse res, OidcLoginConfig cfg) throws IOException {
		HttpSession session = req.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		String home = req.getRequestURL().toString();
		home = home.substring(0, home.length() - LOGOUT_PATH.length()) + "/";
		OidcProvider p = null;
		try {
			p = provider(cfg);
		} catch (IOException e) {
			logger.log(Level.FINE, "no provider for logout", e);
		}
		if (p != null && p.endSessionEndpoint() != null) {
			String url = p.endSessionEndpoint() + (p.endSessionEndpoint().getQuery() == null ? "?" : "&")
					+ "post_logout_redirect_uri=" + encode(home) + "&client_id=" + encode(cfg.clientId());
			res.sendRedirect(url);
		} else {
			res.sendRedirect(home);
		}
	}

	/// Where to send the browser after a login: the address it asked for, or
	/// the application's root when nothing was recorded.
	private static String returnTo(HttpSession session, HttpServletRequest req) {
		String back = (String) session.getAttribute(RETURN_ATTR);
		session.removeAttribute(RETURN_ATTR);
		if (back != null) {
			return back;
		}
		String url = req.getRequestURL().toString();
		int ctx = url.indexOf(req.getRequestURI());
		return (ctx < 0 ? url : url.substring(0, ctx)) + req.getContextPath() + "/";
	}

	private static boolean acceptsHtml(HttpServletRequest req) {
		if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())) {
			return false;
		}
		String accept = req.getHeader("Accept");
		return accept != null && (accept.contains("text/html") || accept.contains("*/*"));
	}

	private static void deny(HttpServletResponse res, int status, String message, boolean challenge)
			throws IOException {
		res.setStatus(status);
		if (challenge) {
			res.setHeader("WWW-Authenticate", "Bearer realm=\"blade\"");
		}
		res.setContentType("application/json");
		res.setCharacterEncoding("UTF-8");
		res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String encode(String value) {
		try {
			return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- lazily built collaborators ------------------------------------------

	private OidcLoginConfig currentConfig() {
		if (configRead) {
			return config;
		}
		synchronized (this) {
			if (!configRead) {
				OidcLoginConfig read = null;
				if (filterConfig != null && filterConfig.getServletContext() != null) {
					try {
						read = OidcLoginConfig.read(filterConfig.getServletContext().getResourceAsStream(OidcLoginConfig.RESOURCE));
						if (read != null) {
							logger.info("OpenID login configured: " + read);
						}
					} catch (IOException | RuntimeException e) {
						// A broken file must not open the door: with no config the
						// container's login applies, and the reason is in the log.
						logger.log(Level.SEVERE, OidcLoginConfig.RESOURCE + " unusable; falling back to the container's login: "
								+ e.getMessage(), e);
					}
				}
				config = read;
				configRead = true;
			}
			return config;
		}
	}

	private OidcProvider provider(OidcLoginConfig cfg) throws IOException {
		OidcProvider p = provider;
		if (p != null) {
			return p;
		}
		synchronized (this) {
			if (provider == null) {
				provider = discovery.discover(cfg);
			}
			return provider;
		}
	}

	private JwtValidator idTokenValidator(OidcLoginConfig cfg) throws IOException, JwtAuthException {
		JwtValidator v = idTokenValidator;
		if (v != null) {
			return v;
		}
		synchronized (this) {
			if (idTokenValidator == null) {
				idTokenValidator = validators.build(cfg.idTokenConfig(provider(cfg).jwksUri()));
			}
			return idTokenValidator;
		}
	}

	private JwtValidator bearerValidator(OidcLoginConfig cfg) throws IOException, JwtAuthException {
		JwtValidator v = bearerValidator;
		if (v != null) {
			return v;
		}
		synchronized (this) {
			if (bearerValidator == null) {
				bearerValidator = validators.build(cfg.bearerConfig(provider(cfg).jwksUri()));
			}
			return bearerValidator;
		}
	}

	/// The request as the application sees it once a token has named the
	/// caller. A role is any value the group claim carried, verbatim or through
	/// a `role.<group>` mapping, so the descriptor's externally defined roles
	/// read the provider's groups directly.
	static final class OidcRequest extends HttpServletRequestWrapper {
		private final JwtIdentity identity;
		private final Map<String, String> roleMappings;

		OidcRequest(HttpServletRequest request, JwtIdentity identity, Map<String, String> roleMappings) {
			super(request);
			this.identity = identity;
			this.roleMappings = roleMappings;
		}

		@Override
		public Principal getUserPrincipal() {
			return identity;
		}

		@Override
		public String getRemoteUser() {
			return identity.getName();
		}

		@Override
		public String getAuthType() {
			return AUTH_TYPE;
		}

		@Override
		public boolean isUserInRole(String role) {
			if (role == null) {
				return false;
			}
			if (identity.roles().contains(role) || identity.groups().contains(role)) {
				return true;
			}
			for (String group : identity.groups()) {
				if (role.equals(roleMappings.get(group))) {
					return true;
				}
			}
			return false;
		}
	}
}
