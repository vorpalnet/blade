package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The files that configure one application, as an editor finds them: the
/// domain file, and any cluster or server file beside it.
///
/// ```
/// config/custom/vorpal/<app>.json                      domain
/// config/custom/vorpal/_clusters/<cluster>/<app>.json  cluster
/// config/custom/vorpal/_servers/<server>/<app>.json    server
/// ```
///
/// The layout is the one the settings loader reads (`v2.config.SettingsManager`):
/// it reads the domain file, merges the cluster file over it, then the server
/// file. Objects merge key by key; a list is appended to the list below it,
/// so an overlay can add list items but cannot change or remove one; any other
/// value replaces. A cluster or server file therefore holds only what it
/// changes, and an editor that picks one edits that file as it is.
///
/// An editor names a file by its id: `domain`, `cluster/<name>` or
/// `server/<name>`. [#resolve] turns an id back into a path and refuses
/// anything else, so a request can never reach outside these directories.
public final class ConfigFiles {

	/// Where every BLADE configuration lives, relative to the domain root (the
	/// AdminServer's working directory).
	public static final Path BASE = Paths.get("config/custom/vorpal");

	/// The domain file's id.
	public static final String DOMAIN = "domain";

	private ConfigFiles() {
	}

	/// One configuration file of an application.
	public static final class Entry {
		/// `domain`, `cluster/<name>` or `server/<name>`.
		public String id;
		/// `domain`, `cluster` or `server`.
		public String scope;
		/// The cluster or server name; null for the domain file.
		public String name;
		/// The path from the domain root, for display.
		public String path;
		/// False for a file not written yet: a domain file, or a layer of
		/// [#chain].
		public boolean exists;
		/// For a server file, the cluster the server is in
		/// ([ClusterMembership]); null when it is in none or that is unknown.
		public String cluster;
	}

	/// The domain file, whether or not it exists yet, then every cluster and
	/// server file that does exist, each group sorted by name. `membership`
	/// ([ClusterMembership]) names each server file's cluster.
	public static List<Entry> of(String app, Map<String, String> membership) throws IOException {
		List<Entry> out = new ArrayList<>();
		Path domain = BASE.resolve(app + ".json");
		out.add(entry(DOMAIN, DOMAIN, null, domain));
		for (String scope : new String[] { "cluster", "server" }) {
			for (String name : names(dir(scope))) {
				Path file = dir(scope).resolve(name).resolve(app + ".json");
				if (Files.isRegularFile(file)) {
					Entry e = entry(scope + "/" + name, scope, name, file);
					if ("server".equals(scope)) e.cluster = membership.get(name);
					out.add(e);
				}
			}
		}
		return out;
	}

	/// The files the settings loader merges to run file `id`, in its order:
	/// the domain file; for a cluster file, that file; for a server file, its
	/// cluster's file (when `membership` names one) and then the server's.
	/// A layer not written yet is listed with `exists` false.
	public static List<Entry> chain(String app, String id, Map<String, String> membership) {
		List<Entry> out = new ArrayList<>();
		out.add(entry(DOMAIN, DOMAIN, null, BASE.resolve(app + ".json")));
		if (isDomain(id)) return out;
		resolve(app, id);
		String scope = id.substring(0, id.indexOf('/'));
		String name = id.substring(id.indexOf('/') + 1);
		String cluster = "cluster".equals(scope) ? name : membership.get(name);
		if (cluster != null && plainName(cluster)) {
			out.add(entry("cluster/" + cluster, "cluster", cluster, dir("cluster").resolve(cluster).resolve(app + ".json")));
		}
		if ("server".equals(scope)) {
			Entry e = entry(id, scope, name, dir(scope).resolve(name).resolve(app + ".json"));
			e.cluster = cluster;
			out.add(e);
		}
		return out;
	}

	/// The files `server` merges, in the loader's order, whether or not its
	/// `_servers/<server>` directory exists on this host: a server on another
	/// host creates it on its own disk.
	public static List<Entry> serverChain(String app, String server, Map<String, String> membership) {
		if (!plainName(server)) throw new IllegalArgumentException("no such server: " + server);
		List<Entry> out = new ArrayList<>();
		out.add(entry(DOMAIN, DOMAIN, null, BASE.resolve(app + ".json")));
		String cluster = membership.get(server);
		if (cluster != null && plainName(cluster)) {
			out.add(entry("cluster/" + cluster, "cluster", cluster, dir("cluster").resolve(cluster).resolve(app + ".json")));
		}
		Entry e = entry("server/" + server, "server", server, dir("server").resolve(server).resolve(app + ".json"));
		e.cluster = cluster;
		out.add(e);
		return out;
	}

	/// The existing files that merge over file `id`, and so change when it
	/// does: every cluster and server file over the domain file, a cluster's
	/// servers over its file, nothing over a server file.
	public static List<Entry> above(String app, String id, Map<String, String> membership) throws IOException {
		List<Entry> out = new ArrayList<>();
		String scope = isDomain(id) ? DOMAIN : id.substring(0, id.indexOf('/'));
		String name = isDomain(id) ? null : id.substring(id.indexOf('/') + 1);
		for (Entry e : of(app, membership)) {
			if (isDomain(e.id)) continue;
			if (DOMAIN.equals(scope) || ("cluster".equals(scope) && "server".equals(e.scope) && name.equals(e.cluster))) {
				out.add(e);
			}
		}
		return out;
	}

	/// What file `id` merges over: its chain without it, merged; null for the
	/// domain file or when no lower file is written.
	public static JsonNode belowOf(String app, String id, Map<String, String> membership, ObjectMapper mapper)
			throws IOException {
		if (isDomain(id)) return null;
		List<JsonNode> layers = new ArrayList<>();
		for (Entry e : chain(app, id, membership)) {
			if (!e.id.equals(id)) layers.add(read(e, mapper));
		}
		return merge(layers, mapper);
	}

	/// The file's contents, or null when it is not written yet.
	public static JsonNode read(Entry e, ObjectMapper mapper) throws IOException {
		Path file = Paths.get(e.path);
		return Files.isRegularFile(file) ? mapper.readTree(file.toFile()) : null;
	}

	/// `layers` merged in order, as the settings loader merges them (it calls
	/// this): Jackson `readerForUpdating` on the tree, so objects merge key by
	/// key, a list is appended to the list below it, anything else replaces.
	/// One exception keeps a layer from dropping another's entries: where one
	/// side holds an object and the other a list of objects that each name an
	/// `id` (rules, connectors, rule sets in the list form an earlier editor
	/// wrote), the list is read as the object keyed by those ids first
	/// ([#keyedForm]), so the two merge by id. Layers of the same shapes merge
	/// as Jackson alone merges them. A null layer is a file not written, and is
	/// skipped; null when every layer is.
	public static JsonNode merge(List<JsonNode> layers, ObjectMapper mapper) throws IOException {
		JsonNode out = null;
		for (JsonNode layer : layers) {
			if (layer == null || layer.isNull()) continue;
			JsonNode over = layer.deepCopy();
			if (out == null) {
				out = over;
				continue;
			}
			alignForms(out, over);
			out = mapper.readerForUpdating(out).readValue(over);
		}
		return out;
	}

	/// Brings `below` and `over` to one form wherever one holds an object and
	/// the other a keyed-able list, at any depth where both are objects.
	/// Changes both trees, which are the caller's own copies.
	static void alignForms(JsonNode below, JsonNode over) {
		if (!(below instanceof com.fasterxml.jackson.databind.node.ObjectNode)
				|| !(over instanceof com.fasterxml.jackson.databind.node.ObjectNode)) return;
		com.fasterxml.jackson.databind.node.ObjectNode b = (com.fasterxml.jackson.databind.node.ObjectNode) below;
		com.fasterxml.jackson.databind.node.ObjectNode o = (com.fasterxml.jackson.databind.node.ObjectNode) over;
		List<String> names = new ArrayList<>();
		o.fieldNames().forEachRemaining(names::add);
		for (String name : names) {
			JsonNode bv = b.get(name);
			JsonNode ov = o.get(name);
			if (bv == null || ov == null) continue;
			if (bv.isObject() && keyable(ov)) {
				o.set(name, keyedForm(ov));
			} else if (ov.isObject() && keyable(bv)) {
				b.set(name, keyedForm(bv));
			}
			alignForms(b.get(name), o.get(name));
		}
	}

	/// A non-empty list whose items are all objects naming a non-empty `id`.
	static boolean keyable(JsonNode n) {
		if (n == null || !n.isArray() || n.size() == 0) return false;
		for (JsonNode item : n) {
			if (!item.isObject() || !item.path("id").isTextual() || item.path("id").asText().isEmpty()) return false;
		}
		return true;
	}

	/// The list as the object keyed by each item's `id`, without it; a
	/// repeated id is renamed `<id>-2` (or the next free), as the CRUD
	/// readers read it.
	static com.fasterxml.jackson.databind.node.ObjectNode keyedForm(JsonNode list) {
		com.fasterxml.jackson.databind.node.ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
		for (JsonNode item : list) {
			com.fasterxml.jackson.databind.node.ObjectNode copy = ((com.fasterxml.jackson.databind.node.ObjectNode) item).deepCopy();
			String id = copy.remove("id").asText();
			String key = id;
			for (int k = 2; out.has(key); k++) key = id + "-" + k;
			out.set(key, copy);
		}
		return out;
	}

	/// What a server or cluster runs with `draft` saved as file `id`: every
	/// layer of [#chain], `draft` in place of that file, merged.
	public static JsonNode applied(String app, String id, JsonNode draft, Map<String, String> membership,
			ObjectMapper mapper) throws IOException {
		return applied(app, id, id, draft, membership, mapper);
	}

	/// What file `id` runs with `draft` saved as file `draftId`, which may be
	/// a layer below it: a domain draft checked through each cluster and
	/// server file that merges over it.
	public static JsonNode applied(String app, String id, String draftId, JsonNode draft, Map<String, String> membership,
			ObjectMapper mapper) throws IOException {
		String replaced = isDomain(draftId) ? DOMAIN : draftId;
		List<JsonNode> layers = new ArrayList<>();
		for (Entry e : chain(app, id, membership)) {
			layers.add(e.id.equals(replaced) ? draft : read(e, mapper));
		}
		return merge(layers, mapper);
	}

	/// Rule sets and rules that a file over `draft` (saved as `draftId`)
	/// changes, and that the files below it hold now but would not with the
	/// draft: a rename or a delete there leaves the overlay's change standing
	/// alone, a rule with none of the original's filters. Read at
	/// `<section>.ruleSets`, or the top-level `ruleSets` when `section` is
	/// null. Each names the file over the draft.
	public static List<String> orphans(String app, String draftId, JsonNode draft, String section,
			Map<String, String> membership, ObjectMapper mapper) throws IOException {
		List<String> out = new ArrayList<>();
		String replaced = isDomain(draftId) ? DOMAIN : draftId;
		for (Entry overlay : above(app, draftId, membership)) {
			List<JsonNode> before = new ArrayList<>();
			List<JsonNode> after = new ArrayList<>();
			for (Entry e : chain(app, overlay.id, membership)) {
				if (e.id.equals(overlay.id)) continue;
				JsonNode file = read(e, mapper);
				before.add(file);
				after.add(e.id.equals(replaced) ? draft : file);
			}
			JsonNode was = ruleSetsOf(merge(before, mapper), section);
			JsonNode will = ruleSetsOf(merge(after, mapper), section);
			JsonNode over = ruleSetsOf(read(overlay, mapper), section);
			if (over == null || !over.isObject()) continue;
			over.fields().forEachRemaining(rs -> {
				if (!rs.getValue().isObject()) return;
				String set = rs.getKey();
				if (isObject(was, set) && !isObject(will, set)) {
					out.add("with " + overlay.path + ": it changes rule set \"" + set + "\", which this draft removes or renames; "
							+ "there it would stand alone. Change that file first");
					return;
				}
				JsonNode rules = rs.getValue().get("rules");
				if (rules == null || !rules.isObject()) return;
				JsonNode wasRules = isObject(was, set) ? was.get(set).get("rules") : null;
				JsonNode willRules = isObject(will, set) ? will.get(set).get("rules") : null;
				rules.fields().forEachRemaining(r -> {
					if (r.getValue().isObject() && isObject(wasRules, r.getKey()) && !isObject(willRules, r.getKey())) {
						out.add("with " + overlay.path + ": it changes rule \"" + set + "/" + r.getKey() + "\", which this draft "
								+ "removes or renames; there it would run alone, with none of its filters. Change that file first");
					}
				});
			});
		}
		return out;
	}

	private static JsonNode ruleSetsOf(JsonNode doc, String section) {
		if (doc == null) return null;
		return (section == null) ? doc.get("ruleSets") : doc.path(section).get("ruleSets");
	}

	private static boolean isObject(JsonNode parent, String key) {
		return parent != null && parent.isObject() && parent.has(key) && parent.get(key).isObject();
	}

	/// The path for `id`; a null or empty id is the domain file. Throws
	/// [IllegalArgumentException] for an id that is not one of the three
	/// shapes, or whose cluster or server directory does not exist.
	public static Path resolve(String app, String id) {
		if (id == null || id.isEmpty() || DOMAIN.equals(id)) return BASE.resolve(app + ".json");
		int slash = id.indexOf('/');
		String scope = (slash > 0) ? id.substring(0, slash) : "";
		String name = (slash > 0) ? id.substring(slash + 1) : "";
		if (!("cluster".equals(scope) || "server".equals(scope)) || !plainName(name)) {
			throw new IllegalArgumentException("no such configuration file: " + id);
		}
		Path dir = dir(scope).resolve(name);
		if (!Files.isDirectory(dir)) throw new IllegalArgumentException("no " + scope + " named " + name);
		return dir.resolve(app + ".json");
	}

	/// The read-only merged view of file `id`, for an editor:
	/// `{file, layers: [entry + config], merged, running}`, `running` from
	/// [RunningConfig#check]; `layers` in the loader's
	/// order ([#chain]) and `merged` what the settings loader would build from
	/// them ([#merge]). With `section` set, only that member of each file (an
	/// application's `crud` section, say); a layer without one is null.
	public static com.fasterxml.jackson.databind.node.ObjectNode mergedView(String app, String id, String section,
			Map<String, String> membership, ObjectMapper mapper) throws IOException {
		com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
		out.put("file", isDomain(id) ? DOMAIN : id);
		com.fasterxml.jackson.databind.node.ArrayNode arr = out.putArray("layers");
		List<JsonNode> layers = new ArrayList<>();
		for (Entry e : chain(app, id, membership)) {
			JsonNode doc = read(e, mapper);
			if (doc != null && section != null) doc = doc.get(section);
			layers.add(doc);
			com.fasterxml.jackson.databind.node.ObjectNode layer = mapper.valueToTree(e);
			layer.set("config", doc);
			arr.add(layer);
		}
		out.set("merged", merge(layers, mapper));
		out.set("running", RunningConfig.check(app, id, section, membership, mapper));
		// CRUD rule hits ride along: the editor shows them beside each rule.
		out.set("counters", AppCounters.read(app, "crud.rule.", mapper));
		return out;
	}

	/// True for the domain file's id.
	public static boolean isDomain(String id) {
		return id == null || id.isEmpty() || DOMAIN.equals(id);
	}

	private static Path dir(String scope) {
		return BASE.resolve("cluster".equals(scope) ? "_clusters" : "_servers");
	}

	/// A single path segment: no separator, not `.` or `..`.
	private static boolean plainName(String name) {
		return !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0
				&& name.indexOf('\\') < 0;
	}

	private static TreeSet<String> names(Path dir) throws IOException {
		TreeSet<String> names = new TreeSet<>();
		if (!Files.isDirectory(dir)) return names;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
			for (Path p : entries) {
				if (Files.isDirectory(p)) names.add(p.getFileName().toString());
			}
		}
		return names;
	}

	private static Entry entry(String id, String scope, String name, Path file) {
		Entry e = new Entry();
		e.id = id;
		e.scope = scope;
		e.name = name;
		e.path = file.toString();
		e.exists = Files.isRegularFile(file);
		return e;
	}
}
