package org.vorpal.blade.framework.v3.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The merge the editors show is the settings loader's: objects key by key,
/// lists appended, anything else replaced; a file not written is skipped.
class ConfigFilesMergeTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode json(String s) throws Exception {
		return MAPPER.readTree(s.replace('\'', '"'));
	}

	@Test
	void listsAppendObjectsMergeValuesReplace() throws Exception {
		JsonNode merged = ConfigFiles.merge(Arrays.asList(
				json("{'list':[1,2],'o':{'a':1,'l':['p']},'v':'domain'}"),
				null,
				json("{'list':[3],'o':{'b':2,'l':['q']},'v':'server'}")), MAPPER);
		assertEquals(json("{'list':[1,2,3],'o':{'a':1,'l':['p','q'],'b':2},'v':'server'}"), merged);
	}

	@Test
	void anUpperFileAloneWhenTheDomainFileIsMissing() throws Exception {
		assertEquals(json("{'a':[1]}"), ConfigFiles.merge(Arrays.asList(null, json("{'a':[1]}")), MAPPER));
		assertNull(ConfigFiles.merge(Arrays.asList(null, null), MAPPER));
	}

	@Test
	void layersAreNotChanged() throws Exception {
		JsonNode domain = json("{'list':[1]}");
		ConfigFiles.merge(Arrays.asList(domain, json("{'list':[2]}")), MAPPER);
		assertEquals(json("{'list':[1]}"), domain);
	}

	@Test
	void aListOverAKeyedObjectMergesById() throws Exception {
		JsonNode merged = ConfigFiles.merge(Arrays.asList(
				json("{'pipeline':{'sip':{'type':'sip'},'table':{'type':'table'}}}"),
				json("{'pipeline':[{'id':'table','keyExpression':'k'},{'id':'rest','type':'rest'}]}")), MAPPER);
		assertEquals(json("{'pipeline':{'sip':{'type':'sip'},'table':{'type':'table','keyExpression':'k'},'rest':{'type':'rest'}}}"), merged);
	}

	@Test
	void layersOfTheSameShapesMergeAsJacksonAloneDoes() throws Exception {
		JsonNode a = json("{'rules':[{'id':'a'}],'o':{'k':{'x':1}},'v':1}");
		JsonNode b = json("{'rules':[{'id':'b'}],'o':{'k':{'y':2}},'v':2}");
		JsonNode jackson = MAPPER.readerForUpdating(a.deepCopy()).readValue((JsonNode) b.deepCopy());
		assertEquals(jackson, ConfigFiles.merge(Arrays.asList(a, b), MAPPER));
	}

	@Test
	void aListWithoutIdsIsNotKeyed() throws Exception {
		// Not keyed: an object over it replaces it, as before.
		JsonNode merged = ConfigFiles.merge(Arrays.asList(json("{'x':[{'n':1}]}"), json("{'x':{'n':2}}")), MAPPER);
		assertEquals(json("{'x':{'n':2}}"), merged);
	}

	@Test
	void aDraftThatRenamesARuleAnOverlayChangesIsReported() throws Exception {
		java.nio.file.Path cluster = ConfigFiles.BASE.resolve("_clusters/c2");
		java.nio.file.Path domainFile = ConfigFiles.BASE.resolve("orphantest.json");
		java.nio.file.Path clusterFile = cluster.resolve("orphantest.json");
		java.nio.file.Files.createDirectories(cluster);
		try {
			java.nio.file.Files.write(domainFile, json("{'crud':{'ruleSets':{'lab':{'rules':{'stamp':{'method':'INVITE'}}}}}}").toString().getBytes());
			java.nio.file.Files.write(clusterFile, json("{'crud':{'ruleSets':{'lab':{'rules':{'stamp':{'operations':[]}}}}}}").toString().getBytes());
			java.util.Map<String, String> none = new java.util.HashMap<>();
			java.util.List<String> renamed = ConfigFiles.orphans("orphantest", "domain",
					json("{'crud':{'ruleSets':{'lab':{'rules':{'stamp-invite':{'method':'INVITE'}}}}}}"), "crud", none, MAPPER);
			assertEquals(1, renamed.size(), renamed.toString());
			org.junit.jupiter.api.Assertions.assertTrue(renamed.get(0).contains("lab/stamp"), renamed.get(0));
			assertEquals(1, ConfigFiles.orphans("orphantest", "domain", json("{'crud':{}}"), "crud", none, MAPPER).size());
			// Kept, or changed in place: nothing.
			assertEquals(0, ConfigFiles.orphans("orphantest", "domain",
					json("{'crud':{'ruleSets':{'lab':{'rules':{'stamp':{'method':'BYE'}}}}}}"), "crud", none, MAPPER).size());
		} finally {
			java.nio.file.Files.deleteIfExists(clusterFile);
			java.nio.file.Files.deleteIfExists(domainFile);
			java.nio.file.Files.deleteIfExists(cluster);
			for (java.nio.file.Path d = cluster.getParent(); d != null; d = d.getParent()) {
				try {
					java.nio.file.Files.deleteIfExists(d);
				} catch (java.nio.file.DirectoryNotEmptyException kept) {
					break;
				}
			}
		}
	}

	@Test
	void aBrokenClusterFileSkipsOnlyTheOverlaysOverIt() throws Exception {
		java.nio.file.Path base = ConfigFiles.BASE;
		java.nio.file.Path domainFile = base.resolve("orphantest2.json");
		java.nio.file.Path c1 = base.resolve("_clusters/c1/orphantest2.json");
		java.nio.file.Path c2 = base.resolve("_clusters/c2/orphantest2.json");
		java.nio.file.Path s1 = base.resolve("_servers/s1/orphantest2.json");
		java.util.List<java.nio.file.Path> files = Arrays.asList(s1, c2, c1, domainFile);
		try {
			for (java.nio.file.Path f : files) java.nio.file.Files.createDirectories(f.getParent());
			java.nio.file.Files.write(domainFile, json("{'crud':{'ruleSets':{'main':{'rules':{'r1':{'method':'INVITE'}}}}}}").toString().getBytes());
			java.nio.file.Files.write(c1, "{ not json".getBytes());
			java.nio.file.Files.write(s1, "{}".getBytes());
			java.nio.file.Files.write(c2, json("{'crud':{'ruleSets':{'main':{'rules':{'r1':{'operations':[]}}}}}}").toString().getBytes());
			java.util.Map<String, String> membership = new java.util.HashMap<>();
			membership.put("s1", "c1");
			java.util.List<String> p = ConfigFiles.orphans("orphantest2", "domain",
					json("{'crud':{'ruleSets':{'renamed':{'rules':{'r1':{'method':'INVITE'}}}}}}"), "crud", membership, MAPPER);
			assertEquals(1, p.size(), p.toString());
			org.junit.jupiter.api.Assertions.assertTrue(p.get(0).contains("c2"), p.get(0));
		} finally {
			for (java.nio.file.Path f : files) {
				java.nio.file.Files.deleteIfExists(f);
				for (java.nio.file.Path d = f.getParent(); d != null && !d.equals(base.getParent()); d = d.getParent()) {
					try {
						java.nio.file.Files.deleteIfExists(d);
					} catch (java.nio.file.DirectoryNotEmptyException kept) {
						break;
					}
				}
			}
		}
	}
}
