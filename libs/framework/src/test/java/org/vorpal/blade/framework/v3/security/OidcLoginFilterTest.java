package org.vorpal.blade.framework.v3.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/// The whole sign-in, with the network replaced: discovery answers from a
/// literal, the token endpoint is a [JwtIssuer] signing what a provider would,
/// and the validator trusts that issuer's published key set. The servlet API is
/// stubbed through dynamic proxies, which keeps the test on the framework's
/// own compile path.
class OidcLoginFilterTest {

	private static final String ISSUER = "https://idcs.example.com";
	private static final String CLIENT = "client-1";
	private static final String CONTEXT = "/blade/recordings";
	private static final String HOST = "https://apps.example.com";
	private static final String CALLBACK = HOST + ":443" + CONTEXT + "/oidc/callback";

	private static final OidcProvider PROVIDER = new OidcProvider(URI.create(ISSUER + "/oauth2/v1/authorize"),
			URI.create(ISSUER + "/oauth2/v1/token"), ISSUER + "/admin/v1/SigningCert/jwk",
			URI.create(ISSUER + "/oauth2/v1/userlogout"));

	private static OidcLoginConfig config(String... extra) {
		Properties p = new Properties();
		p.setProperty("issuer", ISSUER);
		p.setProperty("clientId", CLIENT);
		p.setProperty("clientSecret", "s3cret");
		p.setProperty("redirectUrl", CALLBACK);
		p.setProperty("scope", "openid groups");
		for (int i = 0; i + 1 < extra.length; i += 2) {
			p.setProperty(extra[i], extra[i + 1]);
		}
		return OidcLoginConfig.parse(p);
	}

	/// The provider's signing side: what the token endpoint would hand back.
	private static JwtIssuer providerKeys() throws Exception {
		JwtIssuerConfig cfg = new JwtIssuerConfig();
		cfg.setIssuer(ISSUER);
		cfg.setAudience(CLIENT);
		cfg.setTtlSeconds(60);
		return new JwtIssuer(cfg);
	}

	private static OidcLoginFilter filter(OidcLoginConfig cfg, JwtIssuer keys, String tokenToReturn) {
		OidcLoginFilter.Validators validators = c -> {
			try {
				return new JwtValidator(c, new ImmutableJWKSet<>(JWKSet.parse(keys.jwksJson())));
			} catch (Exception e) {
				throw new JwtAuthException("bad JWKS", e);
			}
		};
		return new OidcLoginFilter(cfg, c -> PROVIDER, (p, c, code, verifier) -> {
			assertEquals("the-code", code);
			assertNotNull(verifier);
			return new OidcProvider.Tokens(tokenToReturn, null);
		}, validators);
	}

	// --- servlet stand-ins ------------------------------------------------------

	static final class Session {
		final Map<String, Object> attributes = new HashMap<>();
		boolean invalidated;

		HttpSession proxy() {
			return (HttpSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { HttpSession.class },
					(proxy, method, args) -> {
						switch (method.getName()) {
						case "getAttribute":
							return attributes.get(args[0]);
						case "setAttribute":
							attributes.put((String) args[0], args[1]);
							return null;
						case "removeAttribute":
							attributes.remove(args[0]);
							return null;
						case "invalidate":
							invalidated = true;
							attributes.clear();
							return null;
						case "getId":
							return "sess-1";
						default:
							return defaultValue(method);
						}
					});
		}
	}

	static final class Request {
		String method = "GET";
		String uri = CONTEXT + "/api/v1/recordings";
		String query;
		Principal principal;
		boolean authenticateAnswer;
		int authenticateCalls;
		final Map<String, String> headers = new LinkedHashMap<>();
		final Map<String, String> params = new LinkedHashMap<>();
		Session session;

		Request accept(String value) {
			headers.put("Accept", value);
			return this;
		}

		HttpServletRequest proxy() {
			return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { HttpServletRequest.class }, (InvocationHandler) (proxy, m, args) -> {
						switch (m.getName()) {
						case "getMethod":
							return method;
						case "getRequestURI":
							return uri;
						case "getRequestURL":
							return new StringBuffer(HOST + uri);
						case "getQueryString":
							return query;
						case "getContextPath":
							return CONTEXT;
						case "getUserPrincipal":
							return principal;
						case "getRemoteUser":
							return principal == null ? null : principal.getName();
						case "getHeader":
							return headers.get(args[0]);
						case "getParameter":
							return params.get(args[0]);
						case "authenticate":
							authenticateCalls++;
							return authenticateAnswer;
						case "changeSessionId":
							return "sess-2";
						case "getSession":
							boolean create = args == null || args.length == 0 || Boolean.TRUE.equals(args[0]);
							if (session == null && create) {
								session = new Session();
							}
							return session == null ? null : session.proxy();
						default:
							return defaultValue(m);
						}
					});
		}
	}

	static final class Response {
		int status = 200;
		String redirect;
		final Map<String, String> headers = new LinkedHashMap<>();
		final StringWriter body = new StringWriter();

		HttpServletResponse proxy() {
			return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { HttpServletResponse.class }, (proxy, m, args) -> {
						switch (m.getName()) {
						case "setStatus":
							status = (Integer) args[0];
							return null;
						case "sendRedirect":
							status = 302;
							redirect = (String) args[0];
							return null;
						case "setHeader":
							headers.put((String) args[0], (String) args[1]);
							return null;
						case "getWriter":
							return new PrintWriter(body);
						default:
							return defaultValue(m);
						}
					});
		}
	}

	static final class Chain implements FilterChain {
		ServletRequest passed;

		@Override
		public void doFilter(ServletRequest request, ServletResponse response) {
			passed = request;
		}

		HttpServletRequest http() {
			return (HttpServletRequest) passed;
		}
	}

	static Object defaultValue(Method m) {
		Class<?> t = m.getReturnType();
		if (t == boolean.class) {
			return false;
		}
		if (t == int.class || t == long.class) {
			return 0;
		}
		return null;
	}

	private static Map<String, String> query(String url) {
		Map<String, String> out = new LinkedHashMap<>();
		String q = url.substring(url.indexOf('?') + 1);
		for (String pair : q.split("&")) {
			int eq = pair.indexOf('=');
			out.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
		}
		return out;
	}

	@Nested
	@DisplayName("without a properties file")
	class Unconfigured {

		@Test
		void asksTheContainerToAuthenticate() throws Exception {
			OidcLoginFilter f = new OidcLoginFilter(null, c -> PROVIDER, null, null);
			Request req = new Request();
			Response res = new Response();
			Chain chain = new Chain();
			f.doFilter(req.proxy(), res.proxy(), chain);
			assertEquals(1, req.authenticateCalls);
			assertNull(chain.passed, "the container's login owns the response");
		}

		@Test
		void passesAContainerAuthenticatedRequestUntouched() throws Exception {
			OidcLoginFilter f = new OidcLoginFilter(null, c -> PROVIDER, null, null);
			Request req = new Request();
			req.principal = () -> "weblogic";
			HttpServletRequest proxy = req.proxy();
			Chain chain = new Chain();
			f.doFilter(proxy, new Response().proxy(), chain);
			assertSame(proxy, chain.passed);
			assertEquals(0, req.authenticateCalls);
		}
	}

	@Nested
	@DisplayName("starting a login")
	class Login {

		@Test
		void sendsABrowserToTheProviderWithPkce() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = new Request().accept("text/html,application/xhtml+xml");
			req.query = "date=2026-09-08";
			Response res = new Response();
			Chain chain = new Chain();
			f.doFilter(req.proxy(), res.proxy(), chain);

			assertNull(chain.passed);
			assertEquals(302, res.status);
			assertTrue(res.redirect.startsWith(ISSUER + "/oauth2/v1/authorize?"), res.redirect);
			Map<String, String> q = query(res.redirect);
			assertEquals("code", q.get("response_type"));
			assertEquals(CLIENT, q.get("client_id"));
			assertEquals(CALLBACK, q.get("redirect_uri"));
			assertEquals("openid groups", q.get("scope"));
			assertEquals("S256", q.get("code_challenge_method"));
			assertEquals(q.get("state"), req.session.attributes.get(OidcLoginFilter.STATE_ATTR));
			assertEquals(q.get("nonce"), req.session.attributes.get(OidcLoginFilter.NONCE_ATTR));
			assertNotNull(req.session.attributes.get(OidcLoginFilter.VERIFIER_ATTR));
			assertEquals(HOST + CONTEXT + "/api/v1/recordings?date=2026-09-08",
					req.session.attributes.get(OidcLoginFilter.RETURN_ATTR));
		}

		@Test
		void answersAnApiCallWith401InsteadOfARedirect() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = new Request().accept("application/json");
			Response res = new Response();
			f.doFilter(req.proxy(), res.proxy(), new Chain());
			assertEquals(401, res.status);
			assertNull(res.redirect);
			assertTrue(res.headers.get("WWW-Authenticate").startsWith("Bearer"));
			assertTrue(res.body.toString().contains("login required"));
		}
	}

	@Nested
	@DisplayName("the callback")
	class Callback {

		private Request callbackRequest(String state, String code) {
			Request req = new Request().accept("text/html");
			req.uri = CONTEXT + "/oidc/callback";
			req.params.put("state", state);
			if (code != null) {
				req.params.put("code", code);
			}
			return req;
		}

		private Session startedLogin(Request req, String state, String nonce) {
			req.session = new Session();
			req.session.attributes.put(OidcLoginFilter.STATE_ATTR, state);
			req.session.attributes.put(OidcLoginFilter.NONCE_ATTR, nonce);
			req.session.attributes.put(OidcLoginFilter.VERIFIER_ATTR, "verifier-value-that-is-long-enough-for-pkce-43-chars");
			req.session.attributes.put(OidcLoginFilter.RETURN_ATTR, HOST + CONTEXT + "/api/v1/recordings");
			return req.session;
		}

		@Test
		void refusesAStateItDidNotIssue() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = callbackRequest("forged", "the-code");
			startedLogin(req, "real", "n1");
			Response res = new Response();
			f.doFilter(req.proxy(), res.proxy(), new Chain());
			assertEquals(400, res.status);
			assertNull(req.session.attributes.get(OidcLoginFilter.IDENTITY_ATTR));
		}

		@Test
		void signsInAndReturnsToTheRequestedPage() throws Exception {
			JwtIssuer keys = providerKeys();
			Map<String, String> claims = new LinkedHashMap<>();
			claims.put("nonce", "n1");
			String idToken = keys.mint("reviewer1", Arrays.asList("Reviewer"), claims);
			OidcLoginFilter f = filter(config(), keys, idToken);

			Request req = callbackRequest("st", "the-code");
			Session session = startedLogin(req, "st", "n1");
			Response res = new Response();
			f.doFilter(req.proxy(), res.proxy(), new Chain());

			assertEquals(302, res.status);
			assertEquals(HOST + CONTEXT + "/api/v1/recordings", res.redirect);
			JwtIdentity id = (JwtIdentity) session.attributes.get(OidcLoginFilter.IDENTITY_ATTR);
			assertNotNull(id);
			assertEquals("reviewer1", id.getName());
			assertEquals(Collections.singleton("Reviewer"), id.groups());
			assertNull(session.attributes.get(OidcLoginFilter.STATE_ATTR), "one-time values are gone");
			assertNull(session.attributes.get(OidcLoginFilter.VERIFIER_ATTR));
		}

		@Test
		void refusesATokenWithAnotherLoginsNonce() throws Exception {
			JwtIssuer keys = providerKeys();
			Map<String, String> claims = new LinkedHashMap<>();
			claims.put("nonce", "someone-elses");
			OidcLoginFilter f = filter(config(), keys, keys.mint("reviewer1", Arrays.asList("Reviewer"), claims));
			Request req = callbackRequest("st", "the-code");
			Session session = startedLogin(req, "st", "n1");
			Response res = new Response();
			f.doFilter(req.proxy(), res.proxy(), new Chain());
			assertEquals(401, res.status);
			assertNull(session.attributes.get(OidcLoginFilter.IDENTITY_ATTR));
		}

		@Test
		void reportsTheProvidersRefusal() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = callbackRequest("st", null);
			req.params.put("error", "access_denied");
			startedLogin(req, "st", "n1");
			Response res = new Response();
			f.doFilter(req.proxy(), res.proxy(), new Chain());
			assertEquals(401, res.status);
			assertTrue(res.body.toString().contains("access_denied"));
		}
	}

	@Nested
	@DisplayName("a signed-in session")
	class SignedIn {

		private Request signedIn(List<String> groups) {
			Request req = new Request().accept("text/html");
			req.session = new Session();
			req.session.attributes.put(OidcLoginFilter.IDENTITY_ATTR,
					new JwtIdentity("reviewer1", Collections.emptySet(), null, new java.util.LinkedHashSet<>(groups)));
			req.session.attributes.put(OidcLoginFilter.SIGNED_IN_ATTR, System.currentTimeMillis());
			return req;
		}

		@Test
		void proceedsAsTheTokensIdentity() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = signedIn(Arrays.asList("Reviewer"));
			Chain chain = new Chain();
			f.doFilter(req.proxy(), new Response().proxy(), chain);
			assertNotNull(chain.passed);
			assertEquals("reviewer1", chain.http().getUserPrincipal().getName());
			assertEquals("reviewer1", chain.http().getRemoteUser());
			assertEquals(OidcLoginFilter.AUTH_TYPE, chain.http().getAuthType());
			assertTrue(chain.http().isUserInRole("Reviewer"), "a group named for the role is the role");
			assertFalse(chain.http().isUserInRole("Admin"));
		}

		@Test
		void mapsADirectoryGroupToARole() throws Exception {
			OidcLoginFilter f = filter(config("role.cardiology-supervisors", "Reviewer"), providerKeys(), null);
			Request req = signedIn(Arrays.asList("cardiology-supervisors"));
			Chain chain = new Chain();
			f.doFilter(req.proxy(), new Response().proxy(), chain);
			assertTrue(chain.http().isUserInRole("Reviewer"));
			assertTrue(chain.http().isUserInRole("cardiology-supervisors"), "the raw group name still answers");
		}

		@Test
		void aStaleSignInReauthenticates() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = signedIn(Arrays.asList("Reviewer"));
			// 13 hours old, past the 12-hour default: the session is dropped and a
			// login begins rather than proceeding as the stored identity.
			req.session.attributes.put(OidcLoginFilter.SIGNED_IN_ATTR,
					System.currentTimeMillis() - 13L * 3_600_000L);
			Response res = new Response();
			Chain chain = new Chain();
			f.doFilter(req.proxy(), res.proxy(), chain);
			assertTrue(req.session.invalidated);
			assertNull(chain.passed);
		}

		@Test
		void signsOutAtTheProvider() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = signedIn(Arrays.asList("Reviewer"));
			req.uri = CONTEXT + OidcLoginFilter.LOGOUT_PATH;
			Response res = new Response();
			f.doFilter(req.proxy(), res.proxy(), new Chain());
			assertTrue(req.session.invalidated);
			assertTrue(res.redirect.startsWith(ISSUER + "/oauth2/v1/userlogout?"), res.redirect);
			assertEquals(HOST + CONTEXT + "/", query(res.redirect).get("post_logout_redirect_uri"));
		}
	}

	@Nested
	@DisplayName("an API client's bearer token")
	class Bearer {

		@Test
		void isVerifiedWithoutASession() throws Exception {
			JwtIssuer keys = providerKeys();
			OidcLoginFilter f = filter(config("audience", CLIENT), keys, null);
			Request req = new Request().accept("application/json");
			req.headers.put("Authorization", "Bearer " + keys.mint("svc-1", Arrays.asList("Reviewer"), null));
			Chain chain = new Chain();
			f.doFilter(req.proxy(), new Response().proxy(), chain);
			assertNotNull(chain.passed);
			assertEquals("svc-1", chain.http().getUserPrincipal().getName());
			assertNull(req.session, "no session is created for a token");
		}

		@Test
		void isRefusedWhenItDoesNotVerify() throws Exception {
			OidcLoginFilter f = filter(config(), providerKeys(), null);
			Request req = new Request().accept("application/json");
			req.headers.put("Authorization", "Bearer not.a.token");
			Response res = new Response();
			Chain chain = new Chain();
			f.doFilter(req.proxy(), res.proxy(), chain);
			assertNull(chain.passed);
			assertEquals(401, res.status);
		}
	}

	@Nested
	@DisplayName("the properties file")
	class Config {

		@Test
		void derivesDiscoveryAndCallbackPath() {
			OidcLoginConfig cfg = config();
			assertEquals(ISSUER + "/.well-known/openid-configuration", cfg.discoveryUrl());
			assertEquals(CONTEXT + "/oidc/callback", cfg.callbackPath());
			assertEquals("sub", cfg.usernameClaim());
			assertEquals("groups", cfg.groupsClaim());
		}

		@Test
		void takesAnExplicitDiscoveryUrl() {
			OidcLoginConfig cfg = config("discoveryUrl", "https://tenant.example.com/.well-known/openid-configuration");
			assertEquals("https://tenant.example.com/.well-known/openid-configuration", cfg.discoveryUrl());
		}

		@Test
		void refusesAFileMissingTheClient() {
			Properties p = new Properties();
			p.setProperty("issuer", ISSUER);
			try {
				OidcLoginConfig.parse(p);
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("clientId"));
				return;
			}
			throw new AssertionError("a file without a client must not parse");
		}

		@Test
		void readsGroupsInEveryShapeAProviderUses() {
			assertEquals(Arrays.asList("a", "b"), JwtValidator.groupValues("a, b"));
			assertEquals(Arrays.asList("a", "b"), JwtValidator.groupValues(Arrays.asList("a", "b")));
			Map<String, Object> obj = new LinkedHashMap<>();
			obj.put("id", "x1");
			obj.put("name", "Reviewer");
			assertEquals(Collections.singletonList("Reviewer"), JwtValidator.groupValues(Collections.singletonList(obj)));
			assertTrue(JwtValidator.groupValues(null).isEmpty());
		}

		@Test
		void parsesADiscoveryDocument() throws Exception {
			OidcProvider p = OidcProvider.parse("{\"issuer\":\"x\",\"authorization_endpoint\":\"https://a/auth\","
					+ "\"token_endpoint\":\"https://a/token\",\"jwks_uri\":\"https://a/jwk\"}");
			assertEquals("https://a/auth", p.authorizationEndpoint().toString());
			assertEquals("https://a/jwk", p.jwksUri());
			assertNull(p.endSessionEndpoint());
		}
	}
}
