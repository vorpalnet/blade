package org.vorpal.blade.framework.v3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParallelRaceTest {

	@Test
	void aDefiniteFailureOutranksARetryableOneAndOtherwiseTheEarlierStands() {
		assertTrue(Callflow.outranks(false, true), "a 404 after a 503 answers the call");
		assertFalse(Callflow.outranks(true, false), "a 503 after a 404 does not");
		assertFalse(Callflow.outranks(false, false), "two definite failures: the earlier");
		assertFalse(Callflow.outranks(true, true), "two retryable failures: the earlier");
	}
}
