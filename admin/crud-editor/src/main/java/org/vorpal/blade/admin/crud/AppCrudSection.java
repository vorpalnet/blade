package org.vorpal.blade.admin.crud;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.crud.Crud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// The file work behind [AppCrudServlet], kept out of the servlet so it runs
/// without a container: which applications carry a `crud` section, the
/// section's schema, and how an edited section goes back into a file.
final class AppCrudSection {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// The section's key in an application's configuration.
	static final String NAME = "crud";

	/// A deployment name: no separators, so it can never leave the config
	/// directory.
	static final Pattern APP_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

	private AppCrudSection() {
	}

	/// Every application whose schema carries a top-level `crud` property,
	/// sorted by name. The CRUD service itself is left out: its rules are the
	/// whole of `crud.json`, edited through [CrudConfigServlet].
	static TreeSet<String> appsWithCrud(Path schemaDir) throws IOException {
		TreeSet<String> apps = new TreeSet<>();
		if (!Files.isDirectory(schemaDir)) return apps;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(schemaDir, "*.jschema")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				String app = name.substring(0, name.length() - ".jschema".length());
				if (app.equals("crud") || !APP_NAME.matcher(app).matches()) continue;
				try {
					if (MAPPER.readTree(file.toFile()).path("properties").has(NAME)) apps.add(app);
				} catch (IOException unreadable) {
					// A schema being rewritten by a starting node: skip it this time.
				}
			}
		}
		return apps;
	}

	/// The `crud` section's schema as `app` itself spells it, cut from its
	/// `_schemas/<app>.jschema` (written by the app at startup): the app's
	/// `Crud` definition with the file's `$defs` beside it, so
	/// `properties.pipeline` and `$defs.Rule` sit where the page's form
	/// resolver reads them. An app on an older framework is offered only the
	/// fields it can load, in the shapes it reads (a list of rules, say), and
	/// the editor saves in those shapes. Without the file, the schema of the
	/// [Crud] class on this server.
	static JsonNode schema(Path schemaDir, String app) {
		try {
			Path file = schemaDir.resolve(app + ".jschema");
			if (Files.isRegularFile(file)) {
				JsonNode doc = MAPPER.readTree(file.toFile());
				JsonNode crud = doc.path("$defs").path("Crud");
				if (crud.isObject()) {
					ObjectNode out = ((ObjectNode) crud).deepCopy();
					out.set("$defs", doc.get("$defs"));
					out.put("x-schema-source", file.getFileName().toString());
					return out;
				}
			}
		} catch (IOException unreadable) {
			// A schema being rewritten by a starting node: use this server's.
		}
		return schema();
	}

	/// The [Crud] class's own schema, from the framework on this server.
	static JsonNode schema() {
		try {
			return SettingsManager.generateSchemaNode(Crud.class, MAPPER);
		} catch (Throwable t) {
			return null; // the editor degrades to raw-JSON forms
		}
	}

	/// Puts `section` into `doc` as `crud`, in place when the key exists so the
	/// file's order is kept. An empty section removes the key: no rules is
	/// the same as no section, and the file stays as the app wrote it.
	static void splice(ObjectNode doc, ObjectNode section) {
		boolean empty = section.path("ruleSets").size() == 0
				&& section.path("pipeline").size() == 0
				&& section.path("variables").size() == 0
				// `"defaultRuleSet": null` switches an inherited default off: kept.
				&& !(section.has("defaultRuleSet") && section.get("defaultRuleSet").isNull())
				&& section.path("defaultRuleSet").asText("").isEmpty();
		if (empty) {
			doc.remove(NAME);
		} else {
			doc.set(NAME, section);
		}
	}
}
