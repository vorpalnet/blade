package org.vorpal.blade.proto.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletContext;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.B2buaServlet;

/// The gateway SIP servlet. Two jobs:
///
///  1. **Registration** (phase 1): at startup, register each {@link VirtualGateway} (per its
///     {@link RegistrationStyle}) bound to its own Contact IP; de‑register at shutdown.
///  2. **Outbound bridge** (phase 2a): FSMAR routes an outbound INVITE here, naming the trunk in
///     the Route URI (`;vgw=<name>`). As a {@link B2buaServlet} it bridges the call; the
///     {@link #callStarted} hook rewrites the outbound leg onto the chosen virtual gateway —
///     Request‑URI to the carrier, From to the trunk identity, and the source bound to the
///     gateway's Contact IP (`setOutboundInterface`).
///
/// FSMAR owns the *policy* (which trunk, chosen by dial‑plan/conditions, visible as an Egress
/// node); this app owns the *mechanism* FSMAR structurally can't do.
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class GatewaySipServlet extends B2buaServlet {
	private static final long serialVersionUID = 1L;

	/// Route/Request‑URI param FSMAR sets to name the virtual gateway for an outbound call.
	static final String VGW_PARAM = "vgw";

	public static SettingsManager<GatewaySettings> settings;
	private static final List<TrunkRegistrar> registrars = new ArrayList<>();
	private static volatile List<SipURI> outboundInterfaces;

	// ============================================================ registration (phase 1)

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settings = new SettingsManager<>(event, GatewaySettings.class, new GatewaySettingsSample());

			@SuppressWarnings("unchecked")
			List<SipURI> interfaces = ((SipServletContext) event.getServletContext()).getOutboundInterfaces();
			outboundInterfaces = interfaces;

			for (VirtualGateway vg : settings.getCurrent().getGateways()) {
				TrunkRegistrar registrar = (vg.getStyle() == null) ? null : vg.getStyle().newRegistrar(vg);
				if (registrar == null) {
					sipLogger.info("gateway " + vg.getName() + ": no registration required (ip-auth or none)");
					continue;
				}
				InetSocketAddress outbound = resolveOutbound(vg);
				if (vg.getContactHost() != null && outbound == null) {
					sipLogger.severe("gateway " + vg.getName() + ": contactHost " + vg.getContactHost()
							+ " is not a configured SIP outbound interface — skipping registration");
					continue;
				}
				registrar.start(outbound);
				registrars.add(registrar);
			}
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

	// ============================================================ outbound bridge (phase 2a)

	/// The B2BUA is creating the outbound leg for an FSMAR‑routed outbound INVITE — rewrite it onto
	/// the trunk the Route URI named (`;vgw=<name>`). Called before the outbound INVITE is sent.
	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		try {
			SipServletRequest inbound = getIncomingRequest(outboundRequest);
			String vgwName = vgwOf(inbound != null ? inbound : outboundRequest);
			VirtualGateway vg = findGateway(vgwName);
			if (vg == null) {
				sipLogger.warning("gateway: outbound INVITE with unknown/missing vgw '" + vgwName + "' — rejecting");
				doNotProcess(outboundRequest, 404, "Unknown virtual gateway");
				return;
			}

			// The dialed number is the user part of the (copied) Request-URI.
			String number = (outboundRequest.getRequestURI() instanceof SipURI)
					? ((SipURI) outboundRequest.getRequestURI()).getUser()
					: null;

			// 1) Request-URI -> the carrier trunk.
			outboundRequest.setRequestURI(sipFactory.createURI(vg.trunkRequestUri(number)));

			// 2) From -> the trunk identity (best effort; some containers restrict system headers).
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
		} catch (Exception e) {
			sipLogger.severe("gateway: callStarted failed: " + e.getMessage());
		}
		// NOTE (follow-up): if the carrier challenges the outbound INVITE (401/407), re-send with
		// addAuthHeader (reuse RegisterDigestStyle creds). Most registered/IP-auth trunks accept
		// outbound INVITEs from the authenticated source without re-challenging.
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

	/// The virtual-gateway name FSMAR named for this call — the `vgw` param on the popped Route
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

	/// Resolve `vg`'s Contact IP to a container SIP outbound interface (for
	/// `SipSession.setOutboundInterface`), or null if none matches.
	static InetSocketAddress resolveOutbound(VirtualGateway vg) {
		List<SipURI> interfaces = outboundInterfaces;
		if (vg.getContactHost() == null || interfaces == null) {
			return null;
		}
		for (SipURI uri : interfaces) {
			if (vg.matchesInterface(uri.getHost(), uri.getPort())) {
				int port = uri.getPort() > 0 ? uri.getPort() : vg.getContactPort();
				return new InetSocketAddress(uri.getHost(), port);
			}
		}
		return null;
	}
}
