package org.vorpal.blade.services.webrtc;

import org.vorpal.blade.framework.v3.security.JwtAuthConfig;

/// Default configuration written on first deployment.
///
/// Ships with browser authentication **on**. The `jwksUri` is left blank because
/// only the deployment knows its admin host, and a blank one fails closed: with
/// authentication enabled and no key source, the gateway refuses every browser
/// and says why. That is the right way round — an operator who has not finished
/// configuring gets a service that does not work, not one that works and is open.
public class WebrtcSettingsSample extends WebrtcSettings {
	private static final long serialVersionUID = 1L;

	public WebrtcSettingsSample() {
		JwtAuthConfig jwt = getJwt();
		jwt.setEnabled(true);
		jwt.setIssuer("urn:blade:phone");
		jwt.setAudience("urn:blade:webrtc");
		jwt.setRolesClaim("groups");
	}
}
