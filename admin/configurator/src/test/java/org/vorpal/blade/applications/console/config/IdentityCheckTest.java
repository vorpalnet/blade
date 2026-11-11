package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// [IdentityCheck] on the transfer service's generated schema: ids repeat
/// across classes and across merged files, never within v2 AttributeSelector.
class IdentityCheckTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String APP = "zzidtest";
	private static final Path BASE = Paths.get("config/custom/vorpal");

	private static JsonNode schema() throws IOException {
		try (InputStream in = IdentityCheckTest.class.getResourceAsStream("/transfer.jschema")) {
			return MAPPER.readTree(in);
		}
	}

	private static JsonNode json(String s) throws IOException {
		return MAPPER.readTree(s.replace('\'', '"'));
	}

	private static final String MAPS = "{'selectors':[{'id':'dialed','attribute':'To'}],"
			+ "'maps':[{'type':'prefix','id':'p','selectors':['dialed']},{'type':'hash','id':'h','selectors':['dialed']}],"
			+ "'plan':['p','h']}";

	@AfterEach
	void clean() throws IOException {
		if (!Files.exists(Paths.get("config"))) return;
		try (Stream<Path> files = Files.walk(Paths.get("config"))) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	@Test
	void aGoodFileHasNoProblems() throws IOException {
		assertTrue(IdentityCheck.usesIdentity(schema()));
		assertEquals(List.of(), IdentityCheck.problems(json(MAPS), schema()));
	}

	@Test
	void idsAreOneNamespaceAcrossClasses() throws IOException {
		List<String> p = IdentityCheck.problems(json(MAPS.replace("'id':'h'", "'id':'p'")), schema());
		assertTrue(p.get(0).contains("\"p\" is used by 2 objects (ConfigPrefixMap, ConfigHashMap)"), p.toString());
		p = IdentityCheck.problems(json(MAPS.replace("'id':'h'", "'id':'dialed'")), schema());
		assertTrue(p.get(0).contains("\"dialed\" is used by 2 objects (Selector, ConfigHashMap)"), p.toString());
	}

	@Test
	void anUnknownReferenceIsAProblem() throws IOException {
		List<String> p = IdentityCheck.problems(json(MAPS.replace("'plan':['p','h']", "'plan':['p','gone']")), schema());
		assertEquals(List.of("plan[1] refers to \"gone\", which nothing defines"), p);
	}

	@Test
	void attributeSelectorIdsMayRepeat() throws IOException {
		assertTrue(!IdentityCheck.takesIdentity("org.vorpal.blade.framework.v2.config.AttributeSelector"));
		assertTrue(IdentityCheck.takesIdentity("org.vorpal.blade.framework.v2.config.ConfigHashMap"));
		assertTrue(!IdentityCheck.takesIdentity("org.vorpal.blade.framework.v3.configuration.selectors.Selector"));
	}

	/// The save check: the identity check plus a schema check that never fails.
	private static org.vorpal.blade.framework.v3.configuration.DraftCheck.Check check() throws IOException {
		JsonNode schema = schema();
		return (doc, domainFile) -> IdentityCheck.problems(doc, schema);
	}

	private static List<String> added(String id, JsonNode draft, JsonNode before) throws IOException {
		return SaveCheck.added(APP, id, draft, before, new HashMap<>(), MAPPER, check());
	}

	@Test
	void anOverlayIsCheckedMergedWithTheDomainFile() throws IOException {
		write(APP + ".json", MAPS);
		Files.createDirectories(BASE.resolve("_clusters/c1"));
		// The overlay's list appends to the domain's: its "h" repeats the domain's.
		JsonNode overlay = json("{'maps':[{'type':'hash','id':'h'}]}");
		List<String> p = added("cluster/c1", overlay, MAPPER.createObjectNode());
		assertTrue(p.get(0).startsWith("merged with the files below it, the id \"h\""), p.toString());
		// A new id, and a reference to the domain's, are fine.
		overlay = json("{'maps':[{'type':'hash','id':'h2','selectors':['dialed']}],'plan':['h2']}");
		assertEquals(List.of(), added("cluster/c1", overlay, MAPPER.createObjectNode()));
	}

	@Test
	void aDomainSaveIsCheckedUnderEveryOverlay() throws IOException {
		write(APP + ".json", MAPS);
		write("_clusters/c1/" + APP + ".json", "{'plan':['h']}");
		// Renaming the domain's "h" leaves the cluster file's plan naming nothing.
		JsonNode domain = json(MAPS.replace("'id':'h'", "'id':'h-new'").replace("'plan':['p','h']", "'plan':['p','h-new']"));
		List<String> p = added("domain", domain, json(MAPS));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).startsWith("with ") && p.get(0).contains("refers to \"h\""), p.toString());
	}

	/// The file on disk does not parse: the save that fixes it is checked,
	/// not refused with a parse error about the file it replaces.
	@Test
	void aBrokenFileCanBeFixedBySaving() throws IOException {
		write(APP + ".json", "{'plan':['p',]");
		write("_clusters/c1/" + APP + ".json", "{'plan':['h']}");
		assertEquals(List.of(), added("domain", json(MAPS), null));
		// A fix that breaks the cluster's file is still refused: with no file
		// to compare with, every problem counts.
		List<String> p = added("domain", json("{'maps':[{'type':'prefix','id':'p'}],'plan':['p']}"), null);
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("refers to \"h\""), p.toString());
	}

	/// A cluster or server folder no server reads is not checked against.
	@Test
	void anUnreadOverlayIsNotChecked() throws IOException {
		write(APP + ".json", MAPS);
		write("_clusters/gone/" + APP + ".json", "{'plan':['h']}");
		JsonNode domain = json(MAPS.replace("'id':'h'", "'id':'h2'").replace("'p','h'", "'p','h2'"));
		assertEquals(List.of(), SaveCheck.added(APP, "domain", domain, json(MAPS), new HashMap<>(), MAPPER, check(),
				overlayId -> !overlayId.equals("cluster/gone")));
	}

	/// Reordering an old duplicate does not make it new.
	@Test
	void aReorderedDuplicateIsNotNew() throws IOException {
		String dup = MAPS.replace("'id':'h'", "'id':'p'").replace("'plan':['p','h']", "'plan':['p']");
		write(APP + ".json", dup);
		String swapped = "{'selectors':[{'id':'dialed','attribute':'To'}],"
				+ "'maps':[{'type':'hash','id':'p','selectors':['dialed']},{'type':'prefix','id':'p','selectors':['dialed']}],'plan':['p']}";
		assertEquals(List.of(), added("domain", json(swapped), json(dup)));
	}

	/// A file below that does not parse refuses every save over it.
	@Test
	void aBrokenFileBelowAlwaysRefuses() throws IOException {
		write(APP + ".json", "{'plan':");
		write("_clusters/c1/" + APP + ".json", "{'plan':['h']}");
		List<String> p = added("cluster/c1", json("{'plan':['p']}"), json("{'plan':['h']}"));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).startsWith("a file below this one does not parse"), p.toString());
	}

	/// The schema leg: legacy paths ($.a[1], which the comparison strips), a
	/// null accepted (Jackson loads it), a whole double accepted as integer.
	@Test
	void theSchemaCheck() throws IOException {
		JsonNode schema = json("{'$schema':'https://json-schema.org/draft/2020-12/schema','type':'object','properties':"
				+ "{'a':{'type':'array','items':{'type':'integer'}},'n':{'type':'string'}}}");
		org.vorpal.blade.framework.v3.configuration.DraftCheck.Check c = FileManagerServlet.runChecks(schema);
		try {
			assertEquals(List.of("$.a[1]: string found, integer expected"), c.problems(json("{'a':[1,'x',5.0],'n':null}"), true));
			assertEquals(List.of(), FileManagerServlet.runChecks(null).problems(json("{'a':'anything'}"), true));
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	/// A file over the draft that does not parse is named, not thrown.
	@Test
	void aBrokenOverlayIsNamed() throws IOException {
		write(APP + ".json", MAPS);
		write("_clusters/c1/" + APP + ".json", "{'plan':");
		List<String> p = SaveCheck.problems(APP, "domain", json(MAPS), new HashMap<>(), MAPPER, check());
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).contains("does not parse"), p.toString());
		// Broken before this save too: not what this save adds, so not refused.
		assertEquals(List.of(), added("domain", json(MAPS), json(MAPS)));
	}

	/// An old problem in the draft does not hide a new one in a file above it.
	@Test
	void anOldProblemDoesNotHideANewOne() throws IOException {
		String old = MAPS.replace("'plan':['p','h']", "'plan':['p','h','gone']");   // already dangling
		write(APP + ".json", old);
		write("_clusters/c1/" + APP + ".json", "{'plan':['h']}");
		JsonNode draft = json(old.replace("'id':'h'", "'id':'h2'").replace("'p','h','gone'", "'p','h2','gone'"));
		List<String> p = added("domain", draft, json(old));
		assertEquals(1, p.size(), p.toString());
		assertTrue(p.get(0).startsWith("with ") && p.get(0).contains("refers to \"h\""), p.toString());
	}

	/// An item inserted above an old problem moves its index: still not new.
	@Test
	void anOldProblemThatMovedIsNotNew() throws IOException {
		String old = MAPS.replace("'plan':['p','h']", "'plan':['gone','p']");
		write(APP + ".json", old);
		JsonNode draft = json(MAPS.replace("'plan':['p','h']", "'plan':['h','gone','p']"));
		assertEquals(List.of(), added("domain", draft, json(old)));
	}

	private static void write(String rel, String content) throws IOException {
		Path f = BASE.resolve(rel);
		Files.createDirectories(f.getParent());
		Files.write(f, content.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
	}
}
