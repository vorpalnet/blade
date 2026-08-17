package org.vorpal.blade.services.webrtc;

import org.vorpal.blade.framework.v3.security.JwtAuthConfig;

/// Default configuration written on first deployment.
///
/// Ships with browser authentication **on**. The `jwksUri` is left blank because
/// only the deployment knows its admin host, and a blank one fails closed: with
/// authentication enabled and no key source, the gateway refuses every browser
/// and says why. That is the right way round — an operator who has not finished
/// configuring gets a service that does not work, not one that works and is open.
///
/// ## Analytics ships configured but off
///
/// The selectors are filled in, and `analytics.enabled` is left at its default of
/// `false` — the same way every other BLADE application samples itself. Turning it
/// on is one edit, and it is deliberately the operator's: these events are call
/// detail records, and a telephony product should not begin keeping them because
/// nobody said not to.
///
/// **`analytics.enabled` is the switch, not `events.enabled`.** The two are easy to
/// confuse and behave differently: `AsyncSipServlet` stands the JMS publisher up on
/// either one, but `SettingsManager.collecting()` — which decides whether a call
/// fact is built at all — reads only `analytics`. Setting `events.enabled` alone
/// therefore gives a live connection to the bus that carries no `call.*` facts,
/// while `Analytics.sessionStart`/`sessionStop`, which are gated on neither, still
/// publish session events whose `appStartedAt` was never set because
/// `Analytics.applicationStart()` runs only when analytics is enabled. Set
/// `analytics.enabled` and both halves are consistent.
public class WebrtcSettingsSample extends WebrtcSettings {
	private static final long serialVersionUID = 1L;

	public WebrtcSettingsSample() {
		JwtAuthConfig jwt = getJwt();
		jwt.setEnabled(true);
		jwt.setIssuer("urn:blade:phone");
		jwt.setAudience("urn:blade:webrtc");
		jwt.setRolesClaim("groups");

		this.analytics = new AnalyticsWebrtcSample();
	}
}
