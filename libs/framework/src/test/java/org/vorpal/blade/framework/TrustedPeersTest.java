package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

/// A caller can write BLADE's own headers. They are believed only from a
/// trusted hop; from outside, the call is first-touch and its origin is its
/// transport peer.
class TrustedPeersTest {

	private static final String FORGED = "0BADF00D;origin=10.20.0.5;ts=18F3A2B4C10;se=100000000";

	@BeforeEach
	void install() {
		System.clearProperty(TrustedPeers.PROPERTY);
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		Callflow.setLogger(new CapturingLogger());
	}

	@AfterEach
	void remove() {
		System.clearProperty(TrustedPeers.PROPERTY);
		Callflow.setSipFactory(null);
		Callflow.setSipUtil(null);
		Callflow.setLogger(null);
	}

	/// An INVITE arriving from `peer`, as the container would present it.
	private static DetachedRequest inviteFrom(String peer) throws Exception {
		DetachedApplicationSession appSession = new DetachedApplicationSession("test");
		DetachedRequest request = new DetachedRequest(appSession, "INVITE") {
			private static final long serialVersionUID = 1L;

			@Override
			public String getRemoteAddr() {
				return peer;
			}

			@Override
			public String getInitialRemoteAddr() {
				return null;
			}
		};
		request.setSession(new DetachedSipSession(appSession));
		request.setRoutingDirective(SipApplicationRoutingDirective.NEW, null);
		request.setHeader("Via", "SIP/2.0/UDP 10.20.0.5:5060;branch=z9hG4bK1;received=10.20.0.5");
		request.setHeader(Callflow.X_VORPAL_ID, FORGED);
		return request;
	}

	@Test
	void externalPeerIsNotTrusted() throws Exception {
		assertFalse(TrustedPeers.isTrusted(inviteFrom("203.0.113.9")));
	}

	@Test
	void loopbackMissingPeerAndContainerHandoffAreTrusted() throws Exception {
		assertTrue(TrustedPeers.isTrusted(inviteFrom("127.0.0.1")));
		assertTrue(TrustedPeers.isTrusted(inviteFrom(null)));

		DetachedRequest handedOn = inviteFrom("203.0.113.9");
		handedOn.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, null);
		assertTrue(TrustedPeers.isTrusted(handedOn));
	}

	@Test
	void configuredSubnetIsTrusted() throws Exception {
		System.setProperty(TrustedPeers.PROPERTY, "198.51.100.0/24, 203.0.113.9");
		assertTrue(TrustedPeers.isTrusted(inviteFrom("203.0.113.9")));
		assertTrue(TrustedPeers.isTrusted(inviteFrom("198.51.100.77")));
		assertFalse(TrustedPeers.isTrusted(inviteFrom("198.51.101.1")));
	}

	@Test
	void forgedOriginDoesNotBecomeOriginIp() throws Exception {
		// Neither the X-Vorpal-ID origin nor the caller's own Via is believed.
		assertEquals("203.0.113.9", Selector.readSource(inviteFrom("203.0.113.9"), "originIP"));
	}

	@Test
	void trustedHopOriginIsBelieved() throws Exception {
		System.setProperty(TrustedPeers.PROPERTY, "203.0.113.0/24");
		assertEquals("10.20.0.5", Selector.readSource(inviteFrom("203.0.113.9"), "originIP"));
	}

	@Test
	void externalCallGetsItsOwnIdentityAndLifetime() throws Exception {
		DetachedRequest request = inviteFrom("203.0.113.9");
		String id = Callflow.getVorpalSessionId(request);

		assertFalse(id.endsWith("0BADF00D"), id);
		assertNull(request.getApplicationSession().getAttribute("VORPAL_SESSION_EXPIRES"));
	}

	@Test
	void trustedHopIdentityIsAdoptedWithLifetimeCapped() throws Exception {
		System.setProperty(TrustedPeers.PROPERTY, "203.0.113.0/24");
		DetachedRequest request = inviteFrom("203.0.113.9");
		String id = Callflow.getVorpalSessionId(request);

		// The detached Parameterable parser prefixes a bare value with "sip:";
		// what matters is that the upstream id was adopted, not minted.
		assertTrue(id.endsWith("0BADF00D"), id);
		assertEquals(Callflow.maxSessionMinutes(), request.getApplicationSession().getAttribute("VORPAL_SESSION_EXPIRES"));
	}
}
