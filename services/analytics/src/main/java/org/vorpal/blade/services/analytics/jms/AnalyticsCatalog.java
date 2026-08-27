package org.vorpal.blade.services.analytics.jms;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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

	/// Whether the sink should write this event type to the database.
	///
	/// **An undeclared type is not persisted, deliberately.** Writing it would
	/// put rows in the database whose shape nothing describes. The caller
	/// counts what it drops, so "analytics is missing events" stays an
	/// answerable question rather than a shrug.
	///
	/// **The framework's own types persist unless a catalog says otherwise.**
	/// Without that, upgrading a domain that had already published an
	/// `events.json` would silently stop analytics dead: `persist` is a newer
	/// field, so nothing in that file carries it, every flag would read false
	/// and every event would be dropped — with the service running, the
	/// subscription healthy and the log quiet. An operator who genuinely wants
	/// a framework type off can still say `"persist": false`, because a
	/// declaration that exists always wins.
	public static boolean persists(String type) {
		EventType declared = EventCatalogFile.catalog().findType(type);
		if (declared != null) {
			return declared.isPersist();
		}
		return EventCatalogFile.frameworkDefaults().findType(type) != null;
	}

	/// Every type the sink should be receiving — the set [#persists] says yes
	/// to, as a list, so it can become a broker-side selector.
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
		return new ArrayList<>(wanted);
	}
}
