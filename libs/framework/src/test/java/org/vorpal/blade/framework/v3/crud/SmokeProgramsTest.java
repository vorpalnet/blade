package org.vorpal.blade.framework.v3.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Runs the CRUD smoke programs under the build. They are `main()` programs
/// for running by hand; without this the build never ran them, and checks
/// went stale unnoticed.
class SmokeProgramsTest {

	@Test
	void crud() throws Exception {
		assertEquals(0, CrudSmokeTest.run(), "CrudSmokeTest failures (see its FAIL lines)");
	}

	@Test
	void previewEngine() throws Exception {
		assertEquals(0, PreviewEngineSmokeTest.run(), "PreviewEngineSmokeTest failures (see its FAIL lines)");
	}

	@Test
	void examples() throws Exception {
		assertEquals(0, ExamplesSmokeTest.run(), "ExamplesSmokeTest failures (see its FAIL lines)");
	}
}
