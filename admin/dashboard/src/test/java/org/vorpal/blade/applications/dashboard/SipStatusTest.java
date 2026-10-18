package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.applications.dashboard.SipStatus.Kind;

/// Who has to act on a refusal: equipment, configuration, nobody.
class SipStatusTest {

	@Test
	void equipmentNotWorking() {
		for (int s : new int[] { 408, 500, 502, 503, 504, 599 }) {
			assertEquals(Kind.FAILING, SipStatus.kind(s), String.valueOf(s));
		}
	}

	@Test
	void configurationRefused() {
		for (int s : new int[] { 403, 404, 484, 488, 482, 483, 438, 501, 513, 604, 606, 608, 499 }) {
			assertEquals(Kind.MISCONFIGURED, SipStatus.kind(s), String.valueOf(s));
		}
	}

	@Test
	void aPersonSaidNo() {
		for (int s : new int[] { 480, 486, 600, 603, 699 }) {
			assertEquals(Kind.CALLEE, SipStatus.kind(s), String.valueOf(s));
		}
	}

	@Test
	void theCallWorkingAsDesigned() {
		for (int s : new int[] { 401, 407, 422, 487, 491, 302 }) {
			assertEquals(Kind.NORMAL, SipStatus.kind(s), String.valueOf(s));
		}
	}

	@Test
	void everyCodeHasWords() {
		assertEquals("number or route not provisioned", SipStatus.meaning(404));
		assertEquals("server error", SipStatus.meaning(599));
	}
}
