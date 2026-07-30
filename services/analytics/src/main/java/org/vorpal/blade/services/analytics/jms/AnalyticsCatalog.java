package org.vorpal.blade.services.analytics.jms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventType;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/// This application's read-only view of the domain-wide event catalog, so the
/// sink can ask which event types it is meant to persist.
///
/// **Why it reads the file rather than borrowing somebody's `SettingsManager`.**
/// Two reasons, and the second is the one that decides it. `services/events`
/// already loads this catalog and publishes a supplier of it into *its own*
/// `ServletContext` — invisible here, because the framework jar ships inside each
/// WAR and that is a different WAR's state. And a `SettingsManager` of our own
/// would not help: it derives its filename from the *deployed context path*, so
/// one built here would read `analytics.json` — this application's own config —
/// and try to parse it as an event catalog. The file is the shared thing; read
/// the file.
///
/// **Reload matters, so it polls.** An operator marking a type persisted in the
/// console should see rows without redeploying this service. A stat per message
/// at call rate is not free, so the check is throttled to [#RELOAD_INTERVAL_MS];
/// the cost of that is a few seconds' delay after a publish, which is the right
/// trade for a flag nobody flips during a call.
public final class AnalyticsCatalog {

	/// Relative to the domain root, which is the server's working directory —
	/// the same assumption every other reader of this file makes.
	private static final Path CATALOG = Paths.get("config/custom/vorpal/events.json");

	/// How often to look for a newer catalog on disk.
	private static final long RELOAD_INTERVAL_MS = 10_000L;

	/// Tolerant of properties the model does not have. A catalog published
	/// before a field moved must not stop analytics recording anything — the
	/// engine tier's own `SettingsManager` has been lenient for the same reason
	/// since long before this class existed.
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/// The framework's own event types — the closed set BLADE emits itself, all
	/// of them persisted. Used both as the catalog for a domain that has none,
	/// and as the fallback for a published catalog that predates a type.
	private static final EventCatalog FRAMEWORK_DEFAULTS = defaults();

	private static volatile EventCatalog current = FRAMEWORK_DEFAULTS;
	private static volatile long checkedAt;
	private static volatile long loadedFrom = -1L;

	private AnalyticsCatalog() {
	}

	/// Whether the sink should write this event type to the database.
	///
	/// **An undeclared type is not persisted, deliberately.** The sink has no
	/// selector — it takes everything on the bus, which is what lets a newly
	/// declared type reach it without a redeploy — so a type nobody declared
	/// arrives here too. Writing it would put rows in the database whose shape
	/// nothing describes. The caller counts what it drops, so "analytics is
	/// missing events" stays an answerable question rather than a shrug.
	///
	/// **The framework's own types persist unless a catalog says otherwise.**
	/// Without that, upgrading a domain that had already published an
	/// `events.json` would silently stop analytics dead: `persist` is a new
	/// field, so nothing in that file carries it, every flag would read false and
	/// every event would be dropped — with the service running, the subscription
	/// healthy and the log quiet. An operator who genuinely wants a framework
	/// type off can still say `"persist": false`, because a declaration that
	/// exists always wins.
	public static boolean persists(String type) {
		EventType declared = catalog().findType(type);
		if (declared != null) {
			return declared.isPersist();
		}
		return FRAMEWORK_DEFAULTS.findType(type) != null;
	}

	/// The catalog, reloaded from disk when it has changed and the throttle has
	/// elapsed.
	///
	/// A failed read leaves the previous catalog in place rather than emptying
	/// it: a half-written file during a publish must not turn into a minute of
	/// silently discarded events.
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

	/// A fresh domain has no `events.json`, and analytics must still record the
	/// framework's own events on one. These are the same declarations the Events
	/// console offers as its starting point, and all of them are persisted.
	private static EventCatalog defaults() {
		EventCatalog catalog = new EventCatalog();
		catalog.setTypes(BladeEventCatalog.analyticsTypes());
		return catalog;
	}
}
