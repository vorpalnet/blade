package org.vorpal.blade.applications.console.mxgraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.sip.ar.SipRouteModifier;

import org.vorpal.blade.framework.v2.config.FormKeyEnum;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;
import org.vorpal.blade.framework.v3.fsmar.State;
import org.vorpal.blade.framework.v3.fsmar.Transition;

import com.fasterxml.jackson.annotation.JsonSubTypes;

/// The FSMAR model's closed value sets, **read from the model** rather than
/// transcribed.
///
/// These lists were previously hand-copied into four places — the validator's
/// whitelists, the transition panel's `<select>` options, the service-plan
/// dialog's own method list, and the selector editor's type list — with nothing
/// tying them to the framework classes they describe. Deriving them here means
/// adding a selector subclass or a routing region shows up everywhere at once,
/// and a rename breaks the build instead of silently narrowing the editor.
///
/// [FsmarMetaServlet] serves the same values to the browser.
public final class FsmarMeta {

	private FsmarMeta() {
	}

	/// SIP methods a trigger may be keyed by — from `State.getTriggers()`'s
	/// [FormKeyEnum]. These are the initial-request methods: the container
	/// invokes an application router for initial requests only, so an in-dialog
	/// method (BYE, CANCEL, ACK, INFO, PRACK, UPDATE) could never match.
	public static final List<String> METHODS = readMethods();

	/// JSR-289 routing regions — from `Transition.Region`.
	public static final List<String> REGIONS = names(Transition.Region.values());

	/// Route modifiers — from the container's own `SipRouteModifier`.
	public static final List<String> ROUTE_MODIFIERS = names(SipRouteModifier.values());

	/// Selector discriminator values — from `Selector`'s [JsonSubTypes].
	public static final List<String> SELECTOR_TYPES = readSelectorTypes();

	/// Set views, for the validator's `contains` checks.
	public static final Set<String> METHOD_SET = setOf(METHODS);
	public static final Set<String> REGION_SET = setOf(REGIONS);
	public static final Set<String> ROUTE_MODIFIER_SET = setOf(ROUTE_MODIFIERS);
	public static final Set<String> SELECTOR_TYPE_SET = setOf(SELECTOR_TYPES);

	/// Populated when a read falls back. Surfaced by [FsmarMetaServlet] so the
	/// condition is visible rather than silent — the editor still works, it is
	/// just running on last-known-good values.
	private static final List<String> WARNINGS = Collections.synchronizedList(new ArrayList<>());

	/// Problems encountered reading the model, empty when all reads succeeded.
	public static List<String> warnings() {
		return Collections.unmodifiableList(new ArrayList<>(WARNINGS));
	}

	/// Degrade rather than fail. An earlier version threw from this class's
	/// static initializer, which meant a renamed getter took the whole editor
	/// down with an `ExceptionInInitializerError` — a bad trade for a dropdown,
	/// and unpleasant to diagnose in a running server. `FsmarMetaTest` fails the
	/// build if these fall out of step with the model, so the loud signal is at
	/// build time where it belongs; at runtime the editor keeps working.
	private static List<String> fallback(String what, String[] values) {
		WARNINGS.add(what + " could not be read from the model; using built-in defaults."
				+ " The editor is usable but its list may be out of date.");
		return Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(values)));
	}

	private static List<String> readMethods() {
		try {
			FormKeyEnum keys = State.class.getMethod("getTriggers").getAnnotation(FormKeyEnum.class);
			if (keys != null && keys.value().length > 0) {
				return Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(keys.value())));
			}
			return fallback("State.getTriggers() has no @FormKeyEnum", DEFAULT_METHODS);
		} catch (NoSuchMethodException e) {
			return fallback("State.getTriggers() not found", DEFAULT_METHODS);
		}
	}

	private static List<String> readSelectorTypes() {
		JsonSubTypes subTypes = Selector.class.getAnnotation(JsonSubTypes.class);
		if (subTypes == null || subTypes.value().length == 0) {
			return fallback("Selector has no @JsonSubTypes", DEFAULT_SELECTOR_TYPES);
		}
		List<String> out = new ArrayList<>();
		for (JsonSubTypes.Type t : subTypes.value()) {
			out.add(t.name());
		}
		return Collections.unmodifiableList(out);
	}

	// Last-known-good values, used only when the model can't be read. Kept in
	// step with the model by FsmarMetaTest.
	private static final String[] DEFAULT_METHODS = {
			"INVITE", "REGISTER", "OPTIONS", "SUBSCRIBE",
			"PUBLISH", "MESSAGE", "NOTIFY", "REFER" };
	private static final String[] DEFAULT_SELECTOR_TYPES = {
			"attribute", "json", "xml", "sdp", "regex", "table" };

	private static List<String> names(Enum<?>[] values) {
		List<String> out = new ArrayList<>();
		for (Enum<?> v : values) {
			out.add(v.name());
		}
		return Collections.unmodifiableList(out);
	}

	private static Set<String> setOf(List<String> values) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}
}
