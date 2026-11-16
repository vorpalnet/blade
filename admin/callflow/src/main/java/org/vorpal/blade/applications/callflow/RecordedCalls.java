package org.vorpal.blade.applications.callflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
/// Source tab, and its far ends are named by address. Each step keeps its own
/// far end, since a proxy's caller and callee share one SIP session (one leg,
/// two far ends). A response stands with the request it answers, and an
/// address a request's response came from is that request's far end from then
/// on, so a request sent to a host name and the messages from that host's IP
/// address stay on one lane.
///
/// **What a recorded call keeps besides.** The call's parties, once, from its
/// initial INVITE (`parties`: From, To, P-Asserted-Identity), and its row's
/// domain, tenant and department on every step, so a viewer's grants apply to
/// it as to a live step ([TraceAccess]), here and once the call is saved.
///
/// One source per application that recorded the call: every BLADE application
/// on a call writes its own record under the same Vorpal-ID, and the page
/// joins them into one timeline as it joins live steps.
final class RecordedCalls {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RecordedCalls() {
	}

	/// What to narrow the recorded calls to. Every field is optional; null
	/// means no condition. Text compares without regard to case.
	///
	/// - `vorpalId`: one call.
	/// - `since` / `before`: recorded at or after, or strictly before, an
	///   instant (epoch millis). `before` pages back: the page passes the oldest
	///   record it holds.
	/// - `app`, `server`: the application or server that recorded it, exactly.
	/// - `finding`: one of the verdict's findings (`silent`, `timeout`, ...), or
	///   `dropped` for a call that expired dropped.
	/// - `fault`: `downstream` or `application`.
	/// - `peer`: part of the far end the verdict names (`10.20.1.7`, `:5060`).
	/// - `number`: part of the call's From, To or P-Asserted-Identity. The
	///   API takes it only from a viewer who sees content.
	/// - `access`: the viewer's grants; null for no limit.
	static final class Filter {
		String vorpalId;
		Long since;
		Long before;
		String app;
		String server;
		String finding;
		String fault;
		String peer;
		String number;
		TraceAccess access;

		/// The WHERE clause for `v_call_traced`, its values added to `params`
		/// in order: Strings, and Longs for instants.
		String where(List<Object> params) {
			StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
			if (vorpalId != null) {
				sql.append(" AND vorpal_id = ?");
				params.add(vorpalId.trim().toUpperCase(Locale.ROOT));
			}
			if (since != null) {
				sql.append(" AND traced_at >= ?");
				params.add(since);
			}
			if (before != null) {
				sql.append(" AND traced_at < ?");
				params.add(before);
			}
			if (app != null) {
				sql.append(" AND LOWER(application) = ?");
				params.add(app.trim().toLowerCase(Locale.ROOT));
			}
			if (server != null) {
				sql.append(" AND LOWER(server) = ?");
				params.add(server.trim().toLowerCase(Locale.ROOT));
			}
			if ("dropped".equalsIgnoreCase(finding)) {
				sql.append(" AND dropped = 'true'");
			} else if (finding != null) {
				sql.append(" AND finding = ?");
				params.add(finding.trim().toLowerCase(Locale.ROOT));
			}
			if (fault != null) {
				sql.append(" AND fault = ?");
				params.add(fault.trim().toLowerCase(Locale.ROOT));
			}
			if (peer != null) {
				sql.append(" AND LOWER(peer) LIKE ? ESCAPE '!'");
				params.add(contains(peer));
			}
			if (number != null) {
				sql.append(" AND (LOWER(call_from) LIKE ? ESCAPE '!' OR LOWER(call_to) LIKE ? ESCAPE '!'"
						+ " OR LOWER(asserted) LIKE ? ESCAPE '!')");
				String like = contains(number);
				params.add(like);
				params.add(like);
				params.add(like);
			}
			if (access != null) {
				sql.append(access.recordedWhere(params));
			}
			return sql.toString();
		}

		/// A LIKE pattern matching `text` anywhere, its wildcards taken literally.
		private static String contains(String text) {
			return "%" + text.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
					+ "%";
		}
	}

	/// The recorded calls that pass `filter`, newest first, at most `limit`
	/// records (one per application per call). `{"sources":[...]}`, each source
	/// with `tracedAt` (epoch millis), the instant the page pages back from.
	static ObjectNode read(String dataSource, Filter filter, int limit) throws NamingException, SQLException {
		DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
		List<Object> params = new ArrayList<>();
		String sql = "SELECT application, vorpal_id, server, payload, traced_at, cluster_name, tenant, department"
				+ " FROM v_call_traced"
				+ filter.where(params) + " ORDER BY traced_at DESC";
		ObjectNode out = MAPPER.createObjectNode();
		ArrayNode sources = out.putArray("sources");
		try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
			int i = 1;
			for (Object p : params) {
				if (p instanceof Long) {
					ps.setTimestamp(i++, utc((Long) p));
				} else {
					ps.setString(i++, (String) p);
				}
			}
			ps.setMaxRows(limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					try {
						ObjectNode src = source(rs.getString(1), rs.getString(2), MAPPER.readTree(rs.getString(4)));
						if (src.path("server").isNull() && rs.getString(3) != null) {
							// The application's row names its server; the record need not.
							src.put("server", rs.getString(3));
						}
						// Each step carries the row's scope, so the grants apply to it
						// as to a live step, here and once the call is saved.
						for (JsonNode step : src.path("steps")) {
							((ObjectNode) step).put("domain", rs.getString(6));
							((ObjectNode) step).put("tenant", rs.getString(7));
							((ObjectNode) step).put("department", rs.getString(8));
						}
						java.sql.Timestamp at = rs.getTimestamp(5);
						if (at != null) {
							src.put("tracedAt", at.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
						}
						sources.add(src);
					} catch (java.io.IOException unreadable) {
						// one bad row must not hide the rest
					}
				}
			}
		}
		return out;
	}

	/// An instant as the database keeps it: UTC wall-clock time.
	private static java.sql.Timestamp utc(long epochMillis) {
		return java.sql.Timestamp.valueOf(java.time.LocalDateTime
				.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneOffset.UTC));
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
		// Who the call was between, from its initial INVITE: content, taken
		// out for a viewer without it ([TraceAccess]).
		ObjectNode parties = MAPPER.createObjectNode();
		for (String f : new String[] { "from", "to", "asserted" }) {
			if (payload.hasNonNull(f)) {
				parties.put(f, payload.get(f).asText());
			}
		}
		if (parties.size() > 0) {
			src.set("parties", parties);
		}

		// Far ends: the request each response answers, and the addresses that
		// answered for a name (a host name's IP address).
		Map<String, String> requestPeer = new HashMap<>(), alias = new HashMap<>();
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
			int leg = s.path("leg").asInt();
			o.put("leg", leg);
			// A request and its responses: same leg, method and CSeq, opposite directions.
			String request = leg + " " + s.path("method").asText() + " " + s.path("cseq").asLong() + " "
					+ ("in".equals(s.path("dir").asText()) == response ? "out" : "in");
			String own = s.hasNonNull("peer") ? s.get("peer").asText() : null, peer;
			if (response) {
				String asked = requestPeer.get(request);
				if (asked != null && own != null && !own.equals(asked)) {
					alias.put(own, asked);
				}
				peer = (asked != null) ? asked : own;
			} else {
				peer = (own == null) ? null : alias.getOrDefault(own, own);
				if (peer != null) {
					requestPeer.put(request, peer);
				}
			}
			if (peer == null) {
				peer = legName.get(leg);
			}
			o.put("peer", peer != null ? peer : "leg " + leg);
		}
		return src;
	}
}
