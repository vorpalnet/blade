package org.vorpal.blade.services.webrtc.v3;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.services.webrtc.BrowserRegistry;
import org.vorpal.blade.services.webrtc.OutboundFromBrowser;
import org.vorpal.blade.services.webrtc.SignalProtocol;

/// A browser says Ready or Not Ready, for the skills it names: [SignalProtocol#PRESENCE_PUBLISH]
/// becomes a SIP PUBLISH (RFC 3903, event package `presence`) for the socket's own address, through
/// the App Router like every request this gateway sends. Each skill is a PIDF tuple with the id
/// `skill-<name>`, open when the browser is Ready; a browser that names no skill publishes one
/// tuple for itself.
///
/// The browser also watches itself. After its first publication the gateway SUBSCRIBEs the socket
/// to its own address, and each NOTIFY goes to the page as [SignalProtocol#PRESENCE_NOTIFY]. That is
/// how the page learns what the presence server decided on its own: that a call is ringing, that the
/// agent is on one, that a call they let ring set them Not Ready. The subscription is refreshed with
/// each publication and ended when the socket closes.
///
/// Each socket is one device with one publication. The gateway keeps the publication's entity tag
/// per socket, so a change of state modifies that publication (`SIP-If-Match`) rather than adding a
/// second one, and a socket that closes withdraws it ([#withdraw]). A presence server that has
/// forgotten the tag answers `412`; the gateway then publishes afresh. The tags are node-local, as
/// the sockets are.
///
/// The page refreshes its state before [#EXPIRES] runs out; a browser that vanishes without closing
/// its socket drops out when the publication expires.
public class PresenceFromBrowser extends Callflow {
	private static final long serialVersionUID = 1L;

	/// Seconds a publication lasts.
	public static final int EXPIRES = 3600;

	/// Socket id to the entity tag of its publication.
	private static final Map<String, String> ETAGS = new ConcurrentHashMap<>();

	/// Socket id to its subscription to itself: application session id, then SIP session id.
	private static final Map<String, String[]> WATCHES = new ConcurrentHashMap<>();

	@Override
	public void process(SipServletRequest request) {
		// Nothing originates this callflow from the network; see publish().
	}

	/// Publish `open` (Ready) or `closed` (Not Ready) for `aor` from socket `socketId`, for `skills`.
	/// The result goes to every socket of `aor`: [SignalProtocol#PRESENCE_PUBLISHED] or
	/// [SignalProtocol#ERROR].
	public void publish(String socketId, String aor, boolean open, List<String> skills) throws Exception {
		send(socketId, aor, open, skills, ETAGS.get(socketId), EXPIRES);
	}

	/// The socket closed: withdraw its publication, if it made one, and end its subscription.
	public static void withdraw(String socketId, String aor) {
		String[] watch = WATCHES.remove(socketId);
		SipSession dialog = (watch == null) ? null : dialog(watch);
		if (dialog != null) {
			try {
				SipServletRequest end = dialog.createRequest("SUBSCRIBE");
				end.setHeader("Event", "presence");
				end.setExpires(0);
				new PresenceFromBrowser().sendRequest(end, response -> {
				});
			} catch (Exception e) {
				getSipLogger().fine("webrtc: could not end " + aor + "'s subscription: " + e);
			}
		}
		String etag = ETAGS.remove(socketId);
		if (etag == null || aor == null) {
			return;
		}
		try {
			new PresenceFromBrowser().send(socketId, aor, false, Collections.emptyList(), etag, 0);
		} catch (Exception e) {
			getSipLogger().fine("webrtc: could not withdraw " + aor + "'s presence: " + e);
		}
	}

	private static SipSession dialog(String[] watch) {
		SipApplicationSession app = getSipUtil().getApplicationSessionById(watch[0]);
		SipSession dialog = (app == null || !app.isValid()) ? null : app.getSipSession(watch[1]);
		return (dialog != null && dialog.isValid() && dialog.getState() == SipSession.State.CONFIRMED) ? dialog : null;
	}

	/// Subscribe socket `socketId` to `aor`, or refresh the subscription it has.
	private void watch(String socketId, String aor) throws Exception {
		String[] known = WATCHES.get(socketId);
		SipSession dialog = (known == null) ? null : dialog(known);
		SipServletRequest subscribe;
		if (dialog != null) {
			subscribe = dialog.createRequest("SUBSCRIBE");
		} else {
			SipApplicationSession app = getSipFactory().createApplicationSession();
			subscribe = getSipFactory().createRequest(app, "SUBSCRIBE", "sip:" + aor, "sip:" + aor);
			OutboundFromBrowser.assertIdentity(subscribe, aor, null);
			expectNotify(subscribe.getSession(), aor);
		}
		subscribe.setHeader("Event", "presence");
		subscribe.setHeader("Accept", "application/pidf+xml");
		subscribe.setExpires(EXPIRES);
		sendRequest(subscribe, response -> {
			if (successful(response)) {
				SipSession s = response.getSession();
				WATCHES.put(socketId, new String[] { s.getApplicationSession().getId(), s.getId() });
			} else if (!provisional(response)) {
				WATCHES.remove(socketId);
				getSipLogger().warning(response, "webrtc: " + aor + " cannot watch itself: " + response.getStatus());
			}
		});
	}

	/// Each NOTIFY on the subscription: answer it, pass the document to the page, wait for the next.
	private void expectNotify(SipSession subscription, String aor) {
		expectRequest(subscription, "NOTIFY", notify -> {
			sendResponse(notify.createResponse(200));
			byte[] raw = notify.getRawContent();
			String state = notify.getHeader("Subscription-State");
			BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.PRESENCE_NOTIFY, null, SignalProtocol.data()
					.put("state", state)
					.put("body", (raw == null) ? null : new String(raw, StandardCharsets.UTF_8))));
			if (state == null || !state.trim().toLowerCase().startsWith("terminated")) {
				expectNotify(notify.getSession(), aor);
			}
		});
	}

	private void send(String socketId, String aor, boolean open, List<String> skills, String etag, int expires)
			throws Exception {
		SipApplicationSession app = getSipFactory().createApplicationSession();
		SipServletRequest publish = getSipFactory().createRequest(app, "PUBLISH", "sip:" + aor, "sip:" + aor);
		OutboundFromBrowser.assertIdentity(publish, aor, null);
		publish.setHeader("Event", "presence");
		publish.setExpires(expires);
		if (etag != null) {
			publish.setHeader("SIP-If-Match", etag);
		}
		if (expires > 0) {
			String basic = "<status><basic>" + (open ? "open" : "closed") + "</basic></status><contact>sip:" + aor
					+ "</contact>";
			StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
					+ "<presence xmlns=\"urn:ietf:params:xml:ns:pidf\" entity=\"sip:" + aor + "\">\n");
			if (skills.isEmpty()) {
				body.append(" <tuple id=\"").append(socketId.replaceAll("[^\\w.-]", "")).append("\">").append(basic)
						.append("</tuple>\n");
			}
			for (String skill : skills) {
				body.append(" <tuple id=\"skill-").append(skill).append("\">").append(basic).append("</tuple>\n");
			}
			body.append("</presence>\n");
			publish.setContent(body.toString().getBytes(StandardCharsets.UTF_8), "application/pidf+xml");
		}
		boolean refreshed = etag != null;
		sendRequest(publish, response -> {
			if (provisional(response)) {
				return;
			}
			if (expires == 0) {
				return; // withdrawn; the socket is gone and nobody is told
			}
			if (successful(response)) {
				String tag = response.getHeader("SIP-ETag");
				if (tag != null) {
					ETAGS.put(socketId, tag);
				}
				BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.PRESENCE_PUBLISHED, null,
						SignalProtocol.data().put("status", open ? "open" : "closed")));
				watch(socketId, aor);
				return;
			}
			if (response.getStatus() == 412 && refreshed) {
				ETAGS.remove(socketId);
				new PresenceFromBrowser().send(socketId, aor, open, skills, null, expires);
				return;
			}
			BrowserRegistry.deliver(aor, SignalProtocol.event(SignalProtocol.ERROR, null, SignalProtocol.data()
					.put("reason", "presence " + response.getStatus() + " " + response.getReasonPhrase())));
		});
	}
}
