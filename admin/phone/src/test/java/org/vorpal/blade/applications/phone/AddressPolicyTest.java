package org.vorpal.blade.applications.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// Which address a signed-in user can be issued a token for.
///
/// The interesting cases are the two ends: a deployment that binds each person
/// to one address, and one that does not — because the second is what makes the
/// app testable from a single `weblogic` account, and it must give up the
/// restriction *without* giving up the signature or the audit trail.
class AddressPolicyTest {

	private static PhoneSettings settings(boolean allowChosen) {
		PhoneSettings s = new PhoneSettings();
		s.setAorDomain("vorpal.net");
		s.setAllowChosenAddress(allowChosen);
		return s;
	}

	@Nested
	@DisplayName("the default address")
	class Default {

		@Test
		void isTheUsernamePlusTheConfiguredDomain() {
			assertEquals("weblogic@vorpal.net", AddressPolicy.defaultAddress("weblogic", settings(true)));
		}

		@Test
		void isWhatYouGetWhenYouAskForNothing() throws Exception {
			assertEquals("weblogic@vorpal.net", AddressPolicy.resolve("weblogic", null, settings(false)));
			assertEquals("weblogic@vorpal.net", AddressPolicy.resolve("weblogic", "   ", settings(false)));
		}

		@Test
		void canAlwaysBeAskedForByName() throws Exception {
			// Even with choosing switched off, echoing back your own address must
			// work — otherwise a page that round-trips the field breaks.
			assertEquals("weblogic@vorpal.net",
					AddressPolicy.resolve("weblogic", "weblogic@vorpal.net", settings(false)));
		}
	}

	@Nested
	@DisplayName("when a chosen address is allowed")
	class Chosen {

		@Test
		void issuesTheAddressAsked() throws Exception {
			assertEquals("alice@vorpal.net", AddressPolicy.resolve("weblogic", "alice@vorpal.net", settings(true)));
		}

		@Test
		void allowsAnyDomain() throws Exception {
			// Deliberately unbounded: inventing a domain restriction nobody asked
			// for would block the exact cross-domain call this app exists to test.
			assertEquals("bob@example.co.uk",
					AddressPolicy.resolve("weblogic", "bob@example.co.uk", settings(true)));
		}

		@Test
		void trimsSurroundingWhitespace() throws Exception {
			assertEquals("alice@vorpal.net", AddressPolicy.resolve("weblogic", "  alice@vorpal.net  ", settings(true)));
		}

		@Test
		void stillRejectsSomethingThatCouldNeverReceiveACall() {
			// InboundToBrowser resolves an inbound request URI to exactly
			// user@host, so anything else registers and then never rings.
			for (String bad : new String[] {
					"alice",                    // no host
					"@vorpal.net",              // no user
					"alice@",                   // no host
					"sip:alice@vorpal.net",     // a URI, not an address
					"alice@vorpal.net:5060",    // port belongs to the gateway, not the address
					"alice bob@vorpal.net",     // whitespace
					"alice@vorpal.net\r\nTo: x" // header injection
			}) {
				AddressPolicy.AddressRejected e = assertThrows(AddressPolicy.AddressRejected.class,
						() -> AddressPolicy.resolve("weblogic", bad, settings(true)),
						"should have rejected: " + bad);
				assertEquals(400, e.getStatus());
			}
		}
	}

	@Nested
	@DisplayName("when it is not allowed")
	class Restricted {

		@Test
		void refusesAnyOtherAddress() {
			AddressPolicy.AddressRejected e = assertThrows(AddressPolicy.AddressRejected.class,
					() -> AddressPolicy.resolve("weblogic", "ceo@vorpal.net", settings(false)));

			assertEquals(403, e.getStatus());
		}

		@Test
		void saysWhichAddressYouDoGetAndWhichSettingChangesThat() {
			AddressPolicy.AddressRejected e = assertThrows(AddressPolicy.AddressRejected.class,
					() -> AddressPolicy.resolve("weblogic", "ceo@vorpal.net", settings(false)));

			assertTrue(e.getMessage().contains("weblogic@vorpal.net"));
			assertTrue(e.getMessage().contains("allowChosenAddress"));
		}
	}

	@Test
	void defaultsToAllowingAChosenAddress() {
		// A browser-to-browser call needs two addresses; deployments have one
		// operator account. Off by default would make the app untestable.
		assertTrue(new PhoneSettings().isAllowChosenAddress());
	}
}
