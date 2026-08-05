package org.vorpal.blade.services.proxy.balancer;

/// The OPTIONS ping verdict, as pure logic ([OptionsPingCallflow] applies it).
///
/// Default rule: ANY final response except 408/503 proves the box is alive —
/// a 405 Method Not Allowed is a third-party endpoint that dislikes OPTIONS,
/// not a dead one. 408 (locally generated, nothing answered) and 503 (the
/// endpoint said so: overloaded, draining, or starting) mark it down.
///
/// `require2xx` tightens the rule for endpoints known to be BLADE engines:
/// only a 2xx marks up. A booting engine's container can answer errors before
/// the options app deploys, and under the default rule such a response would
/// enroll a half-started node — with the options app installed, a healthy
/// engine always affirms with 200, so anything else is "not ready".
final class PingVerdict {

	private PingVerdict() {
	}

	/// Does this final response status mark the endpoint up?
	static boolean marksUp(int status, boolean require2xx) {
		if (status == 408 || status == 503) {
			return false;
		}
		return !require2xx || (status >= 200 && status < 300);
	}

}
