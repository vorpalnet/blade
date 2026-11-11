package org.vorpal.blade.applications.console.config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.databind.JsonNode;

/// Jackson object-identity problems a Save would make live: an `id` two
/// identity objects share, and a reference (a bare id string where an
/// identity object goes) to an id nothing defines. Either makes the
/// application fail to load the file ("Already had POJO for id", "Unresolved
/// forward reference"), and a Save is live at once.
///
/// Run by [SaveCheck] on the documents the loader builds, not on the file
/// alone: a cluster or server file merged with the files below it, and every
/// file above the draft merged over it. Lists append when they merge, so an
/// overlay's map repeating a domain map's id fails exactly like a duplicate
/// in one file.
///
/// Identity is decided by the class the schema names (`x-java-class`): one
/// that carries `@JsonIdentityInfo`, declared or inherited. Ids are one
/// namespace per document: Jackson scopes them to `Object`, not the class.
final class IdentityCheck {

	private static final Map<String, Boolean> IDENTITY = new ConcurrentHashMap<>();

	private IdentityCheck() {
	}

	/// The problems in one whole document, as an application would load it.
	static List<String> problems(JsonNode doc, JsonNode schema) {
		Map<String, List<String>> ids = new LinkedHashMap<>();
		List<String[]> refs = new ArrayList<>();
		walk(doc, schema, schema, "", ids, refs);
		List<String> out = new ArrayList<>();
		ids.forEach((id, classes) -> {
			if (classes.size() > 1) {
				out.add("the id \"" + id + "\" is used by " + classes.size() + " objects (" + String.join(", ", classes)
						+ "); ids must be unique in the file");
			}
		});
		for (String[] r : refs) {
			if (!ids.containsKey(r[1])) out.add(r[0] + " refers to \"" + r[1] + "\", which nothing defines");
		}
		return out;
	}

	/// Whether any class the schema names takes identity: most do not, and
	/// then nothing needs merging.
	static boolean usesIdentity(JsonNode schema) {
		if (schema == null) return false;
		for (JsonNode cls : schema.findValues("x-java-class")) {
			if (takesIdentity(cls.asText())) return true;
		}
		return false;
	}

	static boolean takesIdentity(String cls) {
		if (cls == null || cls.isEmpty()) return false;
		return IDENTITY.computeIfAbsent(cls, name -> {
			try {
				for (Class<?> c = Class.forName(name, false, IdentityCheck.class.getClassLoader()); c != null; c = c.getSuperclass()) {
					if (c.isAnnotationPresent(JsonIdentityInfo.class)) return true;
				}
			} catch (Throwable e) {
				// not on this classpath: an application's own class, which BLADE
				// gives no identity
			}
			return false;
		});
	}

	/// A schema node with its `$ref` chain followed: the first class named on
	/// the way, every property declared on the way (a victools `-2` wrapper
	/// holds the discriminator, its target the rest), and the rest of the node.
	private static final class Node {
		String cls;
		final Map<String, JsonNode> props = new LinkedHashMap<>();
		JsonNode items;
		JsonNode additional;
		JsonNode variants;
	}

	private static Node resolve(JsonNode sch, JsonNode root) {
		Node n = new Node();
		for (int hops = 0; sch != null && hops < 32; hops++) {
			if (n.cls == null && sch.hasNonNull("x-java-class")) n.cls = sch.get("x-java-class").asText();
			JsonNode props = sch.get("properties");
			if (props != null) props.fields().forEachRemaining(e -> n.props.putIfAbsent(e.getKey(), e.getValue()));
			if (n.items == null && sch.has("items")) n.items = sch.get("items");
			if (n.additional == null && sch.get("additionalProperties") != null && sch.get("additionalProperties").isObject()) {
				n.additional = sch.get("additionalProperties");
			}
			if (n.variants == null) n.variants = sch.has("anyOf") ? sch.get("anyOf") : sch.get("oneOf");
			sch = sch.hasNonNull("$ref") ? root.at(sch.get("$ref").asText().substring(1)) : null;
			if (sch != null && sch.isMissingNode()) sch = null;
		}
		return n;
	}

	private static boolean identity(Node n) {
		return n.props.containsKey("id") && takesIdentity(n.cls);
	}

	/// The variant a value is: the one whose constant property (the type
	/// discriminator) the value states; the only object variant otherwise.
	private static Node variantOf(JsonNode v, JsonNode variants, JsonNode root) {
		Node only = null;
		int objects = 0;
		for (JsonNode w : variants) {
			Node n = resolve(w, root);
			for (Map.Entry<String, JsonNode> p : n.props.entrySet()) {
				JsonNode c = p.getValue().has("const") ? p.getValue().get("const")
						: p.getValue().path("enum").size() == 1 ? p.getValue().get("enum").get(0) : null;
				if (c != null && c.equals(v.get(p.getKey()))) return n;
			}
			if (!n.props.isEmpty() || n.additional != null) {
				objects++;
				only = n;
			}
		}
		return objects == 1 ? only : null;
	}

	private static void walk(JsonNode v, JsonNode sch, JsonNode root, String path, Map<String, List<String>> ids,
			List<String[]> refs) {
		if (v == null || v.isNull() || sch == null) return;
		Node n = resolve(sch, root);
		if (n.variants != null && n.variants.isArray()) {
			if (v.isTextual()) {
				for (JsonNode w : n.variants) {
					if (identity(resolve(w, root))) {
						refs.add(new String[] { path, v.asText() });
						break;
					}
				}
				return;
			}
			if (!v.isObject()) return;
			Node picked = variantOf(v, n.variants, root);
			if (picked == null) return;
			n = picked;
		}
		boolean identity = identity(n);
		if (v.isTextual()) {
			if (identity) refs.add(new String[] { path, v.asText() });
			return;
		}
		if (v.isArray()) {
			for (int i = 0; i < v.size(); i++) walk(v.get(i), n.items, root, path + "[" + i + "]", ids, refs);
			return;
		}
		if (!v.isObject()) return;
		if (identity && v.path("id").isTextual()) {
			String simple = n.cls.substring(n.cls.lastIndexOf('.') + 1);
			ids.computeIfAbsent(v.get("id").asText(), k -> new ArrayList<>()).add(simple);
		}
		for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> e = it.next();
			JsonNode child = n.props.containsKey(e.getKey()) ? n.props.get(e.getKey()) : n.additional;
			walk(e.getValue(), child, root, path.isEmpty() ? e.getKey() : path + "." + e.getKey(), ids, refs);
		}
	}
}
