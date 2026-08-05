package org.vorpal.blade.services.analytics.jms;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;

import com.fasterxml.jackson.databind.JsonNode;

/// Reading the CloudEvents payload — the half of the sink that has no database
/// in it.
///
/// **Separate so it can be tested.** Everything else in
/// [AnalyticsEventListener] needs an `EntityManager`, a JMS session and a
/// WebLogic MBean server, which means it can only be exercised on a live domain.
/// These five functions are where the wire format is actually interpreted, they
/// are where the mistakes are, and they are pure — so `WireTest` pins them in a
/// plain JVM. Same discipline as `EventSourceGenerator`, and for the same reason.
final class Wire {

	private Wire() {
	}

	/// A string field, or null when absent or JSON null.
	static String text(JsonNode data, String field) {
		return (data != null && data.hasNonNull(field)) ? data.path(field).asText() : null;
	}

	/// Parse an ISO-8601 instant.
	///
	/// Domain times are always instants on this wire, never epoch millis, so a
	/// consumer in another language does not have to know which epoch or which
	/// precision. An unparseable value yields null rather than throwing: one bad
	/// timestamp should cost a column, not the row.
	static Date instant(JsonNode data, String field) {
		String raw = text(data, field);
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		try {
			return new Date(Instant.parse(raw).toEpochMilli());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/// The call correlator, as the unsigned hex the wire carries.
	///
	/// **`parseUnsignedLong`, not `parseInt`.** The producer formats the
	/// Vorpal-ID with `%08X`, which pads to eight characters but does not *stop*
	/// at eight — a value that does not fit in 32 bits emits more, and parsing
	/// that as a signed int overflows and throws. Reading it unsigned means the
	/// full range round-trips.
	static long vorpalId(JsonNode data) {
		String hex = text(data, "vorpalId");
		return (hex == null) ? 0L : Long.parseUnsignedLong(hex, 16);
	}

	/// Fit a value to its column rather than failing the row.
	///
	/// A truncated attribute is a worse record than a whole one; no record at all
	/// is worse than both, and a caller number one character over the column
	/// width is not a reason to lose the call.
	static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return (value.length() <= max) ? value : value.substring(0, max);
	}

	/// The last dotted segment of a CloudEvents type:
	/// `org.vorpal.blade.transfer.requested` becomes `requested`.
	///
	/// Only used for a type carrying no `eventName` of its own — an event an
	/// operator declared and marked persisted. The framework's own events all
	/// carry their name in the payload, so `event_types` keeps storing
	/// `transferRequested` and existing reports keep working.
	static String shortName(String type) {
		if (type == null || type.isEmpty()) {
			return "unknown";
		}
		int dot = type.lastIndexOf('.');
		return (dot < 0 || dot == type.length() - 1) ? type : type.substring(dot + 1);
	}
}
