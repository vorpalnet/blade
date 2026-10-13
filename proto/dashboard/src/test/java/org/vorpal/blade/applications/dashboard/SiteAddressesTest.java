package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/// A site claims an address by host name, IPv4 address, or CIDR block.
class SiteAddressesTest {

	@Test
	void theHostOfAnyAddressForm() {
		assertEquals("pbx.example.net", SiteAddresses.host("sip:+15550100@PBX.example.net:5060;transport=tcp"));
		assertEquals("pbx.example.net", SiteAddresses.host("<sip:pbx.example.net;lr>"));
		assertEquals("192.0.2.10", SiteAddresses.host("192.0.2.10"));
		assertEquals("pbx.example.net", SiteAddresses.host("pbx.example.net:5061"));
	}

	@Test
	void aSiteClaimsByNameAddressOrBlock() {
		List<String> site = Arrays.asList("pbx.example.net", "198.51.100.7", "192.0.2.0/28");
		assertTrue(SiteAddresses.matches(site, "sip:bob@pbx.example.net"));
		assertTrue(SiteAddresses.matches(site, "198.51.100.7"));
		assertTrue(SiteAddresses.matches(site, "192.0.2.15"), "inside the /28");
		assertFalse(SiteAddresses.matches(site, "192.0.2.16"), "just outside it");
		assertFalse(SiteAddresses.matches(site, "sip:bob@other.example.net"));
		assertFalse(SiteAddresses.matches(site, null));
	}

	@Test
	void aHostNameAlsoClaimsWhatItResolvesTo() {
		assertTrue(SiteAddresses.matches(Arrays.asList("localhost"), "127.0.0.1"));
	}

	@Test
	void theDatabaseHostAJdbcUrlNames() {
		assertEquals("db.example.com", SiteAddresses.jdbcHost("jdbc:oracle:thin:@//db.example.com:1521/orcl"));
		assertEquals("db.example.com", SiteAddresses.jdbcHost("jdbc:oracle:thin:@db.example.com:1521:ORCL"));
		assertEquals("adb.us-ashburn-1.oraclecloud.com", SiteAddresses.jdbcHost(
				"jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcps)(PORT=1522)(HOST=adb.us-ashburn-1.oraclecloud.com))(CONNECT_DATA=(SERVICE_NAME=x)))"));
		assertEquals("192.0.2.40", SiteAddresses.jdbcHost("jdbc:mysql://192.0.2.40:3306/blade"));
		assertEquals("sql.example.com", SiteAddresses.jdbcHost("jdbc:sqlserver://sql.example.com:1433;databaseName=blade"));
		assertEquals(null, SiteAddresses.jdbcHost("jdbc:oracle:thin:@bladeadb_tp?TNS_ADMIN=/u01/wallet"), "a TNS alias names no host");
		assertEquals(null, SiteAddresses.jdbcHost(null));
	}
}
