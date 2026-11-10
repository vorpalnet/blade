package org.vorpal.blade.framework.v3.configuration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Whether each server running an application runs what its files say.
///
/// For every server with the application's Configuration MBean
/// (`vorpal.blade:Name=<app>,Type=Configuration,…`), the files that server
/// merges ([ConfigFiles#chain]: domain, its cluster, itself) are merged and
/// compared with the configuration it holds now (`CurrentJson`). Every value
/// the files set must be what the server has; what they leave out (defaults
/// the server fills in) is not compared. A server that differs has not
/// reloaded since a file changed, or could not load it.
public final class RunningConfig {

	/// Paths reported per server, at most.
	private static final int MAX_DIFFERENCES = 5;

	private RunningConfig() {
	}

	/// One entry per server whose files include `fileId`:
	/// `{server, matches, differences[]}`, sorted by server. `section` limits
	/// both sides to that member (an application's `crud` section). Empty off
	/// the AdminServer.
	public static ArrayNode check(String app, String fileId, String section, Map<String, String> membership,
			ObjectMapper mapper) {
		ArrayNode out = mapper.createArrayNode();
		Map<String, ObjectNode> byServer = new TreeMap<>();
		try {
			MBeanServer dr = ClusterMembership.domainRuntime();
			String wanted = ConfigFiles.isDomain(fileId) ? ConfigFiles.DOMAIN : fileId;
			Map<String, JsonNode> read = new java.util.HashMap<>();
			for (ObjectName on : dr.queryNames(new ObjectName("vorpal.blade:Name=" + app + ",Type=Configuration,*"), null)) {
				String server = on.getKeyProperty("Location");
				if (server == null) continue;
				List<ConfigFiles.Entry> chain;
				try {
					chain = ConfigFiles.serverChain(app, server, membership);
				} catch (IllegalArgumentException e) {
					continue; // a server name that cannot be a directory
				}
				if (chain.stream().noneMatch(e -> e.id.equals(wanted))) continue;
				List<JsonNode> layers = new ArrayList<>();
				for (ConfigFiles.Entry e : chain) {
					// Each file read once per check, however many servers share it.
					JsonNode doc = read.containsKey(e.path) ? read.get(e.path) : ConfigFiles.read(e, mapper);
					read.put(e.path, doc);
					layers.add(doc != null && section != null ? doc.get(section) : doc);
				}
				JsonNode expected = ConfigFiles.merge(layers, mapper);
				Object current;
				try {
					current = dr.getAttribute(on, "CurrentJson");
				} catch (Exception e) {
					continue; // this server's MBean could not answer: leave it out, not the others
				}
				if (!(current instanceof String)) continue; // not loaded yet
				JsonNode running = mapper.readTree((String) current);
				if (running != null && section != null) running = running.get(section);
				List<String> differences = new ArrayList<>();
				if (expected != null) differences(expected, running, "", differences);
				ObjectNode row = mapper.createObjectNode();
				row.put("server", server);
				row.put("matches", differences.isEmpty());
				ArrayNode d = row.putArray("differences");
				differences.stream().limit(MAX_DIFFERENCES).forEach(d::add);
				byServer.put(server, row);
			}
		} catch (Exception e) {
			// Off the AdminServer, or JMX failed: nothing to report.
		}
		byServer.values().forEach(out::add);
		return out;
	}

	/// Adds the path of each value in `expected` that `actual` does not hold.
	/// Lists must match item for item; objects only on `expected`'s keys.
	///
	/// A server writes its configuration leaving out what is empty or
	/// default, so a missing value matches an empty object, list or string,
	/// `false`, `0` or null in the file. A file still in a list form
	/// (`[{"id": …}]`) is compared with the keyed object the server writes.
	static void differences(JsonNode expected, JsonNode actual, String path, List<String> out) {
		if ((actual == null || actual.isNull() || actual.isMissingNode()) && empty(expected)) return;
		if (expected.isArray() && actual != null && actual.isObject()) expected = keyed(expected, actual);
		if (expected.isObject()) {
			if (actual == null || !actual.isObject()) {
				out.add(path.isEmpty() ? "(whole document)" : path);
				return;
			}
			for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext();) {
				Map.Entry<String, JsonNode> f = it.next();
				JsonNode a = actual.get(f.getKey());
				String p = path.isEmpty() ? f.getKey() : path + "." + f.getKey();
				if (f.getValue().isNull()) {
					if (a != null && !a.isNull()) out.add(p);
				} else {
					differences(f.getValue(), a, p, out);
				}
			}
		} else if (expected.isArray()) {
			if (actual == null || !actual.isArray() || actual.size() != expected.size()) {
				out.add(path + " (" + expected.size() + " items in the files, "
						+ (actual != null && actual.isArray() ? actual.size() : 0) + " running)");
				return;
			}
			for (int i = 0; i < expected.size(); i++) differences(expected.get(i), actual.get(i), path + "[" + i + "]", out);
		} else if (actual == null || !(expected.asText().equals(actual.asText()) || MASK.equals(actual.asText())
				|| (anyCase(path) && expected.asText().equalsIgnoreCase(actual.asText())))) {
			out.add(path);
		}
	}

	/// What a server writes in place of a credential (`Settings` masks them
	/// in `CurrentJson`); the file holds it encrypted, so it cannot be
	/// compared and counts as the same.
	private static final String MASK = "********";

	/// Choices a server loads in any case and writes in lower case: a file's
	/// `"Request"` is the server's `request`.
	private static boolean anyCase(String path) {
		String last = path.substring(path.lastIndexOf('.') + 1);
		return last.equals("messageType") || last.equals("leg") || last.equals("sdp");
	}

	/// Nothing a server would write: null, an empty or all-empty object or
	/// list, an empty string, `false` or `0`.
	private static boolean empty(JsonNode n) {
		if (n != null && n.isContainerNode() && n.size() > 0) {
			for (JsonNode child : n) if (!empty(child)) return false;
			return true;
		}
		return n == null || n.isNull() || (n.isContainerNode() && n.size() == 0)
				|| (n.isTextual() && n.asText().isEmpty()) || (n.isBoolean() && !n.asBoolean())
				|| (n.isNumber() && n.asDouble() == 0);
	}

	/// A list of objects as the object keyed by id the server reads it as
	/// (KeyedReader): an item's `id`, else, for one without or a repeated
	/// id, the name the server gave the item at that position in `running`;
	/// anything else as it is.
	private static JsonNode keyed(JsonNode list, JsonNode running) {
		java.util.List<String> names = new java.util.ArrayList<>();
		running.fieldNames().forEachRemaining(names::add);
		ObjectNode out = new ObjectMapper().createObjectNode();
		int i = 0;
		for (JsonNode item : list) {
			if (!item.isObject()) return list;
			ObjectNode copy = ((ObjectNode) item).deepCopy();
			JsonNode idNode = copy.remove("id");
			String id = (idNode != null && !idNode.isNull()) ? idNode.asText() : "";
			if (id.isEmpty() || out.has(id)) id = (i < names.size()) ? names.get(i) : "#" + (i + 1);
			out.set(id, copy);
			i++;
		}
		return out;
	}
}
