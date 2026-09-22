package org.vorpal.blade.applications.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/// The outcome-to-treatment map is the contract with proxy-block's block list,
/// and a disposition over a catalog with no data source must degrade to a no-op
/// rather than throw: a disposition can never break the call it describes.
class DispositionServiceTest {

	@Test
	void outcomeMapsToTheProxyBlockTreatment() {
		assertEquals("review", DispositionService.treatmentFor("harassment"));
		assertEquals("review", DispositionService.treatmentFor("abuse"));
		assertEquals("tarpit", DispositionService.treatmentFor("robocall"));
		assertEquals("tarpit", DispositionService.treatmentFor("spam"));
		assertEquals("decline", DispositionService.treatmentFor("scam"));
		assertEquals("decline", DispositionService.treatmentFor("confirmed-scam"));
		assertEquals("decline", DispositionService.treatmentFor("suspected-scam"));
	}

	@Test
	void unknownAndNullOutcomeDefaultToTarpit() {
		assertEquals("tarpit", DispositionService.treatmentFor("something-else"));
		assertEquals("tarpit", DispositionService.treatmentFor(null));
	}

	@Test
	void caseIsIgnored() {
		assertEquals("decline", DispositionService.treatmentFor("SCAM"));
		assertEquals("review", DispositionService.treatmentFor("Harassment"));
	}

	@Test
	void dispositionOverAnUnconfiguredCatalogIsAHarmlessNoOp() {
		// No data source: every catalog write is a no-op, no analytics and no bus
		// under test, but the treatment is still computed and returned.
		Catalog catalog = new Catalog(null, null, null);
		DispositionService service = new DispositionService(catalog, 7, () -> null);
		DispositionService.Disposition d = new DispositionService.Disposition();
		d.vorpalId = "0BADF00D";
		d.ani = "5557654321";
		d.conversation = "conv-1";
		d.outcome = "confirmed-scam";
		d.action = DispositionService.ACTION_BLOCK;

		DispositionService.Result result = service.record(d, "operator");

		assertEquals("decline", result.treatment);
		assertFalse(result.blocked);
		assertFalse(result.labelled);
		assertFalse(result.published);
	}

	@Test
	void blockIsOnlyWrittenWhenTheActionAsksForIt() {
		Catalog catalog = new Catalog(null, null, null);
		DispositionService service = new DispositionService(catalog, 7, () -> null);
		DispositionService.Disposition d = new DispositionService.Disposition();
		d.ani = "5557654321";
		d.outcome = "legitimate";
		d.action = "none";

		DispositionService.Result result = service.record(d, "operator");

		assertEquals("tarpit", result.treatment, "a legitimate outcome still maps to a treatment, unused");
		assertFalse(result.blocked);
	}
}
