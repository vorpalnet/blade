package org.vorpal.blade.admin.crud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v3.crud.Crud;
import org.vorpal.blade.framework.v3.crud.CrudConfiguration;
import org.vorpal.blade.framework.v3.crud.PreviewEngine;
import org.vorpal.blade.framework.v3.crud.PreviewEngine.PreviewResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// An application's saved CRUD test cases: a preview's inputs and the
/// result it should give, kept in `config/custom/vorpal/_tests/<app>.json`
/// and run by [PreviewEngine] exactly as the preview runs.
///
/// ```json
/// {
///   "reject a lab call": {
///     "ruleSet": "lab", "event": "send", "leg": "callee", "requestSdp": true,
///     "message": "INVITE sip:reject@… SIP/2.0\r\n…",
///     "variables": { "toUser": "reject" },
///     "expect": { "refused": "603 Refused by CRUD", "output": "INVITE sip:…" }
///   }
/// }
/// ```
///
/// `file` is the configuration file the test was recorded on (`domain`,
/// `cluster/<name>`, `server/<name>`; absent means `domain`), and the test
/// runs against what that file runs: a cluster or server file changes
/// behaviour on purpose, so its tests are its own.
/// `expect.refused` is the refusal the caller would get, or absent for none;
/// `expect.output`, when present, is the whole message the rules produce.
/// Tests sit beside the configuration, not in it: they are an editor's, never
/// load on an engine, and so suit an application on any framework.
final class CrudTests {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Path DIR = Paths.get("config/custom/vorpal/_tests");

	private CrudTests() {
	}

	static Path file(String app) {
		return DIR.resolve(app + ".json");
	}

	/// The saved tests, `{}` when there are none.
	static ObjectNode load(String app) throws IOException {
		Path f = file(app);
		if (!Files.isRegularFile(f)) return MAPPER.createObjectNode();
		JsonNode n = MAPPER.readTree(f.toFile());
		return n instanceof ObjectNode ? (ObjectNode) n : MAPPER.createObjectNode();
	}

	/// Why the saved tests cannot run, as a Save warning: their file does not
	/// read. Empty when it reads, or there is none.
	static java.util.List<String> unreadable(String app) {
		try {
			load(app);
			return java.util.Collections.emptyList();
		} catch (IOException e) {
			return java.util.Collections.singletonList("saved tests did not run: " + file(app).getFileName()
					+ " cannot be read (" + e.getMessage() + "); fix or remove it");
		}
	}

	static void save(String app, ObjectNode tests) throws IOException {
		Files.createDirectories(DIR);
		// A temp file of its own, so two saves never write the same one.
		Path tmp = Files.createTempFile(DIR, app + ".", ".tmp");
		Files.write(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(tests));
		Files.move(tmp, file(app), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
				java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}

	/// A configuration the engine can run, from an application's `crud`
	/// section.
	static CrudConfiguration of(Crud crud) {
		CrudConfiguration config = new CrudConfiguration();
		config.setVariables(new LinkedHashMap<>(crud.getVariables()));
		config.setPipeline(new LinkedHashMap<>(crud.getPipeline()));
		config.setDefaultRuleSet(crud.getDefaultRuleSet());
		config.setRuleSets(new LinkedHashMap<>(crud.getRuleSets()));
		return config;
	}

	/// The file a test belongs to.
	static String fileOf(JsonNode test) {
		String f = text(test, "file");
		return f == null ? "domain" : f;
	}

	/// One result per test recorded on `file`, in order: `{name, pass, why}`.
	static ArrayNode run(CrudConfiguration config, JsonNode tests, String file) {
		String wanted = (file == null || file.isEmpty()) ? "domain" : file;
		ArrayNode out = MAPPER.createArrayNode();
		for (Iterator<Map.Entry<String, JsonNode>> it = tests.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> t = it.next();
			if (!fileOf(t.getValue()).equals(wanted)) continue;
			ObjectNode row = out.addObject();
			row.put("name", t.getKey());
			String why = failure(config, t.getValue());
			row.put("pass", why == null);
			if (why != null) row.put("why", why);
		}
		return out;
	}

	/// What each failing test recorded on `file` got wrong, as
	/// `test "name": …`, for Save.
	static List<String> failures(CrudConfiguration config, JsonNode tests, String file) {
		List<String> out = new ArrayList<>();
		for (JsonNode r : run(config, tests, file)) {
			if (!r.path("pass").asBoolean()) out.add("test \"" + r.path("name").asText() + "\": " + r.path("why").asText());
		}
		return out;
	}

	/// Null when `test` passes, else what differed.
	static String failure(CrudConfiguration config, JsonNode test) {
		// The test's own inputs; the config's variables sit beneath the call
		// in the preview, as on the engines.
		Map<String, String> vars = new LinkedHashMap<>();
		test.path("variables").fields().forEachRemaining(e -> vars.put(e.getKey(), e.getValue().asText()));
		JsonNode sdp = test.get("requestSdp");
		PreviewResult result;
		try {
			result = PreviewEngine.preview(config, text(test, "ruleSet"), test.path("message").asText(""),
					text(test, "event"), vars, text(test, "leg"), sdp == null || sdp.isNull() ? null : sdp.asBoolean());
		} catch (RuntimeException e) {
			return "could not run: " + e.getMessage();
		}
		if (result.error != null) return result.error;
		JsonNode expect = test.path("expect");
		String refused = text(expect, "refused");
		if (refused == null ? result.rejected != null : !refused.equals(result.rejected)) {
			return "expected " + (refused == null ? "no refusal" : "refused " + refused) + ", got "
					+ (result.rejected == null ? "no refusal" : "refused " + result.rejected);
		}
		String output = text(expect, "output");
		if (output != null && !normalize(output).equals(normalize(result.output))) {
			return "output differs at line " + firstDifference(normalize(output), normalize(result.output));
		}
		return null;
	}

	private static String text(JsonNode n, String field) {
		JsonNode v = n.get(field);
		return (v == null || v.isNull() || v.asText().isEmpty()) ? null : v.asText();
	}

	/// Line endings aside, as the editor's text box may change them.
	private static String normalize(String s) {
		return s == null ? "" : s.replace("\r\n", "\n").trim();
	}

	private static String firstDifference(String a, String b) {
		String[] x = a.split("\n", -1), y = b.split("\n", -1);
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			String l = i < x.length ? x[i] : "", r = i < y.length ? y[i] : "";
			if (!l.equals(r)) return (i + 1) + ": expected \"" + l + "\", got \"" + r + "\"";
		}
		return "?";
	}
}
