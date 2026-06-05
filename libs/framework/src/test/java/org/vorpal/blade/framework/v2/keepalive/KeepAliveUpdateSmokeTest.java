package org.vorpal.blade.framework.v2.keepalive;

import java.util.Arrays;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.KeepAliveParameters;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.testing.DummyApplicationSession;
import org.vorpal.blade.framework.v2.testing.DummySipSession;

/// Smoke test for the UPDATE-style keep-alive decision logic:
/// [KeepAlive#allowsUpdate] (Allow header parsing),
/// [KeepAlive#updateStyleConfigured] (config gate) and
/// [KeepAlive#supportsUpdate] (per-leg capability flag).
///
/// Note: `KeepAlive` unqualified means the callflow class (same package);
/// the config enum is always written `KeepAliveParameters.KeepAlive`.
public final class KeepAliveUpdateSmokeTest {
	private static int passed;
	private static int failed;

	/// DummySipSession.isValid() is hardwired to false; the decision logic
	/// requires a valid session, so flip it for testing.
	private static final class ValidDummySipSession extends DummySipSession {
		private static final long serialVersionUID = 1L;

		ValidDummySipSession(SipApplicationSession appSession) {
			super(appSession);
		}

		@Override
		public boolean isValid() {
			return true;
		}
	}

	public static void main(String[] args) {
		testAllowParsing();
		testUpdateStyleConfigured();
		testSupportsUpdate();

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}

	private static void testAllowParsing() {
		check("allow.null-list", KeepAlive.allowsUpdate(null) == null);
		check("allow.null-entry", KeepAlive.allowsUpdate(list((String) null)) == null);
		check("allow.empty-entry", KeepAlive.allowsUpdate(list("")) == null);
		check("allow.typical", Boolean.TRUE.equals(KeepAlive.allowsUpdate(list("INVITE, ACK, CANCEL, BYE, UPDATE"))));
		check("allow.single", Boolean.TRUE.equals(KeepAlive.allowsUpdate(list("UPDATE"))));
		check("allow.case-insensitive", Boolean.TRUE.equals(KeepAlive.allowsUpdate(list("invite, update"))));
		check("allow.whitespace", Boolean.TRUE.equals(KeepAlive.allowsUpdate(list("INVITE ,  UPDATE "))));
		check("allow.multi-header", Boolean.TRUE.equals(KeepAlive.allowsUpdate(list("INVITE, ACK", "UPDATE"))));
		check("allow.without-update", Boolean.FALSE.equals(KeepAlive.allowsUpdate(list("INVITE, ACK, CANCEL, BYE"))));
		check("allow.no-substring-match", Boolean.FALSE.equals(KeepAlive.allowsUpdate(list("XUPDATE, UPDATEX"))));
	}

	private static void testUpdateStyleConfigured() {
		SessionParameters saved = Callflow.getSessionParameters();
		try {
			Callflow.setSessionParameters(null);
			check("style.no-session-params", !KeepAlive.updateStyleConfigured());

			Callflow.setSessionParameters(new SessionParameters());
			check("style.no-keepalive-block", !KeepAlive.updateStyleConfigured());

			Callflow.setSessionParameters(new SessionParameters().setKeepAlive(new KeepAliveParameters()));
			check("style.null-style", !KeepAlive.updateStyleConfigured());

			Callflow.setSessionParameters(new SessionParameters()
					.setKeepAlive(new KeepAliveParameters().setStyle(KeepAliveParameters.KeepAlive.DISABLED)));
			check("style.disabled", !KeepAlive.updateStyleConfigured());

			Callflow.setSessionParameters(new SessionParameters()
					.setKeepAlive(new KeepAliveParameters().setStyle(KeepAliveParameters.KeepAlive.REINVITE)));
			check("style.reinvite", !KeepAlive.updateStyleConfigured());

			Callflow.setSessionParameters(new SessionParameters()
					.setKeepAlive(new KeepAliveParameters().setStyle(KeepAliveParameters.KeepAlive.UPDATE)));
			check("style.update", KeepAlive.updateStyleConfigured());
		} finally {
			Callflow.setSessionParameters(saved);
		}
	}

	private static void testSupportsUpdate() {
		DummyApplicationSession appSession = new DummyApplicationSession("KeepAliveUpdateSmokeTest");

		ValidDummySipSession session = new ValidDummySipSession(appSession);
		check("supports.absent-attribute", !KeepAlive.supportsUpdate(session));

		session.setAttribute(Callflow.ALLOW_UPDATE, Boolean.TRUE);
		check("supports.true", KeepAlive.supportsUpdate(session));

		session.setAttribute(Callflow.ALLOW_UPDATE, Boolean.FALSE);
		check("supports.false", !KeepAlive.supportsUpdate(session));

		DummySipSession invalid = new DummySipSession(appSession); // isValid() == false
		invalid.setAttribute(Callflow.ALLOW_UPDATE, Boolean.TRUE);
		check("supports.invalid-session", !KeepAlive.supportsUpdate(invalid));
	}

	private static List<String> list(String... values) {
		return Arrays.asList(values);
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}
}
