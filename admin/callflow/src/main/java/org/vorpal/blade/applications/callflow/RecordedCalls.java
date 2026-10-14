package org.vorpal.blade.applications.callflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Calls the framework's flight recorder wrote out because they went wrong
/// (`callTraced` events in the analytics database), in the shape the live
/// trace sources have, so the timeline, the sequence diagram and the printed
/// report draw them unchanged.
///
/// **What a recorded step lacks.** The recorder keeps no SIP text and no
/// source position: a step is the time, direction, leg, method or status,
/// CSeq and the far end's host and port. So a recorded call has no Message or
/// Source tab, and its far ends are named by address. Each leg is named once,
/// by the address of its first step, so a request sent to a host name and the
/// response from that host's IP address stay on one lane.
///
/// One source per application that recorded the call: every BLADE application
/// on a call writes its own record under the same Vorpal-ID, and the page
/// joins them into one timeline as it joins live steps.
final class RecordedCalls {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RecordedCalls() {
	}

	/// The recorded calls, newest first: one call's records when `vorpalId` is
	/// given, else the latest `limit` records, recorded at or after `since`
	/// (epoch millis) when it is given. `{"sources":[...]}`.
	static ObjectNode read(String dataSource, String vorpalId, Long since, int limit)
			throws NamingException, SQLException {
		DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
		String sql = "SELECT application, vorpal_id, payload FROM v_events WHERE event_type = 'callTraced'"
				+ (vorpalId == null ? "" : " AND vorpal_id = ?") + (since == null ? "" : " AND occurred_at >= ?")
				+ " ORDER BY occurred_at DESC";
		ObjectNode out = MAPPER.createObjectNode();
		ArrayNode sources = out.putArray("sources");
		try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
			int i = 1;
			if (vorpalId != null) {
				ps.setString(i++, vorpalId.trim().toUpperCase(Locale.ROOT));
			}
			if (since != null) {
				// The database keeps UTC wall-clock times.
				ps.setTimestamp(i++, java.sql.Timestamp.valueOf(java.time.LocalDateTime
						.ofInstant(java.time.Instant.ofEpochMilli(since), java.time.ZoneOffset.UTC)));
			}
			ps.setMaxRows(limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					try {
						sources.add(source(rs.getString(1), rs.getString(2), MAPPER.readTree(rs.getString(3))));
					} catch (java.io.IOException unreadable) {
						// one bad row must not hide the rest
					}
				}
			}
		}
		return out;
	}

	/// One `callTraced` payload as a trace source: `{app, server, recorded,
	/// verdict, steps}`, each step shaped like a live `CallStep` with the leg's
	/// name as `peer`.
	static ObjectNode source(String app, String vorpalId, JsonNode payload) {
		ObjectNode src = MAPPER.createObjectNode();
		src.put("app", app);
		src.put("server", payload.path("server").asText(null));
		src.put("recorded", true);
		ObjectNode verdict = src.putObject("verdict");
		for (String f : new String[] { "fault", "finding", "method", "peer" }) {
			if (payload.hasNonNull(f)) {
				verdict.put(f, payload.get(f).asText());
			}
		}
		for (String f : new String[] { "status", "leg", "omitted" }) {
			if (payload.hasNonNull(f)) {
				verdict.put(f, payload.get(f).asInt());
			}
		}
		verdict.put("dropped", payload.path("dropped").asBoolean(false));

		Map<Integer, String> legName = new HashMap<>();
		for (JsonNode s : payload.path("steps")) {
			int leg = s.path("leg").asInt();
			if (!legName.containsKey(leg) && s.hasNonNull("peer")) {
				legName.put(leg, s.get("peer").asText());
			}
		}
		ArrayNode steps = src.putArray("steps");
		int order = 0;
		for (JsonNode s : payload.path("steps")) {
			boolean response = s.has("status");
			ObjectNode o = steps.addObject();
			o.put("sessionId", vorpalId);
			o.put("epochMillis", s.path("t").asLong());
			o.put("order", order++);
			o.put("direction", s.path("dir").asText());
			o.put("kind", response ? "response" : "request");
			o.put("label", response ? s.path("status").asText() + " " + s.path("method").asText()
					: s.path("method").asText());
			o.put("leg", s.path("leg").asInt());
			String peer = legName.get(s.path("leg").asInt());
			o.put("peer", peer != null ? peer : "leg " + s.path("leg").asInt());
		}
		return src;
	}
}
