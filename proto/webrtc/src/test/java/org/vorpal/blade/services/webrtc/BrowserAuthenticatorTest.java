package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import org.vorpal.blade.framework.v3.security.JwtAuthConfig;
import org.vorpal.blade.framework.v3.security.JwtAuthException;
import org.vorpal.blade.framework.v3.security.JwtIssuer;
import org.vorpal.blade.framework.v3.security.JwtIssuerConfig;
import org.vorpal.blade.framework.v3.security.JwtValidator;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/// The rule that closes the gateway, exercised end to end against a real issuer.
///
/// Tokens here are minted by an actual [JwtIssuer] and verified through its
/// published JWKS, so these are the same code paths a deployment runs — only the
/// transport is missing. What they are really guarding is the difference between
/// *authenticated* and *authorized*: several of the cases below present a
/// perfectly valid, correctly signed token from a genuine user and must still be
/// refused, because the address they ask for is not the address they were given.
public class BrowserAuthenticatorTest {

	private static final String ISSUER = "urn:blade:phone";
	private static final String AUDIENCE = "urn:blade:webrtc";

	private JwtIssuer issuer;
	private BrowserAuthenticator authenticator;

	@Before
	public void setUp() throws Exception {
		JwtIssuerConfig issuerConfig = new JwtIssuerConfig();
		issuerConfig.setIssuer(ISSUER);
		issuerConfig.setAudience(AUDIENCE);
		issuer = new JwtIssuer(issuerConfig);

		// Same class the gateway uses, with the network JWKS fetch replaced by
		// the document this issuer publishes.
		authenticator = new BrowserAuthenticator() {
			@Override
			protected JwtValidator validatorFor(JwtAuthConfig config) throws JwtAuthException {
				try {
					return new JwtValidator(config, new ImmutableJWKSet<>(JWKSet.parse(issuer.jwksJson())));
				} catch (Exception e) {
					throw new JwtAuthException("test JWKS", e);
				}
			}
		};
	}

	private static JwtAuthConfig enabledConfig() {
		JwtAuthConfig cfg = new JwtAuthConfig();
		cfg.setEnabled(true);
		cfg.setIssuer(ISSUER);
		cfg.setAudience(AUDIENCE);
		cfg.setRolesClaim("groups");
		return cfg;
	}

	private String tokenFor(String user, String aor, String... roles) throws Exception {
		Map<String, String> claims = new LinkedHashMap<>();
		claims.put("aor", aor);
		return issuer.mint(user, Arrays.asList(roles), claims);
	}

	// ---- the happy path -----------------------------------------------------------------------

	@Test
	public void bindsTheAddressTheTokenGrants() throws Exception {
		String token = tokenFor("alice", "alice@vorpal.net", "Admin");

		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), token, "alice@vorpal.net");

		assertTrue(d.isAllowed());
		assertTrue(d.isAuthenticated());
		assertEquals("alice@vorpal.net", d.getAor());
		assertEquals("alice", d.getUser());
		assertNull(d.getReason());
	}

	@Test
	public void acceptsATokenWhenTheBrowserNamesNoAddressAtAll() throws Exception {
		String token = tokenFor("alice", "alice@vorpal.net", "Monitor");

		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), token, null);

		assertTrue(d.isAllowed());
		assertEquals("alice@vorpal.net", d.getAor());
	}

	// ---- authenticated, and still not allowed --------------------------------------------------

	@Test
	public void refusesAValidTokenAskingForSomebodyElsesAddress() throws Exception {
		// alice is a real, signed-in Admin. That is not the question.
		String token = tokenFor("alice", "alice@vorpal.net", "Admin");

		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), token, "ceo@vorpal.net");

		assertFalse("a signed-in user must not be able to claim another address", d.isAllowed());
		assertTrue(d.getReason().contains("grants alice@vorpal.net"));
		assertNull(d.getAor());
	}

	@Test
	public void refusesATokenThatNamesNoAddress() throws Exception {
		String token = issuer.mint("alice", Collections.singletonList("Admin"), null);

		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), token, "alice@vorpal.net");

		assertFalse(d.isAllowed());
		assertTrue(d.getReason().contains("aor"));
	}

	@Test
	public void refusesACallerHoldingNoBladeRole() throws Exception {
		String token = tokenFor("intern", "intern@vorpal.net", "Guest");

		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), token, "intern@vorpal.net");

		assertFalse(d.isAllowed());
		assertTrue(d.getReason().contains("no BLADE role"));
	}

	// ---- not authenticated ---------------------------------------------------------------------

	@Test
	public void refusesASocketThatSendsNoToken() {
		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), null, "alice@vorpal.net");

		assertFalse(d.isAllowed());
		assertTrue(d.getReason().contains("requires an authentication token"));
	}

	@Test
	public void refusesAGarbageToken() {
		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), "not.a.jwt", "alice@vorpal.net");

		assertFalse(d.isAllowed());
		assertTrue(d.getReason().startsWith("token rejected"));
	}

	@Test
	public void refusesATokenFromAnotherIssuersKey() throws Exception {
		JwtIssuerConfig strangerConfig = new JwtIssuerConfig();
		strangerConfig.setIssuer(ISSUER);
		strangerConfig.setAudience(AUDIENCE);
		Map<String, String> claims = new LinkedHashMap<>();
		claims.put("aor", "alice@vorpal.net");
		// Identical claims, identical issuer string — a different signing key.
		String forged = new JwtIssuer(strangerConfig).mint("alice", Collections.singletonList("Admin"), claims);

		BrowserAuthenticator.Decision d = authenticator.authorize(enabledConfig(), forged, "alice@vorpal.net");

		assertFalse(d.isAllowed());
	}

	// ---- failing closed ------------------------------------------------------------------------

	@Test
	public void refusesWhenTheGatewayHasNoConfigurationLoaded() {
		BrowserAuthenticator.Decision d = authenticator.authorize(null, "any-token", "alice@vorpal.net");

		assertFalse("a missing configuration must not read as an absent rule", d.isAllowed());
		assertFalse(d.isAuthenticated());
	}

	@Test
	public void refusesWhenAuthenticationIsEnabledButUnusable() throws Exception {
		// The shipped sample: enabled, with no jwksUri filled in yet. The real
		// validatorFor runs here, so this is the genuine misconfiguration path.
		BrowserAuthenticator real = new BrowserAuthenticator();
		String token = tokenFor("alice", "alice@vorpal.net", "Admin");

		BrowserAuthenticator.Decision d = real.authorize(enabledConfig(), token, "alice@vorpal.net");

		assertFalse(d.isAllowed());
		assertTrue(d.getReason().contains("misconfigured"));
		assertTrue("the message should name the setting to fix", d.getReason().contains("jwksUri"));
	}

	// ---- the deliberate open mode --------------------------------------------------------------

	@Test
	public void allowsUnauthenticatedBrowsersOnlyWhenExplicitlyDisabled() {
		JwtAuthConfig off = enabledConfig();
		off.setEnabled(false);

		BrowserAuthenticator.Decision d = authenticator.authorize(off, null, "alice@vorpal.net");

		assertTrue(d.isAllowed());
		assertFalse("the page must be able to see that nothing was proved", d.isAuthenticated());
		assertEquals("alice@vorpal.net", d.getAor());
	}

	@Test
	public void stillRequiresAnAddressWhenAuthenticationIsDisabled() {
		JwtAuthConfig off = enabledConfig();
		off.setEnabled(false);

		assertFalse(authenticator.authorize(off, null, null).isAllowed());
		assertFalse(authenticator.authorize(off, null, "   ").isAllowed());
	}
}
