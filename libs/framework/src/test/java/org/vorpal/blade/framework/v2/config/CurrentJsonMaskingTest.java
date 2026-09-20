package org.vorpal.blade.framework.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.auth.BasicAuthentication;
import org.vorpal.blade.framework.v3.configuration.connectors.LdapConnector;
import org.vorpal.blade.framework.v3.configuration.connectors.RestConnector;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The JSON a Configuration MBean serves hides credentials but keeps
/// everything else.
class CurrentJsonMaskingTest {

	private final ObjectMapper masking = Settings.masking(new ObjectMapper());

	@Test
	void credentialsAreMaskedAndOtherFieldsKept() throws Exception {
		LdapConnector ldap = new LdapConnector();
		ldap.setId("directory");
		ldap.setBindDn("cn=svc,dc=example,dc=com");
		ldap.setBindPassword("hunter2");

		RestConnector rest = new RestConnector();
		rest.setId("screening");
		rest.setAuthentication(new BasicAuthentication("svc", "correct-horse"));

		IRouterConfig config = new IRouterConfig();
		config.getPipeline().add(ldap);
		config.getPipeline().add(rest);

		String json = masking.writeValueAsString(config);
		JsonNode tree = new ObjectMapper().readTree(json);

		assertFalse(json.contains("hunter2"), json);
		assertFalse(json.contains("correct-horse"), json);
		assertEquals(Settings.MASK, tree.at("/pipeline/0/bindPassword").asText());
		assertEquals("cn=svc,dc=example,dc=com", tree.at("/pipeline/0/bindDn").asText());
		assertEquals(Settings.MASK, tree.at("/pipeline/1/authentication/password").asText());
		assertEquals("svc", tree.at("/pipeline/1/authentication/username").asText());
	}

	@Test
	void unsetCredentialStaysUnset() throws Exception {
		LdapConnector ldap = new LdapConnector();
		ldap.setId("anonymous-bind");
		String json = masking.writeValueAsString(ldap);
		assertFalse(json.contains(Settings.MASK), json);
		assertTrue(json.contains("anonymous-bind"), json);
	}

	@Test
	void theRuntimeMapperIsUntouched() throws Exception {
		ObjectMapper base = new ObjectMapper();
		Settings.masking(base);
		LdapConnector ldap = new LdapConnector();
		ldap.setBindPassword("hunter2");
		assertTrue(base.writeValueAsString(ldap).contains("hunter2"));
	}
}
