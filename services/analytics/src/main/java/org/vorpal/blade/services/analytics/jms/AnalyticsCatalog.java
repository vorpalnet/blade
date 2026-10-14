package org.vorpal.blade.services.analytics.jms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventCatalogFile;
import org.vorpal.blade.framework.v3.events.EventType;

/// What the analytics sink asks the catalog: which event types to write, and
/// which to select for.
///
/// **The reading and reloading of the catalog file moved to
/// [EventCatalogFile]** when actors needed the same view. What is left here is
/// the part that is genuinely analytics': the two rules below, which have to
/// agree with each other or the sink filters for one set at the broker and
/// writes a different set to the database.
public final class AnalyticsCatalog {

	private AnalyticsCatalog() {
	}

	/// The framework's own flag for each operations type it declares (an
	/// endpoint down, a trunk failed, a queue's minute, a configuration push).
	/// They are about no call, so without this they would persist only when a
	/// domain's `events.json` declared them, and a domain that never published
	/// one would keep no history of its operations.
	private static final Map<String, Boolean> OPERATIONS = operations();

	private static Map<String, Boolean> operations() {
		Map<String, Boolean> flags = new HashMap<>();
		for (EventType type : BladeEventCatalog.operationsTypes()) {
			flags.put(type.getType(), type.isPersist());
		}
		return flags;
	}

	/// Whether the sink should write this event to the database.
	///
	/// **A declaration that exists always wins**, so an operator can switch any
	/// type off with `"persist": false`, or on for an event that is not about a
	/// call.
	///
	/// **The framework's own types persist unless a catalog says otherwise.**
	/// Without that, upgrading a domain that had already published an
	/// `events.json` would silently stop analytics dead: `persist` is a newer
	/// field, so nothing in that file carries it, every flag would read false
	/// and every event would be dropped.
	///
	/// **The framework's operations types persist as the framework marks
	/// them** ([#OPERATIONS]), unless a catalog says otherwise.
	///
	/// **Any other call event persists too, declared or not.** An application
	/// adding an event used to have to add it to `events.json` as well, or the
	/// sink never wrote it; the rows were lost with nothing but a counter to
	/// show for it. A call event's payload is the flat, typed object [Events]
	/// builds, and its row carries the application, type and dataversion, so it
	/// is not a shape nothing describes. An event that is not about a call (an
	/// operations event) still needs a declaration to be stored.
	public static boolean persists(CloudEvent event) {
		if (event == null || event.getType() == null) {
			return false;
		}
		String type = event.getType();
		EventType declared = EventCatalogFile.catalog().findType(type);
		if (declared != null) {
			return declared.isPersist();
		}
		if (EventCatalogFile.frameworkDefaults().findType(type) != null) {
			return true;
		}
		Boolean operations = OPERATIONS.get(type);
		return operations != null ? operations : event.isCallScoped();
	}

	/// The types a declaration switches off: the call events the sink must not
	/// take even though they are call events.
	public static List<String> switchedOff() {
		List<String> off = new ArrayList<>();
		for (EventType declared : EventCatalogFile.catalog().typesOrEmpty()) {
			if (!declared.isPersist() && declared.getType() != null) {
				off.add(declared.getType());
			}
		}
		return off;
	}

	/// The types the sink should be receiving by name: the declared and framework
	/// half of [#persists], as a list, so it can become a broker-side selector.
	/// Undeclared call events arrive through [#switchedOff]'s half.
	///
	/// This is what lets the sink filter at the broker instead of taking
	/// everything and discarding most of it. The two must agree, which is why
	/// this applies exactly the rules [#persists] applies: a declared type
	/// counts when its flag says so, and a framework type counts unless a
	/// declaration turns it off.
	public static List<String> persistedTypes() {
		LinkedHashSet<String> wanted = new LinkedHashSet<>();
		EventCatalog catalog = EventCatalogFile.catalog();

		for (EventType declared : catalog.typesOrEmpty()) {
			if (declared.isPersist() && declared.getType() != null) {
				wanted.add(declared.getType());
			}
		}
		for (EventType framework : EventCatalogFile.frameworkDefaults().typesOrEmpty()) {
			String type = framework.getType();
			if (type != null && catalog.findType(type) == null) {
				wanted.add(type);
			}
		}
		for (Map.Entry<String, Boolean> operations : OPERATIONS.entrySet()) {
			if (operations.getValue() && catalog.findType(operations.getKey()) == null) {
				wanted.add(operations.getKey());
			}
		}
		return new ArrayList<>(wanted);
	}
}
