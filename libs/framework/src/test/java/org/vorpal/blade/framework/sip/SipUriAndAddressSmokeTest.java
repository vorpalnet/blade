package org.vorpal.blade.framework.sip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;

/// Covers [DetachedSipURI] and [DetachedAddress], and through them the
/// [Callflow#copyParameters(URI,URI)] merge that every request builder relies on
/// to carry the user part and URI parameters onto a configured destination.
class SipUriAndAddressSmokeTest {

	@Nested
	@DisplayName("URI parsing and rendering")
	class Uris {

		@Test
		void roundTripsAFullUri() {
			DetachedSipURI uri = new DetachedSipURI("sip:alice@example.com:5080;transport=tcp");

			assertEquals("sip", uri.getScheme());
			assertEquals("alice", uri.getUser());
			assertEquals("example.com", uri.getHost());
			assertEquals(5080, uri.getPort());
			assertEquals("tcp", uri.getTransportParam());
			assertEquals("sip:alice@example.com:5080;transport=tcp", uri.toString());
		}

		@Test
		void handlesAHostOnlyUri() {
			DetachedSipURI uri = new DetachedSipURI("sip:10.0.0.5");

			assertNull(uri.getUser());
			assertEquals("10.0.0.5", uri.getHost());
			assertEquals(-1, uri.getPort());
			assertEquals("sip:10.0.0.5", uri.toString());
		}

		/// A flag parameter has no value and must not gain an '=' on the way out.
		@Test
		void keepsFlagParametersFlat() {
			DetachedSipURI uri = new DetachedSipURI("sip:proxy.example.com;lr");

			assertTrue(uri.getLrParam());
			assertEquals("sip:proxy.example.com;lr", uri.toString());
		}

		@Test
		void sipsIsSecure() {
			assertTrue(new DetachedSipURI("sips:alice@example.com").isSecure());
			assertFalse(new DetachedSipURI("sip:alice@example.com").isSecure());
		}

		@Test
		void cloneIsIndependent() {
			DetachedSipURI original = new DetachedSipURI("sip:alice@example.com");
			SipURI copy = original.clone();
			copy.setUser("bob");

			assertEquals("alice", original.getUser());
			assertEquals("bob", copy.getUser());
		}
	}

	@Nested
	@DisplayName("address parsing and rendering")
	class Addresses {

		@Test
		void separatesHeaderParametersFromUriParameters() {
			DetachedAddress address = new DetachedAddress("\"Alice\" <sip:alice@example.com;transport=tcp>;tag=abc123");

			assertEquals("Alice", address.getDisplayName());
			assertEquals("abc123", address.getParameter("tag"), "tag is a header parameter");
			assertNull(address.getURI().getParameter("tag"), "tag must not leak onto the URI");
			assertEquals("tcp", address.getURI().getParameter("transport"), "transport belongs to the URI");
		}

		@Test
		void acceptsABareUri() {
			DetachedAddress address = new DetachedAddress("sip:bob@example.com");

			assertNull(address.getDisplayName());
			assertEquals("sip:bob@example.com", address.getURI().toString());
		}

		@Test
		void roundTripsThroughToString() {
			String text = "\"Alice\" <sip:alice@example.com>;tag=xyz";
			assertEquals(text, new DetachedAddress(text).toString());
		}
	}

	@Nested
	@DisplayName("copyParameters merge")
	class Merge {

		/// The behaviour that makes a configured destination usable: a bare host
		/// inherits the inbound user part, so `sip:10.0.0.5` becomes
		/// `sip:alice@10.0.0.5`. Losing this is what a plain setRequestURI would do.
		@Test
		void fillsInTheUserPart() throws Exception {
			URI inbound = new DetachedSipURI("sip:alice@caller.example.com");
			URI destination = new DetachedSipURI("sip:10.0.0.5:5060");

			URI merged = Callflow.copyParameters(inbound, destination);

			assertEquals("sip:alice@10.0.0.5:5060", merged.toString());
		}

		@Test
		void carriesParametersTheDestinationLacks() throws Exception {
			URI inbound = new DetachedSipURI("sip:alice@caller.example.com;transport=tcp;custom=keep");
			URI destination = new DetachedSipURI("sip:10.0.0.5");

			Callflow.copyParameters(inbound, destination);

			assertEquals("tcp", destination.getParameter("transport"));
			assertEquals("keep", destination.getParameter("custom"));
		}

		@Test
		void doesNotOverrideWhatTheDestinationAlreadySets() throws Exception {
			URI inbound = new DetachedSipURI("sip:alice@caller.example.com;transport=tcp");
			URI destination = new DetachedSipURI("sip:10.0.0.5;transport=udp");

			Callflow.copyParameters(inbound, destination);

			assertEquals("udp", destination.getParameter("transport"));
		}

		/// A dialog tag must never be copied onto a request URI.
		@Test
		void neverCopiesTag() throws Exception {
			URI inbound = new DetachedSipURI("sip:alice@caller.example.com;tag=should-not-travel");
			URI destination = new DetachedSipURI("sip:10.0.0.5");

			Callflow.copyParameters(inbound, destination);

			assertNull(destination.getParameter("tag"));
		}

		/// A null destination comes back null rather than throwing — which is why
		/// v3.Callflow.createRequest guards for it before calling setRequestURI.
		@Test
		void nullDestinationComesBackNull() throws Exception {
			assertNull(Callflow.copyParameters(new DetachedSipURI("sip:alice@example.com"), null));
		}
	}
}
