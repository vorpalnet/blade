package org.vorpal.blade.services.webrtc;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
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
/// forks an INVITE to it, the container recognizes its own targeting parameters,
/// hands the App Router a targeted request naming this application — which the
/// FSMAR's targeted branch honors before its state machine even runs — and
/// dispatches the INVITE **into the registration's own application session** on
/// this app. No router configuration names this application; the REGISTER said
/// everything, which is how a registrar is supposed to learn where things live.
///
/// Three container behaviours this rides on. None is guaranteed by the
/// specification, so treat them as constraints rather than assumptions:
///
/// - **The full targeting parameters are required.** The short form the container
///   stamps on its own default contacts gives in-dialog affinity only; it does not
///   target an initial request. The contact must carry what
///   [SipApplicationSession#encodeURI] writes.
/// - **An app-set Contact with a real host is left alone.** The container rewrites
///   only its own placeholder host, so exactly one contact — ours — is registered.
/// - **The transport parameter is not optional.** Without an explicit
///   `;transport=` the container will not judge the fork's Request-URI local, and
///   the whole design depends on it doing so.
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
/// ## Refreshing
///
/// A binding lapses, so it has to be renewed. A timer armed on the registration session
/// re-REGISTERs shortly before expiry, and because the session is the same one by key, a refresh,
/// a page reload and an inbound call all still meet in one place.
///
/// Three things about the timer are deliberate:
///
/// - **It is armed in the `2xx` callback, not at startup.** A timer created during servlet
///   initialization does not fire on this container; one armed inside a live request context does.
///   `services/gateway/RegisterCallflow` learned this the hard way and says so.
/// - **It refreshes at the *granted* expiry, not the requested one.** A registrar is free to shorten
///   a binding, and refreshing on the value we asked for would let it lapse before the timer came
///   round.
/// - **It stops when the socket does.** If the browser is no longer connected to this node the timer
///   cancels itself rather than re-asserting a contact that names an engine which can no longer
///   deliver. Letting the binding lapse is the honest outcome; a browser that reconnects registers
///   again from wherever it lands.
///
/// No digest handling — the internal registrar never challenges.
public class BrowserRegistration extends Callflow {
	private static final long serialVersionUID = 1L;

	/// How long before expiry to refresh. Enough to survive a slow round trip without making the
	/// refresh rate meaningfully higher than the binding requires.
	private static final long REFRESH_MARGIN_SECONDS = 30;

	/// [SipApplicationSession] attribute (String): the refresh timer's id. It lives on the session
	/// rather than in a field because this callflow is created fresh for every register — there is
	/// no long-lived object to hold it.
	private static final String REFRESH_TIMER = "org.vorpal.blade.webrtc.refreshTimer";

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
		// receive a call placed just before the registration would lapse. This
		// also has to outlive the refresh timer that lives on it: the timer
		// fires at expires-30s while the session runs to expires+2min, and each
		// refresh re-runs this line, so the session is renewed for as long as
		// refreshing continues and is reaped once it stops.
		sas.setExpires(Math.max(2, (expires / 60) + 2));

		if (expires == 0) {
			// Deregistering. Stop refreshing before the binding goes, or the timer would put it
			// straight back.
			stopRefresh(sas);
		}

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
				if (expires > 0) {
					armRefresh(aor, grantedExpires(response, expires));
				}
			} else {
				// The browser stays connected either way; this only costs
				// reachability from the SIP side, which is worth a loud line.
				sipLogger.warning("webrtc: " + verb + " " + aor + " failed: " + response.getStatus()
						+ " " + response.getReasonPhrase());
			}
		});
	}

	// ---- refresh ------------------------------------------------------------------------------

	/// Start refreshing this binding, once. A page reload re-registers on the same by-key session,
	/// so without the guard every reload would leave another timer running against it.
	private void armRefresh(String aor, int expires) {
		SipApplicationSession sas = getSipFactory().createApplicationSessionByKey(aor);
		if (sas.getAttribute(REFRESH_TIMER) != null) {
			return;
		}
		long periodMs = Math.max(30, expires - REFRESH_MARGIN_SECONDS) * 1000L;
		String timerId = startTimer(sas, periodMs, periodMs, false, false, timer -> onRefresh(aor, timer));
		sas.setAttribute(REFRESH_TIMER, timerId);
		sipLogger.info("webrtc: refreshing " + aor + " every " + (periodMs / 1000) + "s");
	}

	/// Renew the binding, or give up on it.
	private void onRefresh(String aor, ServletTimer timer) throws Exception {
		SipApplicationSession sas = timer.getApplicationSession();
		if (sas == null || !sas.isValid()) {
			return;
		}
		if (!BrowserRegistry.isLocal(aor)) {
			// No socket here: either the browser disconnected without a clean close, or this
			// session failed over to a node that never held it. Either way the registered contact
			// names an engine that cannot deliver, and re-asserting it would keep a dead address
			// alive in the location service. Let the binding lapse — a browser that comes back
			// registers again from wherever it lands.
			stopRefresh(sas);
			sipLogger.info("webrtc: stopped refreshing " + aor + "; no socket on this node");
			return;
		}
		send(aor, WebrtcServlet.registerExpiresSeconds());
	}

	/// Cancel the refresh timer if one is armed. Safe to call when none is.
	private static void stopRefresh(SipApplicationSession sas) {
		String timerId = (String) sas.getAttribute(REFRESH_TIMER);
		if (timerId != null) {
			// By id, never `stopTimers` — this session is shared with whatever call the browser
			// happens to be on, and its timers are not ours to cancel.
			stopTimer(sas, timerId);
			sas.removeAttribute(REFRESH_TIMER);
		}
	}

	/// What the registrar actually granted, which it is free to shorten. Refreshing on the value we
	/// asked for would let a shortened binding lapse before the timer came round.
	private static int grantedExpires(SipServletResponse response, int requested) {
		int granted = response.getExpires();
		return (granted > 0) ? granted : requested;
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
