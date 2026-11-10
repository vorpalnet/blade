package org.vorpal.blade.framework.v3.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/// Phone numbers go to the model as same-length stand-ins and come back real;
/// ports, CSeq numbers and addresses are left alone.
class NumberMaskTest {

	@Test
	void numbersBecomeStandInsOfTheSameLengthAndComeBack() {
		NumberMask m = new NumberMask();
		String text = "From: <sip:+14085551212@x>;tag=1\r\nTo: <sip:18005550100@10.1.1.124:5060>\r\nCSeq: 1 INVITE";
		String masked = m.mask(text);
		assertFalse(masked.contains("14085551212"), masked);
		assertFalse(masked.contains("18005550100"), masked);
		assertEquals(text.length(), masked.length(), masked);
		// Short runs are not numbers to hide.
		assertEquals(true, masked.contains("10.1.1.124:5060"), masked);
		assertEquals(text, m.unmask(masked));
	}

	@Test
	void oneNumberOneStandInAcrossMessages() {
		NumberMask m = new NumberMask();
		String a = m.mask("18005551234");
		assertEquals(a, m.mask("call 18005551234 now").substring(5, 16));
		assertNotEquals(a, m.mask("18005559999"));
		assertEquals("rule for 18005551234", m.unmask("rule for " + a));
	}
}
