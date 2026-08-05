package org.vorpal.blade.services.gateway;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v2.config.FormLayout;

/// Unit tests for the gateway config model — the novel parts (pluggable registration
/// "style", per‑virtual‑gateway Contact IP, masked password). The digest REGISTER flow +
/// timer refresh are OCCAS‑dependent (real SipFactory/TimerService) and are verified at
/// deploy time, not here.
public class GatewayConfigTest {

	private static final ObjectMapper M = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@Test
	public void sampleHasTwoGatewaysWithDistinctContactsAndStyles() {
		GatewaySettings sample = new GatewaySettingsSample();
		assertEquals(2, sample.getGateways().size());

		VirtualGateway digest = sample.getGateways().get(0);
		VirtualGateway ipauth = sample.getGateways().get(1);

		assertTrue("first gateway is register-digest", digest.getStyle() instanceof RegisterDigestStyle);
		assertTrue("second gateway is ip-auth", ipauth.getStyle() instanceof IpAuthStyle);
		assertNotEquals("virtual gateways bind distinct Contact IPs", digest.getContactHost(), ipauth.getContactHost());

		// newRegistrar: digest → a RegisterCallflow; ip-auth → null (no REGISTER)
		assertTrue(digest.getStyle().newRegistrar(digest) instanceof RegisterCallflow);
		assertNull(ipauth.getStyle().newRegistrar(ipauth));
	}

	@Test
	public void styleDiscriminatorRoundTrips() throws Exception {
		VirtualGateway vg = new VirtualGateway();
		vg.setName("t");
		vg.setContactHost("203.0.113.10");
		vg.setRegistrarDomain("sip.example.com");
		RegisterDigestStyle d = new RegisterDigestStyle();
		d.setAuthName("acct");
		vg.setStyle(d);

		String json = M.writeValueAsString(vg);
		assertTrue("emits the type discriminator", json.contains("\"type\":\"register-digest\""));

		VirtualGateway back = M.readValue(json, VirtualGateway.class);
		assertTrue("deserializes to the concrete style", back.getStyle() instanceof RegisterDigestStyle);
		assertEquals("acct", ((RegisterDigestStyle) back.getStyle()).getAuthName());

		// ip-auth variant
		VirtualGateway ip = new VirtualGateway();
		ip.setStyle(new IpAuthStyle());
		VirtualGateway ipBack = M.readValue(M.writeValueAsString(ip), VirtualGateway.class);
		assertTrue(ipBack.getStyle() instanceof IpAuthStyle);
	}

	@Test
	public void missingTypeFallsBackToRegisterDigest() throws Exception {
		RegistrationStyle s = M.readValue("{\"authName\":\"x\"}", RegistrationStyle.class);
		assertTrue("defaultImpl kicks in", s instanceof RegisterDigestStyle);
	}

	@Test
	public void passwordGetterIsMaskedForTheConfigurator() throws Exception {
		Method getPassword = RegisterDigestStyle.class.getMethod("getPassword");
		FormLayout fl = getPassword.getAnnotation(FormLayout.class);
		assertNotNull("password getter must carry @FormLayout", fl);
		assertTrue("password must be masked (format:password)", fl.password());
	}

	@Test
	public void trunkRequestUriIsBuiltForTheCarrier() {
		VirtualGateway vg = new VirtualGateway();
		vg.setRegistrarDomain("us-east-nj.sip.flowroute.com");
		vg.setTransport("tcp");
		assertEquals("sip:18165551234@us-east-nj.sip.flowroute.com;transport=tcp",
				vg.trunkRequestUri("18165551234"));
		assertEquals("sip:us-east-nj.sip.flowroute.com;transport=tcp", vg.trunkRequestUri(null));
	}

	@Test
	public void outboundIdentityDependsOnStyle() {
		RegisterDigestStyle d = new RegisterDigestStyle();
		d.setUserId("15551234567");
		assertEquals("register-digest presents the account/DID", "15551234567", d.outboundIdentity());
		assertNull("ip-auth leaves the caller's From unchanged", new IpAuthStyle().outboundIdentity());
	}

	@Test
	public void contactIpMatching() {
		VirtualGateway vg = new VirtualGateway();
		vg.setContactHost("203.0.113.10");
		vg.setContactPort(5060);

		assertTrue(vg.matchesInterface("203.0.113.10", 5060));
		assertTrue("case-insensitive host", vg.matchesInterface("203.0.113.10", 5060));
		assertTrue("port 0 is a wildcard", vg.matchesInterface("203.0.113.10", 0));
		assertFalse("wrong host", vg.matchesInterface("203.0.113.99", 5060));
		assertFalse("wrong port", vg.matchesInterface("203.0.113.10", 5061));
	}

	// ---------------------------------------------------------------- inbound direction

	@Test
	public void sourceHostsMatchBareIpsAndCidrBlocks() {
		VirtualGateway vg = new VirtualGateway();
		vg.setSourceHosts(Arrays.asList("203.0.113.0/24", "198.51.100.7"));

		assertTrue("inside the CIDR block", vg.matchesSource("203.0.113.55"));
		assertTrue("the block's own boundaries count", vg.matchesSource("203.0.113.0"));
		assertTrue("bare IP entry", vg.matchesSource("198.51.100.7"));
		assertFalse("outside every entry", vg.matchesSource("192.0.2.1"));
		assertFalse("adjacent to the block but outside it", vg.matchesSource("203.0.114.1"));
		assertFalse("no source at all", vg.matchesSource(null));
	}

	@Test
	public void emptySourceHostsAcceptsAnySourceOnTheInterface() {
		VirtualGateway vg = new VirtualGateway();
		assertTrue("unset means the Contact interface alone identifies the trunk",
				vg.matchesSource("192.0.2.1"));

		vg.setSourceHosts(null);
		assertTrue("null is normalized to empty, not a reject-all", vg.matchesSource("192.0.2.1"));
	}

	@Test
	public void malformedSourceHostEntryIsSkippedNotThrown() {
		VirtualGateway vg = new VirtualGateway();
		vg.setSourceHosts(Arrays.asList("not-an-ip", "", "203.0.113.0/24"));

		assertTrue("a bad entry must not stop the good ones matching", vg.matchesSource("203.0.113.55"));
		assertFalse(vg.matchesSource("192.0.2.1"));
	}

	@Test
	public void sourceHostsRoundTripAndAreOptionalInExistingConfigs() throws Exception {
		VirtualGateway vg = new VirtualGateway();
		vg.setName("t");
		vg.setSourceHosts(Arrays.asList("203.0.113.0/24"));

		VirtualGateway back = M.readValue(M.writeValueAsString(vg), VirtualGateway.class);
		assertEquals(Arrays.asList("203.0.113.0/24"), back.getSourceHosts());

		// A gateway.json written before the field existed must still load, and must not
		// start rejecting the carrier it was already accepting.
		VirtualGateway legacy = M.readValue(
				"{\"name\":\"t\",\"contactHost\":\"203.0.113.10\",\"registrarDomain\":\"sip.example.com\"}",
				VirtualGateway.class);
		assertNotNull("absent sourceHosts deserializes to empty, never null", legacy.getSourceHosts());
		assertTrue(legacy.getSourceHosts().isEmpty());
		assertTrue("legacy config keeps accepting its carrier", legacy.matchesSource("192.0.2.1"));
	}

	@Test
	public void sampleFlowrouteTrunkDescribesBothDirections() {
		VirtualGateway flowroute = new GatewaySettingsSample().getGateways().get(0);

		assertNotNull("outbound: where we call the carrier", flowroute.getRegistrarDomain());
		assertFalse("inbound: where the carrier calls us", flowroute.getSourceHosts().isEmpty());
	}
}
