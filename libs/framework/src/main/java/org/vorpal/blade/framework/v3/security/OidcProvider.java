package org.vorpal.blade.framework.v3.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// What [OidcLoginFilter] needs to know about the identity provider, found by
/// discovery: the endpoint to send the browser to, the endpoint to trade the
/// code at, the keys that sign its tokens, and where a sign-out goes.
///
/// Discovery is one HTTPS fetch of the configured discovery URL, parsed as
/// JSON. The document's `issuer` field is not checked against the configured
/// issuer here; the issuer is enforced where it matters, on the `iss` claim of
/// every token [JwtValidator] accepts. That is what lets a provider whose
/// discovery document and tokens disagree about the issuer still be used.
///
/// The server's outbound TLS trust applies to the fetch and to the token
/// exchange. A domain trust store that holds only BLADE's own CA fails both
/// with "PKIX path building failed"; the installer seeds the store with the
/// JDK's public roots for this reason.
public final class OidcProvider {

	private final URI authorizationEndpoint;
	private final URI tokenEndpoint;
	private final String jwksUri;
	private final URI endSessionEndpoint;
	private final URI userinfoEndpoint;

	/// What the token endpoint hands back for a code. The ID token names the
	/// user; the access token is what the userinfo endpoint wants to see, and
	/// with some providers it is also where the groups are.
	public static final class Tokens {
		public final String idToken;
		public final String accessToken;

		public Tokens(String idToken, String accessToken) {
			this.idToken = idToken;
			this.accessToken = accessToken;
		}
	}

	public OidcProvider(URI authorizationEndpoint, URI tokenEndpoint, String jwksUri, URI endSessionEndpoint) {
		this(authorizationEndpoint, tokenEndpoint, jwksUri, endSessionEndpoint, null);
	}

	public OidcProvider(URI authorizationEndpoint, URI tokenEndpoint, String jwksUri, URI endSessionEndpoint,
			URI userinfoEndpoint) {
		this.authorizationEndpoint = authorizationEndpoint;
		this.tokenEndpoint = tokenEndpoint;
		this.jwksUri = jwksUri;
		this.endSessionEndpoint = endSessionEndpoint;
		this.userinfoEndpoint = userinfoEndpoint;
	}

	/// Fetch and parse the provider's discovery document.
	public static OidcProvider discover(String discoveryUrl) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(discoveryUrl).openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(10_000);
		c.setRequestProperty("Accept", "application/json");
		int status = c.getResponseCode();
		if (status != 200) {
			throw new IOException("discovery at " + discoveryUrl + " answered " + status);
		}
		try (InputStream in = c.getInputStream()) {
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	/// Parse a discovery document. Package-visible so a test can hand one in
	/// without a server.
	static OidcProvider parse(String json) throws IOException {
		JsonNode doc = new ObjectMapper().readTree(json);
		URI auth = uri(doc, "authorization_endpoint", true);
		URI token = uri(doc, "token_endpoint", true);
		JsonNode jwks = doc.get("jwks_uri");
		if (jwks == null || jwks.asText().isEmpty()) {
			throw new IOException("discovery document has no jwks_uri");
		}
		return new OidcProvider(auth, token, jwks.asText(), uri(doc, "end_session_endpoint", false),
				uri(doc, "userinfo_endpoint", false));
	}

	private static URI uri(JsonNode doc, String field, boolean required) throws IOException {
		JsonNode n = doc.get(field);
		if (n == null || n.asText().isEmpty()) {
			if (required) {
				throw new IOException("discovery document has no " + field);
			}
			return null;
		}
		return URI.create(n.asText());
	}

	public URI authorizationEndpoint() {
		return authorizationEndpoint;
	}

	public URI tokenEndpoint() {
		return tokenEndpoint;
	}

	public String jwksUri() {
		return jwksUri;
	}

	/// Where a sign-out goes, or null when the provider publishes none.
	public URI endSessionEndpoint() {
		return endSessionEndpoint;
	}

	/// Where the user's claims can be asked for with the access token, or null
	/// when the provider publishes none.
	public URI userinfoEndpoint() {
		return userinfoEndpoint;
	}

	/// Trade an authorization code for the tokens, with the client's secret
	/// and the PKCE verifier the login started with. Returns the ID token as
	/// the provider signed it; validating it is the caller's job.
	///
	/// The form is posted by hand over the same connection class discovery
	/// uses. The OAuth SDK's own sender, run inside the container, reached an
	/// identity domain with an empty body ("Request body is required"), and a
	/// token request is four fields and one header.
	public Tokens exchange(OidcLoginConfig cfg, String code, String codeVerifier) throws IOException {
		String form = "grant_type=authorization_code" + "&code=" + encode(code) + "&redirect_uri="
				+ encode(cfg.redirectUrl()) + "&code_verifier=" + encode(codeVerifier);
		String basic = Base64.getEncoder().encodeToString(
				(encode(cfg.clientId()) + ":" + encode(cfg.clientSecret())).getBytes(StandardCharsets.UTF_8));

		HttpURLConnection c = (HttpURLConnection) tokenEndpoint.toURL().openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(15_000);
		c.setRequestMethod("POST");
		c.setDoOutput(true);
		c.setRequestProperty("Authorization", "Basic " + basic);
		c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		c.setRequestProperty("Accept", "application/json");
		byte[] body = form.getBytes(StandardCharsets.UTF_8);
		c.setFixedLengthStreamingMode(body.length);
		try (OutputStream out = c.getOutputStream()) {
			out.write(body);
		}
		int status = c.getResponseCode();
		String answer;
		try (InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream()) {
			answer = (in == null) ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		if (status != 200) {
			// The provider's own words: a refusal that is not a standard error
			// object still says why.
			throw new IOException("token endpoint answered " + status + ": "
					+ answer.substring(0, Math.min(answer.length(), 400)));
		}
		JsonNode tokens = new ObjectMapper().readTree(answer);
		JsonNode idToken = tokens.get("id_token");
		if (idToken == null || idToken.asText().isEmpty()) {
			throw new IOException("token endpoint issued no ID token; is 'openid' in the requested scope?");
		}
		JsonNode accessToken = tokens.get("access_token");
		return new Tokens(idToken.asText(), accessToken == null ? null : accessToken.asText());
	}

	/// Ask the userinfo endpoint for the user's claims, as JSON, or return
	/// null when the provider publishes no such endpoint. The groups a provider
	/// leaves out of its ID token are usually here.
	public JsonNode userinfo(String accessToken) throws IOException {
		if (userinfoEndpoint == null || accessToken == null) {
			return null;
		}
		HttpURLConnection c = (HttpURLConnection) userinfoEndpoint.toURL().openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(15_000);
		c.setRequestProperty("Authorization", "Bearer " + accessToken);
		c.setRequestProperty("Accept", "application/json");
		int status = c.getResponseCode();
		String answer;
		try (InputStream in = (status >= 400) ? c.getErrorStream() : c.getInputStream()) {
			answer = (in == null) ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		if (status != 200) {
			throw new IOException("userinfo endpoint answered " + status + ": "
					+ answer.substring(0, Math.min(answer.length(), 400)));
		}
		return new ObjectMapper().readTree(answer);
	}

	private static String encode(String value) throws IOException {
		return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
	}
}
