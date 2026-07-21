package org.vorpal.blade.proto.gateway;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// `type: "register-digest"` — keep the trunk alive with an outbound SIP REGISTER and
/// HTTP‑digest authentication on the 401/407 challenge (Flowroute and most credentialed
/// SIP trunks). Refreshed by a SIP servlet timer before {@link #getExpires()} lapses.
@JsonPropertyOrder({ "type", "userId", "authName", "password", "expires", "allow", "userAgent" })
public class RegisterDigestStyle extends RegistrationStyle {
	private static final long serialVersionUID = 1L;

	private String userId;
	private String authName;
	private String password;
	private int expires = 300;
	private String allow = "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO, REFER, NOTIFY, MESSAGE, SUBSCRIBE";
	private String userAgent = "BLADE Gateway";

	@Override
	public TrunkRegistrar newRegistrar(VirtualGateway gateway) {
		return new RegisterCallflow(gateway, this);
	}

	@Override
	public String outboundIdentity() {
		return userId; // present the registered account/DID on outbound calls
	}

	@JsonPropertyDescription("SIP account / DID used as the address‑of‑record (the From/To user of the REGISTER).")
	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	@JsonPropertyDescription("Digest authentication username (often differs from the userId / DID).")
	public String getAuthName() {
		return authName;
	}

	public void setAuthName(String authName) {
		this.authName = authName;
	}

	@JsonPropertyDescription("Trunk password. Encrypted at rest by the Configurator ({CLEARTEXT}→{AES}); never store plaintext.")
	@FormLayout(password = true)
	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	@JsonPropertyDescription("Registration lifetime in seconds; the gateway re‑REGISTERs shortly before this lapses.")
	public int getExpires() {
		return expires;
	}

	public void setExpires(int expires) {
		this.expires = expires;
	}

	@JsonPropertyDescription("Value of the Allow header advertised in the REGISTER.")
	public String getAllow() {
		return allow;
	}

	public void setAllow(String allow) {
		this.allow = allow;
	}

	@JsonPropertyDescription("Value of the User-Agent header advertised in the REGISTER.")
	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}
}
