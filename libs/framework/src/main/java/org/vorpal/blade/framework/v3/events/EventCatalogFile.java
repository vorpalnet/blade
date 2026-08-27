package org.vorpal.blade.framework.v3.events;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Any application's read-only view of the domain-wide event catalog.
///
/// **Why it reads the file rather than borrowing somebody's `SettingsManager`.**
/// Two reasons, and the second decides it. `services/events` already loads this
/// catalog and publishes a supplier of it into *its own* `ServletContext` —
/// invisible to every other application, because the framework jar ships inside
/// each WAR and that is a different WAR's state. And a `SettingsManager` of an
/// application's own would not help: it derives its filename from the deployed
/// context path, so one built in `transfer` would read `transfer.json` and try
/// to parse an application's config as an event catalog. The file is the shared
/// thing; read the file.
///
/// **Reload matters, so it polls.** An operator changing what a consumer
/// listens to should see the change without redeploying it. A stat per message
/// at call rate is not free, so the check is throttled to
/// [#RELOAD_INTERVAL_MS]; the cost is a few seconds' delay after a publish,
/// which is the right trade for something nobody changes during a call.
///
/// This began as the analytics service's private `AnalyticsCatalog`. It moved
/// here when actors needed the same view — a second copy of "where the catalog
/// lives and how often to re-read it" is two answers to one question, and the
/// one that rots is whichever is read less often.
public final class EventCatalogFile {

	/// Relative to the domain root, which is the server's working directory —
	/// the same assumption every other reader of this file makes.
	private static final Path CATALOG = Paths.get("config/custom/vorpal/events.json");

	/// How often to look for a newer catalog on disk.
	private static final long RELOAD_INTERVAL_MS = 10_000L;

	/// Tolerant of properties the model does not have. A catalog published
	/// before a field moved must not stop a consumer working — the engine
	/// tier's own `SettingsManager` has been lenient for the same reason since
	/// long before this class existed.
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/// The framework's own event types — the closed set BLADE emits itself.
	/// Used as the catalog for a domain that has none.
	private static final EventCatalog FRAMEWORK_DEFAULTS = defaults();

	private static volatile EventCatalog current = FRAMEWORK_DEFAULTS;
	private static volatile long checkedAt;
	private static volatile long loadedFrom = -1L;

	private EventCatalogFile() {
	}

	/// The catalog, reloaded from disk when it has changed and the throttle has
	/// elapsed.
	///
	/// A failed read leaves the previous catalog in place rather than emptying
	/// it: a half-written file during a publish must not turn into a minute of
	/// consumers deciding they want nothing.
	public static EventCatalog catalog() {
		long now = System.currentTimeMillis();
		if (now - checkedAt < RELOAD_INTERVAL_MS) {
			return current;
		}
		checkedAt = now;
		try {
			if (!Files.exists(CATALOG)) {
				return current;
			}
			long modified = Files.getLastModifiedTime(CATALOG).toMillis();
			if (modified == loadedFrom) {
				return current;
			}
			EventCatalog loaded = MAPPER.readValue(CATALOG.toFile(), EventCatalog.class);
			if (loaded != null) {
				current = loaded;
				loadedFrom = modified;
			}
		} catch (Exception e) {
			// Keep serving the catalog we have. Retried on the next interval.
		}
		return current;
	}

	/// The framework's own declarations, for a domain with no `events.json`.
	public static EventCatalog frameworkDefaults() {
		return FRAMEWORK_DEFAULTS;
	}

	/// The types a named subscription declares in the catalog, or null when the
	/// catalog does not mention it.
	///
	/// Null and empty mean different things here: **null** is "the operator has
	/// not said", so the consumer keeps the types it was built with; **empty**
	/// is "the operator said none", which is a deliberate way to switch a
	/// consumer off without undeploying it.
	public static List<String> declaredTypes(String subscriptionName) {
		EventSubscription declared = declaredSubscription(subscriptionName);
		return (declared == null) ? null : declared.typesOrEmpty();
	}

	/// The catalog's entry for a named subscription, or null when it has none.
	public static EventSubscription declaredSubscription(String subscriptionName) {
		for (EventSubscription subscription : catalog().subscriptionsOrEmpty()) {
			if (subscriptionName != null && subscriptionName.equals(subscription.getName())) {
				return subscription;
			}
		}
		return null;
	}

	private static EventCatalog defaults() {
		EventCatalog catalog = new EventCatalog();
		catalog.setTypes(BladeEventCatalog.analyticsTypes());
		return catalog;
	}
}
