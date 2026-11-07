package org.vorpal.blade.services.gateway;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// `type: "register-digest"` — keep the trunk alive with an outbound SIP REGISTER and
/// HTTP‑digest authentication on the 401/407 challenge (most credentialed
/// SIP trunks). Refreshed by a SIP servlet timer before {@link #getExpires()} lapses.
///
/// With {@link #getRegister()} false the trunk sends no REGISTER, yet its credentials still
/// answer the carrier's digest challenge on outbound calls. Use it when two trunks reach the
/// same carrier account through different registrars: a carrier that holds two bindings for
/// one account delivers each inbound call to both, so the same call arrives twice. Let one
/// trunk register and keep the other for outbound only.
@JsonPropertyOrder({ "type", "register", "userId", "authName", "password", "expires", "contact", "allow",
		"userAgent" })
public class RegisterDigestStyle extends RegistrationStyle {
	private static final long serialVersionUID = 1L;

	private boolean register = true;
	private String userId;
	private String authName;
	private String password;
	private int expires = 300;
	private String contact;
	private String allow = "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO, REFER, NOTIFY, MESSAGE, SUBSCRIBE";
	private String userAgent = "BLADE Gateway";

	@Override
	public TrunkRegistrar newRegistrar(VirtualGateway gateway) {
		return register ? new RegisterCallflow(gateway, this) : null;
	}

	@Override
	public String outboundIdentity() {
		return userId; // present the registered account/DID on outbound calls
	}

	@JsonPropertyDescription("Send a REGISTER for this trunk. False keeps the credentials for outbound digest "
			+ "challenges but takes no inbound calls by registration. When two trunks share one carrier account, "
			+ "register only one of them, or the carrier delivers each inbound call to both.")
	public boolean getRegister() {
		return register;
	}

	public void setRegister(boolean register) {
		this.register = register;
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

	@JsonPropertyDescription("Optional: the Contact URI the REGISTER binds, where the carrier sends this trunk's "
			+ "inbound calls, e.g. sip:12345678@203.0.113.10:5060;transport=tcp. Use the engine's public address when "
			+ "it sits behind NAT. Unset sends the container's own Contact, which carries its listen address and a "
			+ "placeholder user.")
	public String getContact() {
		return contact;
	}

	public void setContact(String contact) {
		this.contact = contact;
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
