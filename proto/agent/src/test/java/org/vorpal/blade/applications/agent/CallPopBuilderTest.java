package org.vorpal.blade.applications.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedRequest;

/// [CallPopBuilder] is pure header parsing, so it tests without a container: a
/// [DetachedRequest] carries the headers a real INVITE would.
class CallPopBuilderTest {

	private static DetachedRequest invite(String from, String to) throws Exception {
		return new DetachedRequest("INVITE", from, to);
	}

	@Test
	void assertedIdentityBeatsFromForTheNumber() throws Exception {
		DetachedRequest r = invite("\"Jane Doe\" <sip:+15551230000@carrier.example>", "<sip:5559998888@pbx.example>");
		r.setHeader("P-Asserted-Identity", "<sip:+15557654321@carrier.example>");

		CallPop pop = CallPopBuilder.of(r, "call-1", CallerHistory.EMPTY);

		assertEquals("5557654321", pop.ani, "PAI number, not the From number");
		assertEquals("Jane Doe", pop.displayName);
		assertEquals("5559998888", pop.dialed);
		assertFalse(pop.anonymous);
		assertEquals("call-1", pop.callId);
	}

	@Test
	void screeningAndRateAreReadFromTheEdgeHeaders() throws Exception {
		DetachedRequest r = invite("<sip:+15551230000@carrier.example>", "<sip:5559998888@pbx.example>");
		r.setHeader("X-Call-Screen", "watch;reason=call-rate");
		r.setHeader("X-Call-Rate", "12");

		CallPop pop = CallPopBuilder.of(r, "call-2", CallerHistory.EMPTY);

		assertEquals("watch", pop.screenVerdict);
		assertEquals("call-rate", pop.screenReason);
		assertEquals(Integer.valueOf(12), pop.callRate);
	}

	@Test
	void fusedRiskBandComesFromItsOwnHeader() throws Exception {
		DetachedRequest r = invite("<sip:+15551230000@carrier.example>", "<sip:5559998888@pbx.example>");
		r.setHeader("X-Call-Screen", "watch;reason=call-rate");
		r.setHeader("X-Call-Risk", "suspect;score=0.82");

		CallPop pop = CallPopBuilder.of(r, "call-r", CallerHistory.EMPTY);

		assertEquals("suspect", pop.riskBand, "band from X-Call-Risk, not derived from screening");
		assertEquals("watch", pop.screenVerdict, "screening stays its own distinct signal");
	}

	@Test
	void noRiskHeaderMeansNotScored() throws Exception {
		DetachedRequest r = invite("<sip:+15551230000@carrier.example>", "<sip:5559998888@pbx.example>");
		CallPop pop = CallPopBuilder.of(r, "call-nr", CallerHistory.EMPTY);
		assertNull(pop.riskBand);
	}

	@Test
	void anonymousCallerIsFlaggedAndHistoryDefaultsEmpty() throws Exception {
		DetachedRequest r = invite("\"Anonymous\" <sip:anonymous@anonymous.invalid>", "<sip:5559998888@pbx.example>");

		CallPop pop = CallPopBuilder.of(r, "call-3", null);

		assertTrue(pop.anonymous);
		assertNull(pop.ani, "no NANP number to extract from an anonymous From");
		assertEquals(0, pop.history.callCount, "null history falls back to EMPTY");
	}
}
