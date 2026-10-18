package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// The file's values against the running configuration's.
class ConfigDriftTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static String diff(String file, String running) throws Exception {
		return ConfigDrift.firstDifference(MAPPER.readTree(file), MAPPER.readTree(running));
	}

	@Test
	void defaultsTheFileLeavesOutDoNotCount() throws Exception {
		assertNull(diff("{\"a\":1,\"b\":{\"c\":\"x\"}}", "{\"a\":1.0,\"b\":{\"c\":\"x\",\"d\":true},\"e\":[1]}"));
	}

	@Test
	void aValueTheApplicationDoesNotRunIsNamedByItsPath() throws Exception {
		assertEquals("differs at b.c", diff("{\"a\":1,\"b\":{\"c\":\"x\"}}", "{\"a\":1,\"b\":{\"c\":\"y\"}}"));
		assertEquals("differs at list", diff("{\"list\":[1,2]}", "{\"list\":[1]}"));
		assertEquals("differs at list", diff("{\"list\":[{\"id\":\"a\",\"v\":1}]}", "{\"list\":[{\"id\":\"a\",\"v\":2}]}"));
	}

	@Test
	void aSettingTheApplicationDoesNotHaveIsSaidSo() throws Exception {
		assertEquals("does not know gone", diff("{\"gone\":3}", "{}"));
		assertEquals("does not know gws[gw1].contactHost",
				diff("{\"gws\":[{\"name\":\"gw1\",\"contactHost\":\"h\"}]}", "{\"gws\":[{\"name\":\"gw1\"}]}"));
	}

	@Test
	void listsMatchByIdNotOrderAndKeepTheirDefaults() throws Exception {
		assertNull(diff("{\"l\":[{\"id\":\"a\",\"x\":1},{\"id\":\"b\",\"x\":2}]}",
				"{\"l\":[{\"id\":\"b\",\"x\":2,\"dflt\":0},{\"id\":\"a\",\"x\":1}]}"));
	}

	@Test
	void aHiddenSecretAgrees() throws Exception {
		assertNull(diff("{\"password\":\"s3cret\"}", "{\"password\":\"********\"}"));
	}

	@Test
	void aRepeatedIdKeepsItsPosition() throws Exception {
		String l = "{\"s\":[{\"id\":\"ani\",\"attribute\":\"P-Asserted-Identity\"},{\"id\":\"ani\",\"attribute\":\"From\"}]}";
		assertNull(diff(l, l));
		assertEquals("differs at s", diff(l, "{\"s\":[{\"id\":\"ani\",\"attribute\":\"From\"},{\"id\":\"ani\",\"attribute\":\"P-Asserted-Identity\"}]}"),
				"the order of a repeated id is the order they are tried");
	}
}
