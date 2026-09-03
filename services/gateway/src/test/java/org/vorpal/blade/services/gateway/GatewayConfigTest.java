package org.vorpal.blade.services.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v2.config.FormLayout;

/// Unit tests for the gateway config model — the novel parts (pluggable registration
/// "style", masked password, the optional multi-homed outbound-interface hint). The
/// gateway is outbound only, so there is no inbound arrival-interface or source-allowlist
/// matching to test. The digest REGISTER flow + timer refresh are OCCAS-dependent (real
/// SipFactory/TimerService) and are verified at deploy time, not here.
public class GatewayConfigTest {

	private static final ObjectMapper M = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	@Test
	public void sampleHasTwoTrunksWithDistinctNamesAndStyles() {
		GatewaySettings sample = new GatewaySettingsSample();
		assertEquals(2, sample.getGateways().size());

		VirtualGateway digest = sample.getGateways().get(0);
		VirtualGateway ipauth = sample.getGateways().get(1);

		assertTrue("first trunk is register-digest", digest.getStyle() instanceof RegisterDigestStyle);
		assertTrue("second trunk is ip-auth", ipauth.getStyle() instanceof IpAuthStyle);
		assertNotEquals("trunks have distinct names", digest.getName(), ipauth.getName());

		// newRegistrar: digest -> a RegisterCallflow; ip-auth -> null (no REGISTER)
		assertTrue(digest.getStyle().newRegistrar(digest) instanceof RegisterCallflow);
		assertNull(ipauth.getStyle().newRegistrar(ipauth));
	}

	@Test
	public void styleDiscriminatorRoundTrips() throws Exception {
		VirtualGateway vg = new VirtualGateway();
		vg.setName("t");
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
	public void outboundInterfaceIsOptionalAndRoundTrips() throws Exception {
		// Unset is the single-homed default: absent from a legacy config deserializes to null.
		VirtualGateway legacy = M.readValue(
				"{\"name\":\"t\",\"registrarDomain\":\"sip.example.com\"}", VirtualGateway.class);
		assertNull("no pin requested when unset", legacy.getOutboundInterface());

		// Set is the multi-homed case: it round-trips.
		VirtualGateway vg = new VirtualGateway();
		vg.setName("t");
		vg.setOutboundInterface("admin.ashburn.vorpal.net");
		VirtualGateway back = M.readValue(M.writeValueAsString(vg), VirtualGateway.class);
		assertEquals("admin.ashburn.vorpal.net", back.getOutboundInterface());
	}
}
