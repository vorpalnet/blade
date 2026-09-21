package org.vorpal.blade.applications.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/// The category-to-treatment map is the contract with proxy-block's block list,
/// and a report over a catalog with no data source must degrade to a no-op
/// rather than throw: a report can never break the call it is reporting.
class ReportServiceTest {

	@Test
	void categoryMapsToTheProxyBlockTreatment() {
		assertEquals("review", ReportService.treatmentFor("harassment"));
		assertEquals("review", ReportService.treatmentFor("abuse"));
		assertEquals("tarpit", ReportService.treatmentFor("robocall"));
		assertEquals("tarpit", ReportService.treatmentFor("spam"));
		assertEquals("decline", ReportService.treatmentFor("scam"));
		assertEquals("decline", ReportService.treatmentFor("fraud"));
	}

	@Test
	void unknownAndNullCategoryDefaultToTarpit() {
		assertEquals("tarpit", ReportService.treatmentFor("something-else"));
		assertEquals("tarpit", ReportService.treatmentFor(null));
	}

	@Test
	void caseIsIgnored() {
		assertEquals("decline", ReportService.treatmentFor("SCAM"));
		assertEquals("review", ReportService.treatmentFor("Harassment"));
	}

	@Test
	void reportOverAnUnconfiguredCatalogIsAHarmlessNoOp() {
		// No data source: every catalog write is a no-op, the event bus is not
		// ready under test, but the treatment is still computed and returned.
		Catalog catalog = new Catalog(null, null, null);
		ReportService reports = new ReportService(catalog, 7);

		ReportService.Result result = reports.record("5557654321", "conv-1", "call-1", "scam", "operator");

		assertEquals("decline", result.treatment);
		assertFalse(result.blocked);
		assertFalse(result.labelled);
		assertFalse(result.published);
	}
}
