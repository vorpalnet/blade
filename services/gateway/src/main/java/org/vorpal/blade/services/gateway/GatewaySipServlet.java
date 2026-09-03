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

/// The gateway SIP servlet: **the outbound face of a SIP trunk**. Two jobs:
///
///  1. **Registration**: at startup, register each {@link VirtualGateway} per its
///     {@link RegistrationStyle}; de-register at shutdown. This keeps the trunk
///     authenticated so the carrier accepts our outbound calls (and, for a registered
///     trunk, so the carrier knows where to send inbound ones).
///  2. **Outbound** (BLADE to carrier): FSMAR routes an outbound INVITE here, naming the
///     trunk in the Route URI (`;vgw=<name>`). As a {@link B2buaServlet} it bridges the
///     call and {@link #callStarted} rewrites the outbound dialog onto that trunk:
///     Request-URI to {@link VirtualGateway#getRegistrarDomain()}, From to the
///     {@link RegistrationStyle}'s outbound identity, and (multi-homed engines only) the
///     source pinned to the trunk's {@link VirtualGateway#getOutboundInterface()}.
///
/// Inbound calls are not handled here. A carrier's INVITE lands on the engine and FSMAR
/// routes it straight to the answering app; the gateway never sees it, so it needs no
/// arrival-interface or source matching. FSMAR owns the policy (which trunk, by
/// dial-plan); this app owns the outbound mechanism FSMAR structurally can't do.
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

	/// The B2BUA is creating the second dialog for an outbound trunk call. FSMAR named the
	/// trunk in `;vgw=`, so this is BLADE to carrier: rewrite the outbound dialog onto that
	/// trunk ({@link #bridgeToTrunk}). An INVITE with no `;vgw=` was routed here by mistake
	/// (inbound carrier INVITEs go straight to the answering app, never to the gateway) and
	/// is rejected.
	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		try {
			SipServletRequest inbound = getIncomingRequest(outboundRequest);
			String vgwName = vgwOf(inbound != null ? inbound : outboundRequest);

			// Outbound trunk calls only: FSMAR names the trunk with ;vgw=. No vgw means
			// this call was routed here by mistake (inbound INVITEs never reach the gateway).
			if (vgwName == null) {
				sipLogger.warning("gateway: INVITE with no vgw param — the gateway routes outbound trunk calls only; rejecting");
				doNotProcess(outboundRequest, 404, "Not a gateway trunk call");
				return;
			}
			VirtualGateway vg = findGateway(vgwName);
			if (vg == null) {
				sipLogger.warning("gateway: outbound INVITE with unknown vgw '" + vgwName + "' — rejecting");
				doNotProcess(outboundRequest, 404, "Unknown virtual gateway");
				return;
			}
			bridgeToTrunk(vg, outboundRequest);
		} catch (Exception e) {
			sipLogger.severe("gateway: callStarted failed: " + e.getMessage());
		}
	}

	/// BLADE to carrier. Rewrite the outbound dialog onto `vg`: Request-URI to the carrier
	/// trunk, From to the trunk identity, source pinned to the trunk's outbound interface
	/// when one is configured.
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

		// 3) Source -> the trunk's outbound interface (multi-homed engines only; unset
		// means the container's single interface, which its public-address advertises).
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

	/// Resolve `vg`'s optional {@link VirtualGateway#getOutboundInterface()} to a container
	/// SIP outbound interface (for `SipSession.setOutboundInterface`), so an outbound
	/// REGISTER/INVITE can be pinned to it on a multi-homed engine. Null when no interface
	/// is requested (the common single-homed case) or the requested one isn't among the
	/// container's interfaces; the caller then originates on the container default.
	static InetSocketAddress resolveOutbound(VirtualGateway vg) {
		List<SipURI> interfaces = outboundInterfaces;
		String want = vg.getOutboundInterface();
		if (want == null || interfaces == null) {
			return null; // single-homed / no pin requested: originate on the container default
		}
		for (SipURI uri : interfaces) {
			// Accessors, not just construction, can throw on OCCAS 8.3: the
			// container's SipURI wrappers parse lazily, and the IPv6 loopback
			// channel with a zone id ("sips:[::1%lo]:5062") fails that parse on
			// getHost(). One unusable interface must not kill the whole resolve.
			try {
				if (want.equalsIgnoreCase(uri.getHost())) {
					int port = uri.getPort() > 0 ? uri.getPort() : 5060;
					return new InetSocketAddress(uri.getHost(), port);
				}
			} catch (RuntimeException unparseable) {
				continue;
			}
		}
		return null; // requested interface not present: caller falls back to the default
	}
}
