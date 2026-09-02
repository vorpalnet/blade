package org.vorpal.blade.services.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.services.gateway.GatewaySettingsManager.Plan;

/// Unit tests for the config-reload reconcile: on a config change the gateway must
/// de-register only trunks that were removed or actually changed, and leave an
/// unchanged trunk's live registration alone (no de-register, no re-register, no gap).
/// The register/refresh/de-register SIP flow itself is OCCAS-dependent and verified at
/// deploy time; this covers the pure keep/register/de-register decision.
public class GatewayReconcileTest {

	private static final ObjectMapper M = new ObjectMapper();

	/// A container-free stand-in for a live registrar: construction touches no
	/// SipFactory (only start() would), so it is safe outside the container.
	private static final class FakeRegistrar extends TrunkRegistrar {
		private static final long serialVersionUID = 1L;
		boolean stopped = false;

		FakeRegistrar(VirtualGateway gateway) {
			super(gateway);
		}

		@Override
		public void start(InetSocketAddress outboundInterface) {
			// no-op: the SIP REGISTER flow is deploy-verified, not exercised here
		}

		@Override
		public void stop() {
			stopped = true;
		}
	}

	private static VirtualGateway digestTrunk(String name, String password) {
		VirtualGateway vg = new VirtualGateway();
		vg.setName(name);
		vg.setContactHost("203.0.113.10");
		vg.setRegistrarDomain("sip.example.com");
		RegisterDigestStyle style = new RegisterDigestStyle();
		style.setAuthName(name);
		style.setPassword(password);
		vg.setStyle(style);
		return vg;
	}

	private static List<String> names(List<VirtualGateway> gateways) {
		List<String> out = new ArrayList<>();
		for (VirtualGateway vg : gateways) {
			out.add(vg.getName());
		}
		return out;
	}

	@Test
	public void unchangedTrunkIsLeftAlone() {
		VirtualGateway a = digestTrunk("A", "secret");
		FakeRegistrar running = new FakeRegistrar(a);

		// Same trunk, freshly deserialized (a real reload builds new instances).
		VirtualGateway aReloaded = digestTrunk("A", "secret");

		Plan plan = GatewaySettingsManager.reconcile(Arrays.asList(running), Arrays.asList(aReloaded), M);

		assertEquals("the live registrar is kept", Arrays.asList(running), plan.keep);
		assertTrue("nothing de-registered", plan.deregister.isEmpty());
		assertTrue("nothing re-registered", plan.register.isEmpty());
		assertFalse("the unchanged trunk's registration is not torn down", running.stopped);
	}

	@Test
	public void changedTrunkIsReRegistered() {
		VirtualGateway a = digestTrunk("A", "old-password");
		FakeRegistrar running = new FakeRegistrar(a);

		VirtualGateway aChanged = digestTrunk("A", "new-password");

		Plan plan = GatewaySettingsManager.reconcile(Arrays.asList(running), Arrays.asList(aChanged), M);

		assertTrue("nothing kept", plan.keep.isEmpty());
		assertEquals("old registration is de-registered", Arrays.asList(running), plan.deregister);
		assertEquals("new settings are registered", Arrays.asList("A"), names(plan.register));
	}

	@Test
	public void removedTrunkIsDeregisteredOnly() {
		FakeRegistrar a = new FakeRegistrar(digestTrunk("A", "secret"));
		FakeRegistrar b = new FakeRegistrar(digestTrunk("B", "secret"));

		// Config now names only A.
		Plan plan = GatewaySettingsManager.reconcile(
				Arrays.asList(a, b), Arrays.asList(digestTrunk("A", "secret")), M);

		assertEquals("A is kept", Arrays.asList((TrunkRegistrar) a), plan.keep);
		assertEquals("B (dropped from config) is de-registered", Arrays.asList((TrunkRegistrar) b), plan.deregister);
		assertTrue("A is not re-registered", plan.register.isEmpty());
	}

	@Test
	public void addedTrunkIsRegisteredOnly() {
		FakeRegistrar a = new FakeRegistrar(digestTrunk("A", "secret"));

		Plan plan = GatewaySettingsManager.reconcile(
				Arrays.asList(a),
				Arrays.asList(digestTrunk("A", "secret"), digestTrunk("B", "secret")), M);

		assertEquals("A is kept", Arrays.asList((TrunkRegistrar) a), plan.keep);
		assertTrue("nothing de-registered", plan.deregister.isEmpty());
		assertEquals("only the new trunk B is registered", Arrays.asList("B"), names(plan.register));
	}

	@Test
	public void firstLoadRegistersEverythingWithNothingRunning() {
		Plan plan = GatewaySettingsManager.reconcile(
				new ArrayList<>(),
				Arrays.asList(digestTrunk("A", "secret"), digestTrunk("B", "secret")), M);

		assertTrue(plan.keep.isEmpty());
		assertTrue(plan.deregister.isEmpty());
		assertEquals(Arrays.asList("A", "B"), names(plan.register));
	}

	@Test
	public void sameSettingsDetectsAPasswordChange() {
		VirtualGateway a = digestTrunk("A", "secret");
		assertTrue("identical trunks compare equal",
				GatewaySettingsManager.sameSettings(M, a, digestTrunk("A", "secret")));
		assertFalse("a changed password is detected",
				GatewaySettingsManager.sameSettings(M, a, digestTrunk("A", "rotated")));
	}
}
