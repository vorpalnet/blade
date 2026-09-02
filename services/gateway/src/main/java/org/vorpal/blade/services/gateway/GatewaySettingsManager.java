package org.vorpal.blade.services.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipURI;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v3.configuration.SettingsManager;

public class GatewaySettingsManager extends SettingsManager<GatewaySettings> {

	public GatewaySettingsManager(SipServletContextEvent event) throws ServletException, IOException {
		super(event);
	}

	@Override
	protected GatewaySettings sample() {
		return new GatewaySettingsSample();
	}

	@Override
	protected void refreshed(GatewaySettings config) throws ServletParseException {

		// Read the container-parsed attribute (JSR-289 §S.1) rather than
		// SipServletContext.getOutboundInterfaces(): OCCAS 8.3's helper re-parses
		// the channel URIs and throws on the IPv6 loopback with a zone id
		// ("sips:[::1%lo]:5062"), killing init before any registration.
		@SuppressWarnings("unchecked")
		List<SipURI> interfaces = (List<SipURI>) GatewaySipServlet.servletCreatedEvent.getServletContext()
				.getAttribute("javax.servlet.sip.outboundInterfaces");
		GatewaySipServlet.outboundInterfaces = (interfaces != null) ? interfaces : new ArrayList<>();

		// Decide what changed (pure, container-free) then act on it. A trunk whose
		// settings did not change keeps its live registration untouched: no de-register,
		// no re-register, no gap. Only removed or changed trunks are de-registered (with
		// their OLD Contact/credentials, which the live registrar still holds), and only
		// new or changed trunks are registered. Shutdown de-registers whatever remains,
		// in GatewaySipServlet.servletDestroyed.
		Plan plan = reconcile(GatewaySipServlet.registrars, config.getGateways(), getMapper());

		for (TrunkRegistrar gone : plan.deregister) {
			sipLogger.info("gateway " + gone.getGateway().getName() + ": de-registering (removed or changed)");
			gone.stop();
		}

		List<TrunkRegistrar> live = new ArrayList<>(plan.keep);
		for (VirtualGateway vg : plan.register) {
			TrunkRegistrar registrar = (vg.getStyle() == null) ? null : vg.getStyle().newRegistrar(vg);
			if (registrar == null) {
				sipLogger.info("gateway " + vg.getName() + ": no registration required (ip-auth or none)");
				continue;
			}
			InetSocketAddress outbound = GatewaySipServlet.resolveOutbound(vg);
			if (vg.getContactHost() != null && outbound == null) {
				sipLogger.severe("gateway " + vg.getName() + ": contactHost " + vg.getContactHost()
						+ " is not a configured SIP outbound interface — skipping registration");
				continue;
			}
			try {
				registrar.start(outbound);
			} catch (Exception e) {
				sipLogger.severe("gateway " + vg.getName() + ": registration failed: " + e.getMessage());
				continue;
			}
			live.add(registrar);
		}

		GatewaySipServlet.registrars.clear();
		GatewaySipServlet.registrars.addAll(live);
	}

	/// The reconcile decision, keyed by trunk name (the trunk's identity: its log name
	/// and the target FSMAR routes to via ;vgw=). Split out from the container work in
	/// [#refreshed] so the classification, the part with the edge cases, is unit-testable
	/// without a SIP container:
	///
	///  - **unchanged** (same name, same settings) → `keep` the live registrar.
	///  - **changed** (same name, different settings) → `deregister` the old, `register` the new.
	///  - **removed** (running, absent from config) → `deregister`.
	///  - **added** (in config, not running) → `register`.
	static Plan reconcile(List<TrunkRegistrar> running, List<VirtualGateway> desired, ObjectMapper mapper) {
		Map<String, TrunkRegistrar> byName = new HashMap<>();
		for (TrunkRegistrar registrar : running) {
			byName.put(registrar.getGateway().getName(), registrar);
		}

		Plan plan = new Plan();
		for (VirtualGateway vg : desired) {
			TrunkRegistrar current = byName.remove(vg.getName());
			if (current != null && sameSettings(mapper, current.getGateway(), vg)) {
				plan.keep.add(current);
			} else {
				if (current != null) {
					plan.deregister.add(current);
				}
				plan.register.add(vg);
			}
		}
		plan.deregister.addAll(byName.values()); // whatever the new config no longer names
		return plan;
	}

	/// True if two trunk configs are identical, so a reload can leave an unchanged
	/// trunk's live registration untouched. Compares the beans by their serialized JSON
	/// (the config ObjectMapper already handles the polymorphic RegistrationStyle), which
	/// avoids hand-writing equals() across the whole style tree. If the compare can't run,
	/// treat the trunk as changed: re-registering is the safe fallback.
	static boolean sameSettings(ObjectMapper mapper, VirtualGateway a, VirtualGateway b) {
		try {
			return mapper.writeValueAsString(a).equals(mapper.writeValueAsString(b));
		} catch (Exception e) {
			return false;
		}
	}

	/// The outcome of [#reconcile]: which live registrars to keep, which to de-register,
	/// and which trunks to (re)register.
	static final class Plan {
		final List<TrunkRegistrar> keep = new ArrayList<>();
		final List<TrunkRegistrar> deregister = new ArrayList<>();
		final List<VirtualGateway> register = new ArrayList<>();
	}

}
