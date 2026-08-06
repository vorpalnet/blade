package org.vorpal.blade.services.webrtc;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.v3.Callflow;

/// Registers a browser with the SIP location service — the gateway speaking SIP
/// on the browser's behalf, the way an SBC registers for the endpoints behind it.
///
/// ## The Contact is the routing instruction
///
/// What this class registers is a **routable** contact: this engine's own SIP
/// address, carrying the container's `encodeURI` targeting parameters bound to a
/// long-lived per-browser application session:
///
/// ```
/// Contact: <sip:alice@172.16.32.129:5060;transport=tcp;sipappsessionid=<prefix>:<callId>:webrtc;wlsscid=…>
/// ```
///
/// That single header is the entire inbound routing story. When the registrar
/// forks an INVITE to it, the container recognizes its own parameters
/// (`SipServletRequestImpl.isInitialRequestWithEncodeURI`), hands the App Router
/// a `SipTargetedRequestInfo(ENCODED_URI, "webrtc", …)` — which the FSMAR's
/// targeted branch honors before its state machine even runs — and dispatches
/// the INVITE **into the registration's own application session** on this app.
/// No router configuration names this application; the REGISTER said everything,
/// which is how a registrar is supposed to learn where things live.
///
/// Verified against the decompiled container (OCCAS 8.1), not assumed:
///
/// - the three-part `sipappsessionid` written by
///   `SipApplicationSessionImpl.encodeURI` is what
///   `WlssSipTargetedRequestInfo.generateTargetedRequestInfo` requires — the
///   short form the container stamps on its *default* contacts is for in-dialog
///   affinity only and does not target initial requests;
/// - an app-set REGISTER Contact with a real host is left untouched by
///   `SipServletRequestImpl.addOrUpdateContact`, which rewrites only the
///   `wlsshost.bea.com` placeholder — so exactly one contact, ours, is
///   registered;
/// - `LocalTransport.isLocalServer` requires an explicit `transport` parameter
///   before it will call a URI local, so the contact always carries one.
///
/// A welcome consequence of the contact naming this engine: in a cluster, the
/// registrar's fork is *delivered to the node that holds the WebSocket*, because
/// the contact's address is that node's. The socket table is node-local; the
/// contact routes to the node.
///
/// ## One session per browser, as long-lived as the binding
///
/// The registration session is created by key (the AOR), carries
/// [BrowserSignals#BROWSER_AOR], and is what inbound targeted INVITEs land on —
/// so [InboundToBrowser] reads the AOR off the session instead of parsing it
/// out of a Request-URI that now names an engine, not a domain. A re-REGISTER
/// (page reload) finds the same session by key and produces the identical
/// contact string, which the registrar treats as a refresh of the same binding.
///
/// One call at a time per browser follows from this: concurrent inbound calls
/// for one AOR would share the session and its continuation slots. A browser
/// tab is a one-call phone, so that is the honest shape, not a limitation.
///
/// No refresh timer yet — a browser re-registers on every reconnect, and a tab
/// older than [WebrtcSettings#getRegisterExpiresSeconds] ages out of the
/// location service until it reconnects. No digest handling — the internal
/// registrar never challenges.
public class BrowserRegistration extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) {
		// Nothing originates this callflow from the network; see register().
	}

	/// Announce `aor` to the location service. Best-effort by design: the
	/// caller logs and moves on, because a browser that cannot be reached from
	/// the SIP fabric can still relay browser-to-browser.
	public void register(String aor) throws Exception {
		send(aor, WebrtcServlet.registerExpiresSeconds());
	}

	/// Withdraw `aor` — REGISTER with Expires: 0 removes exactly our contact
	/// and leaves any other binding for the address alone. The registration
	/// session is left to age out on its own: an in-progress call may still be
	/// living on it.
	public void deregister(String aor) throws Exception {
		send(aor, 0);
	}

	private void send(String aor, int expires) throws Exception {
		// By key, so registration, refresh, deregistration and every inbound
		// targeted INVITE for this browser meet on one session.
		SipApplicationSession sas = getSipFactory().createApplicationSessionByKey(aor);
		sas.setAttribute(BrowserSignals.BROWSER_AOR, aor);
		// This session IS the registration: it must outlive the REGISTER
		// transaction, because it is what the encodeURI contact targets. Left
		// at the default, the container invalidates it the instant the 200
		// lands (its only SipSession is done, it has no timers) — and three
		// seconds later a fork arrives to BEA-331604 "targeted session not
		// found" and a 503. The registrar guards its per-AOR session the same
		// way for the same reason.
		sas.setInvalidateWhenReady(false);
		// Outlive the binding by a margin so the session is still there to
		// receive a call placed just before the registration would lapse.
		sas.setExpires(Math.max(2, (expires / 60) + 2));

		// From = To = the AOR: the registrar keys its per-address session on
		// getAccountName(From), so this is what files the binding under the
		// address the browser claimed rather than under the gateway.
		Address aorAddress = getSipFactory().createAddress("<sip:" + aor + ">");
		SipServletRequest register = getSipFactory().createRequest(sas, "REGISTER", aorAddress, aorAddress);
		register.setRequestURI(getSipFactory().createURI(registrarUri(aor)));
		register.setAddressHeader("Contact", getSipFactory().createAddress(contactUri(sas, aor)));
		register.setExpires(expires);

		sendRequest(register, response -> {
			if (provisional(response)) {
				return;
			}
			String verb = (expires == 0) ? "deregister" : "register";
			if (successful(response)) {
				sipLogger.info("webrtc: " + verb + " " + aor + " -> " + response.getStatus()
						+ " (contact " + response.getRequest().getHeader("Contact") + ", expires " + expires + "s)");
			} else {
				// The browser stays connected either way; this only costs
				// reachability from the SIP side, which is worth a loud line.
				sipLogger.warning("webrtc: " + verb + " " + aor + " failed: " + response.getStatus()
						+ " " + response.getReasonPhrase());
			}
		});
	}

	/// The routable contact: this engine's SIP interface, the browser's user
	/// part for legibility, and the session's `encodeURI` targeting parameters
	/// — which are the part that makes an INVITE sent here arrive back in this
	/// application with no router configuration involved.
	private SipURI contactUri(SipApplicationSession sas, String aor) {
		SipURI iface = WebrtcServlet.outboundInterface();
		if (iface == null) {
			// No interface list means we cannot mint a routable address; the
			// caller's continuation will log the failure loudly.
			throw new IllegalStateException("no SIP outbound interface available to build a contact");
		}
		SipURI contact = getSipFactory().createSipURI(userOf(aor), iface.getHost());
		if (iface.getPort() > 0) {
			contact.setPort(iface.getPort());
		}
		// isLocalServer refuses a URI with no transport parameter, and the
		// whole design rides on the fork's Request-URI being judged local.
		String transport = iface.getTransportParam();
		contact.setTransportParam((transport == null || transport.isEmpty()) ? "tcp" : transport);
		sas.encodeURI(contact);
		return contact;
	}

	// ---- the URI scheme, container-free for tests ---------------------------------------------

	/// Where the REGISTER is aimed: the AOR's domain, e.g. `sip:vorpal.net`.
	/// The App Router, not DNS, decides where it lands.
	static String registrarUri(String aor) {
		return "sip:" + hostOf(aor);
	}

	/// The user part of `user@host`, or the whole string when there is no `@`.
	static String userOf(String aor) {
		int at = aor.lastIndexOf('@');
		return (at >= 0) ? aor.substring(0, at) : aor;
	}

	/// The host part of `user@host`. Addresses are validated upstream (the
	/// phone's AddressPolicy, the gateway's token claim), so a missing `@` is
	/// answered with the whole string rather than an exception.
	static String hostOf(String aor) {
		int at = aor.lastIndexOf('@');
		return (at >= 0) ? aor.substring(at + 1) : aor;
	}
}
