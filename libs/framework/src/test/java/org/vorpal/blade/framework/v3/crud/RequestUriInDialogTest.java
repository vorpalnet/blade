package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;

/// A rule that rewrites the Request-URI changes a call's first request and
/// never one inside the dialog, whose Request-URI is the far end's Contact.
class RequestUriInDialogTest {

	private static final DetachedSipFactory FACTORY = new DetachedSipFactory();

	@BeforeEach
	void wire() {
		SettingsManager.setSipFactory(FACTORY);
		SettingsManager.setSipLogger(new CapturingLogger());
	}

	private static DetachedRequest invite(boolean initial, String requestUri) throws Exception {
		DetachedRequest invite = new DetachedRequest(new DetachedApplicationSession("app"), "INVITE",
				"<sip:alice@example.com>;tag=a1", "<sip:bob@example.com>");
		invite.setInitial(initial);
		invite.setRequestURI(FACTORY.createURI(requestUri));
		return invite;
	}

	private static final UpdateOperation READDRESS = new UpdateOperation("requestURI",
			"^sip:[^@]+@(?<host>.*)$", "sip:carol@${host}");

	@Test
	void theFirstInviteIsReaddressed() throws Exception {
		DetachedRequest invite = invite(true, "sip:bob@example.com");
		READDRESS.process(invite);
		assertEquals("sip:carol@example.com", invite.getRequestURI().toString());
	}

	@Test
	void aReinviteKeepsTheContact() throws Exception {
		DetachedRequest reinvite = invite(false, "sip:bob@10.9.8.7:5060");
		READDRESS.process(reinvite);
		assertEquals("sip:bob@10.9.8.7:5060", reinvite.getRequestURI().toString());
	}
}
