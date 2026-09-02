package org.vorpal.blade.services.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v3.B2buaServlet;

/// The gateway SIP servlet — **the app that terminates a SIP trunk, in both directions**.
/// Three jobs:
///
///  1. **Registration**: at startup, register each {@link VirtualGateway} (per its
///     {@link RegistrationStyle}) bound to its own Contact IP; de‑register at shutdown.
///  2. **Outbound** (BLADE → carrier): FSMAR routes an outbound INVITE here, naming the trunk in
///     the Route URI (`;vgw=<name>`). As a {@link B2buaServlet} it bridges the call and
///     {@link #callStarted} rewrites the outbound dialog onto that virtual gateway — Request‑URI to
///     the carrier, From to the trunk identity, source bound to the gateway's Contact IP
///     (`setOutboundInterface`).
///  3. **Inbound** (carrier → BLADE): the call arrives on a trunk's own Contact IP, so the
///     **arrival interface identifies the trunk** ({@link VirtualGateway#matchesInterface}) with
///     no `;vgw=` present. The source is checked against the trunk's
///     {@link VirtualGateway#getSourceHosts()} and the bridge carries the call onward; FSMAR runs
///     again on the B2BUA's second dialog (`previousApp = gateway`) and routes it to the app that
///     answers.
///
/// The two directions are told apart by the `vgw` param: FSMAR sets it going out, and a carrier
/// never sets it coming in.
///
/// FSMAR owns the *policy* (which trunk, chosen by dial‑plan/conditions); this app owns the
/// *mechanism* FSMAR structurally can't do.
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class GatewaySipServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;

	/// Route/Request‑URI param FSMAR sets to name the virtual gateway for an
	/// outbound call.
	static final String VGW_PARAM = "vgw";

	public static GatewaySettingsManager settings;
	static final List<TrunkRegistrar> registrars = new ArrayList<>();
	static volatile List<SipURI> outboundInterfaces;
	public static SipServletContextEvent servletCreatedEvent;

	// ==================================================== registration (phase 1)

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			servletCreatedEvent = event;
			settings = new GatewaySettingsManager(event);

		} catch (Exception e) {
			sipLogger.severe("GatewaySipServlet init failed: " + e.getMessage());
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		for (TrunkRegistrar registrar : registrars) {
			try {
				registrar.stop();
			} catch (Exception ignore) {
				// best effort
			}
		}
		registrars.clear();
		if (settings != null) {
			try {
				settings.unregister();
			} catch (Exception ignore) {
			}
		}
	}

	// ============================================================ the bridge, both
	// directions

	/// The B2BUA is creating the second dialog. Which direction this call is going
	/// decides what
	/// happens to it:
	///
	/// - **`;vgw=` present** — FSMAR named a trunk, so this is BLADE → carrier:
	/// rewrite the
	/// outbound dialog onto that trunk ({@link #bridgeToTrunk}).
	/// - **no `;vgw=`, but the call arrived on a trunk's Contact IP** — carrier →
	/// BLADE: check
	/// the source and let the call through to the rest of the chain ({@link
	/// #bridgeFromTrunk}).
	/// - **neither** — nothing here owns this call; reject it.
	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		try {
			SipServletRequest inbound = getIncomingRequest(outboundRequest);
			String vgwName = vgwOf(inbound != null ? inbound : outboundRequest);

			if (vgwName != null) {
				VirtualGateway vg = findGateway(vgwName);
				if (vg == null) {
					sipLogger.warning("gateway: outbound INVITE with unknown vgw '" + vgwName + "' — rejecting");
					doNotProcess(outboundRequest, 404, "Unknown virtual gateway");
					return;
				}
				bridgeToTrunk(vg, outboundRequest);
				return;
			}

			VirtualGateway arrival = findGatewayByArrival(inbound);
			if (arrival != null) {
				bridgeFromTrunk(arrival, inbound, outboundRequest);
				return;
			}

			sipLogger.warning("gateway: INVITE with no vgw param, on no configured trunk interface — rejecting");
			doNotProcess(outboundRequest, 404, "Unknown virtual gateway");
		} catch (Exception e) {
			sipLogger.severe("gateway: callStarted failed: " + e.getMessage());
		}
	}

	/// BLADE → carrier. Rewrite the outbound dialog onto `vg`: Request‑URI to the
	/// carrier trunk, From
	/// to the trunk identity, source bound to the trunk's Contact IP.
	private void bridgeToTrunk(VirtualGateway vg, SipServletRequest outboundRequest)
			throws ServletException, IOException {

		// The dialed number is the user part of the (copied) Request-URI.
		String number = (outboundRequest.getRequestURI() instanceof SipURI)
				? ((SipURI) outboundRequest.getRequestURI()).getUser()
				: null;

		// 1) Request-URI -> the carrier trunk.
		outboundRequest.setRequestURI(sipFactory.createURI(vg.trunkRequestUri(number)));

		// 2) From -> the trunk identity (best effort; some containers restrict system
		// headers).
		String identity = (vg.getStyle() != null) ? vg.getStyle().outboundIdentity() : null;
		if (identity != null) {
			try {
				Address from = sipFactory.createAddress("<sip:" + identity + "@" + vg.getRegistrarDomain() + ">");
				outboundRequest.setAddressHeader("From", from);
			} catch (Exception e) {
				sipLogger.warning("gateway " + vg.getName() + ": could not set trunk From: " + e.getMessage());
			}
		}

		// 3) Source -> the gateway's Contact IP.
		InetSocketAddress outbound = resolveOutbound(vg);
		if (outbound != null) {
			try {
				outboundRequest.getSession().setOutboundInterface(outbound);
			} catch (Exception e) {
				sipLogger.warning("gateway " + vg.getName() + ": setOutboundInterface failed: " + e.getMessage());
			}
		}

		sipLogger.info("gateway " + vg.getName() + ": outbound INVITE -> " + outboundRequest.getRequestURI());

		// BOUNDARY: outbound-INVITE digest auth is NOT handled here. Registered
		// (post-REGISTER)
		// and ip-auth trunks accept outbound INVITEs from the authenticated source, so
		// this is
		// rarely needed. It also can't be done from callStarted: the stock bridge
		// treats a carrier
		// 401/407 as a failure and propagates it to the caller
		// (InitialInvite.processContinue,
		// v2/b2bua/InitialInvite.java:197-206) — answering the challenge needs a
		// re-auth-aware
		// outbound dialog (a gateway InitialInvite variant, mirroring
		// RegisterCallflow.onResponse's
		// createRequest(response,"INVITE") + addAuthHeader + loop guard). Add it if a
		// carrier
		// re-challenges INVITEs.
	}

	/// Carrier → BLADE. The trunk is already known (the call landed on its Contact
	/// IP); confirm the
	/// call actually came from that carrier, then leave the second dialog alone.
	///
	/// The dialog keeps the dialed Request-URI, so FSMAR — which runs again on this
	/// dialog with
	/// `previousApp = gateway` — routes it onward to whichever app answers. That
	/// handoff is the
	/// whole point: the answering app never learns which carrier called.
	private void bridgeFromTrunk(VirtualGateway vg, SipServletRequest inbound, SipServletRequest outboundRequest)
			throws ServletException, IOException {

		String source = (inbound != null) ? inbound.getRemoteAddr() : null;
		if (!vg.matchesSource(source)) {
			sipLogger.warning("gateway " + vg.getName() + ": inbound INVITE from " + source
					+ " is not one of this trunk's sourceHosts — rejecting");
			doNotProcess(outboundRequest, 403, "Source not permitted on this trunk");
			return;
		}

		sipLogger.info("gateway " + vg.getName() + ": inbound INVITE from " + source + " -> "
				+ outboundRequest.getRequestURI());
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	// ============================================================ helpers

	/// The virtual-gateway name FSMAR named for this call — the `vgw` param on the
	/// popped Route
	/// (how FSMAR hands it over) or, as a fallback, on the Request-URI.
	static String vgwOf(SipServletRequest request) {
		if (request == null) {
			return null;
		}
		try {
			Address popped = request.getPoppedRoute();
			if (popped != null && popped.getURI() instanceof SipURI) {
				String v = ((SipURI) popped.getURI()).getParameter(VGW_PARAM);
				if (v != null) {
					return v;
				}
			}
		} catch (Exception ignore) {
			// no popped route
		}
		if (request.getRequestURI() instanceof SipURI) {
			return ((SipURI) request.getRequestURI()).getParameter(VGW_PARAM);
		}
		return null;
	}

	/// The trunk an inbound request landed on. Each virtual gateway advertises its
	/// own Contact IP,
	/// which is the address its carrier was told to send calls to — so the local
	/// end of the socket
	/// names the trunk, with no knowledge of the carrier's source ranges required.
	static VirtualGateway findGatewayByArrival(SipServletRequest inbound) {
		if (inbound == null || settings == null) {
			return null;
		}
		String host = inbound.getLocalAddr();
		if (host == null) {
			return null;
		}
		int port = inbound.getLocalPort();
		for (VirtualGateway vg : settings.getCurrent().getGateways()) {
			if (vg.matchesInterface(host, port)) {
				return vg;
			}
		}
		return null;
	}

	private static VirtualGateway findGateway(String name) {
		if (name == null || settings == null) {
			return null;
		}
		for (VirtualGateway vg : settings.getCurrent().getGateways()) {
			if (name.equals(vg.getName())) {
				return vg;
			}
		}
		return null;
	}

	/// Resolve `vg`'s Contact IP to a container SIP outbound interface (for
	/// `SipSession.setOutboundInterface`), or null if none matches.
	static InetSocketAddress resolveOutbound(VirtualGateway vg) {
		List<SipURI> interfaces = outboundInterfaces;
		if (vg.getContactHost() == null || interfaces == null) {
			return null;
		}
		for (SipURI uri : interfaces) {
			// Accessors, not just construction, can throw on OCCAS 8.3: the
			// container's SipURI wrappers parse lazily, and the IPv6 loopback
			// channel with a zone id ("sips:[::1%lo]:5062") fails that parse on
			// getHost(). One unusable interface must not kill the whole resolve.
			try {
				if (vg.matchesInterface(uri.getHost(), uri.getPort())) {
					int port = uri.getPort() > 0 ? uri.getPort() : vg.getContactPort();
					return new InetSocketAddress(uri.getHost(), port);
				}
			} catch (RuntimeException unparseable) {
				continue;
			}
		}
		return null;
	}
}
