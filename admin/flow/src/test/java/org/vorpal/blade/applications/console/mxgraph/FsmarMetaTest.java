package org.vorpal.blade.applications.console.mxgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Locks the model-derived value sets to what the editor is built around.
///
/// [FsmarMeta] reads these off the framework classes, so this test is the
/// tripwire: if a selector subclass, routing region or trigger method is added
/// or renamed in the model, the build fails here rather than the editor
/// quietly refusing to offer the new value (or the validator rejecting it).
/// When that happens the fix is to update this list *and* the browser-side
/// fallbacks in `flowMeta.js` — not to loosen the assertion.
class FsmarMetaTest {

	@Test
	@DisplayName("every list was read from the model, not a fallback")
	void nothingFellBack() {
		// FsmarMeta degrades to built-in defaults rather than throwing, so the
		// build is where a model rename has to be caught. A non-empty warning
		// list means reflection missed something and the editor is running on
		// stale literals.
		assertTrue(FsmarMeta.warnings().isEmpty(),
				() -> "FsmarMeta fell back instead of reading the model: " + FsmarMeta.warnings());
	}

	@Test
	@DisplayName("trigger methods are the initial-request set")
	void triggerMethods() {
		assertEquals(
				Arrays.asList("INVITE", "REGISTER", "OPTIONS", "SUBSCRIBE",
						"PUBLISH", "MESSAGE", "NOTIFY", "REFER"),
				FsmarMeta.METHODS);
	}

	@Test
	@DisplayName("no in-dialog method is offered as a trigger key")
	void noInDialogMethods() {
		// The application router is only ever invoked for initial requests.
		for (String inDialog : Arrays.asList("BYE", "CANCEL", "ACK", "INFO", "PRACK", "UPDATE")) {
			assertTrue(!FsmarMeta.METHOD_SET.contains(inDialog),
					() -> inDialog + " is in-dialog and can never reach the application router");
		}
	}

	@Test
	@DisplayName("regions match Transition.Region")
	void regions() {
		assertEquals(Arrays.asList("ORIGINATING", "TERMINATING", "NEUTRAL"), FsmarMeta.REGIONS);
	}

	@Test
	@DisplayName("route modifiers match the container's SipRouteModifier")
	void routeModifiers() {
		assertEquals(4, FsmarMeta.ROUTE_MODIFIERS.size(),
				() -> "unexpected SipRouteModifier values: " + FsmarMeta.ROUTE_MODIFIERS);
		assertTrue(FsmarMeta.ROUTE_MODIFIER_SET.containsAll(
				Arrays.asList("ROUTE", "ROUTE_BACK", "ROUTE_FINAL", "NO_ROUTE")),
				() -> "missing a route modifier: " + FsmarMeta.ROUTE_MODIFIERS);
	}

	@Test
	@DisplayName("selector types match Selector's @JsonSubTypes")
	void selectorTypes() {
		assertEquals(Arrays.asList("attribute", "json", "xml", "sdp", "regex", "table"),
				FsmarMeta.SELECTOR_TYPES);
	}

	@Test
	@DisplayName("the browser-side fallbacks agree with the model")
	void browserFallbacksAgree() throws IOException {
		// flowMeta.js carries a copy for when /fsmarMeta is unreachable. A
		// fallback that disagrees with the model is worse than none, so keep
		// the two in step.
		Path js = Paths.get("src/main/webapp/js/flowMeta.js");
		assertTrue(Files.exists(js), () -> "missing " + js.toAbsolutePath());
		String source = new String(Files.readAllBytes(js), StandardCharsets.UTF_8);

		assertContainsList(source, "methods", FsmarMeta.METHODS);
		assertContainsList(source, "regions", FsmarMeta.REGIONS);
		assertContainsList(source, "routeModifiers", FsmarMeta.ROUTE_MODIFIERS);
		assertContainsList(source, "selectorTypes", FsmarMeta.SELECTOR_TYPES);
	}

	private static void assertContainsList(String source, String key, List<String> values) {
		for (String v : values) {
			assertTrue(source.contains("'" + v + "'"),
					() -> "flowMeta.js fallback for " + key + " is missing '" + v + "'");
		}
	}
}
