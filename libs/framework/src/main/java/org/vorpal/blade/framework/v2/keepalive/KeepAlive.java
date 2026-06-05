package org.vorpal.blade.framework.v2.keepalive;

import java.util.List;

import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.framework.v2.config.KeepAliveParameters;
import org.vorpal.blade.framework.v2.config.SessionParameters;

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw.
@startuml doc-files/keepalive_reinvite.png
title Keep Alive ReINVITE: Container refreshes RTP streams
hide footbox
participant Alice as alice
participant KeepAlive as blade
participant Bob as bob

alice     <-->      bob : RTP
alice <-  blade         : INVITE
alice --> blade         : 200 OK (SDP)
          blade ->  bob : INVITE (SDP)            
          blade <-- bob : 200 OK (SDP) 
alice <-  blade         : ACK (SDP)

@enduml
*/

/// Implements SIP session keep-alive by refreshing both call legs.
///
/// Two refresh styles, chosen per cycle by [#handle]:
///
/// - **UPDATE** (RFC 3311) — a lightweight, bodiless UPDATE on each leg; no
///   SDP renegotiation. Used only when the config file's
///   `session.keepAlive.style` is `UPDATE` **and** both endpoints have
///   advertised `Allow: UPDATE` (tracked passively by AsyncSipServlet in the
///   [Callflow#ALLOW_UPDATE][org.vorpal.blade.framework.v2.callflow.Callflow#ALLOW_UPDATE]
///   session attribute).
/// - **re-INVITE** — INVITE to both call legs with full SDP exchange. Used
///   for `style: REINVITE`, whenever either endpoint's UPDATE support is
///   unknown or absent, and as the fallback when an UPDATE refresh fails.
///
/// @see SessionKeepAlive.Callback
public class KeepAlive extends ClientCallflow implements SessionKeepAlive.Callback {

	private static final long serialVersionUID = 1L;

	/// Handles the keep-alive callback by refreshing both call legs, using
	/// the UPDATE style when configured and supported by both endpoints, the
	/// re-INVITE style otherwise.
	///
	/// @param sipSession the SIP session that triggered the keep-alive
	@Override
	public void handle(SipSession sipSession) {
		// Defensive null check for sipSession parameter
		if (sipSession == null) {
			return;
		}

		SipSession linkedSession = getLinkedSession(sipSession);
		if (linkedSession == null) {
			return;
		}

		if (updateStyleConfigured() && supportsUpdate(sipSession) && supportsUpdate(linkedSession)) {
			refreshWithUpdate(sipSession, linkedSession);
		} else {
			refreshWithReinvite(sipSession, linkedSession);
		}
	}

	/// True when the config file's `session.keepAlive.style` is UPDATE. Read
	/// at refresh time, so a config republish changes the style on a live
	/// call's next refresh cycle.
	static boolean updateStyleConfigured() {
		SessionParameters params = getSessionParameters();
		KeepAliveParameters kap = (params != null) ? params.getKeepAlive() : null;
		return kap != null && KeepAliveParameters.KeepAlive.UPDATE.equals(kap.getStyle());
	}

	/// True when this session's endpoint has advertised `Allow: UPDATE`
	/// (recorded by AsyncSipServlet in the ALLOW_UPDATE attribute). An absent
	/// attribute means unknown, which counts as unsupported.
	static boolean supportsUpdate(SipSession sipSession) {
		return sipSession.isValid() && Boolean.TRUE.equals((Boolean) sipSession.getAttribute(ALLOW_UPDATE));
	}

	/// Parse Allow header values for the UPDATE method.
	///
	/// @param allowHeaderValues the message's Allow header values (may be
	/// null, and entries may be null — the testing dummies and absent
	/// headers produce both)
	/// @return TRUE/FALSE when at least one non-empty Allow header was seen,
	/// or null when the message carried no Allow header (unknown — the
	/// caller should leave any previously recorded answer in place)
	public static Boolean allowsUpdate(List<String> allowHeaderValues) {
		if (allowHeaderValues == null) {
			return null;
		}

		boolean sawAllow = false;
		for (String allow : allowHeaderValues) {
			if (allow == null || allow.isEmpty()) {
				continue;
			}
			sawAllow = true;
			for (String token : allow.split(",")) {
				if (token.trim().equalsIgnoreCase(UPDATE)) {
					return Boolean.TRUE;
				}
			}
		}

		return sawAllow ? Boolean.FALSE : null;
	}

	/// Refresh both legs with bodiless UPDATEs, sequentially — the linked leg
	/// is refreshed only after this leg's UPDATE succeeds, so at most one
	/// fallback can fire. Any non-2xx final response falls back to the
	/// re-INVITE refresh for this cycle (which refreshes both legs, so a
	/// half-refreshed pair self-corrects); a 405/501 additionally marks the
	/// failing leg as not supporting UPDATE so future cycles go straight to
	/// re-INVITE. Exceptions inside sendRequest surface here as a dummy 500
	/// response, so the failure path covers those too.
	private void refreshWithUpdate(SipSession sipSession, SipSession linkedSession) {
		try {
			sendRequest(sipSession.createRequest(UPDATE), (response) -> {
				if (provisional(response)) {
					return;
				}
				if (!successful(response)) {
					noteUpdateFailure(response);
					refreshWithReinvite(sipSession, linkedSession);
					return;
				}
				sendRequest(linkedSession.createRequest(UPDATE), (linkedResponse) -> {
					if (provisional(linkedResponse)) {
						return;
					}
					if (!successful(linkedResponse)) {
						noteUpdateFailure(linkedResponse);
						refreshWithReinvite(sipSession, linkedSession);
					}
				});
			});
		} catch (Exception ex) {
			sipLogger.logStackTrace(sipSession, ex);
		}
	}

	/// Log an UPDATE refresh failure; on 405 Method Not Allowed or 501 Not
	/// Implemented, record that this leg's endpoint does not support UPDATE.
	private static void noteUpdateFailure(SipServletResponse response) {
		sipLogger.warning(response, "KeepAlive - UPDATE refresh failed with " + response.getStatus()
				+ ", falling back to re-INVITE");

		int status = response.getStatus();
		if ((status == 405 || status == 501) && response.getSession().isValid()) {
			response.getSession().setAttribute(ALLOW_UPDATE, Boolean.FALSE);
		}
	}

	/// Refresh both legs with a re-INVITE and full SDP exchange — the
	/// original keep-alive behavior, used for REINVITE style and as the
	/// UPDATE fallback.
	private void refreshWithReinvite(SipSession sipSession, SipSession linkedSession) {
		try {
			SipServletRequest invite = sipSession.createRequest(INVITE);
			sendRequest(invite, (response) -> {
				if (successful(response)) {
					SipServletRequest linkedInvite = copyContent(response, linkedSession.createRequest(INVITE));
					sendRequest(linkedInvite, (linkedResponse) -> {
						if (successful(linkedResponse)) {
							SipServletRequest aliceAck = copyContent(linkedResponse, response.createAck());
							sendRequest(aliceAck);
							sendRequest(linkedResponse.createAck());
						}
					});
				}
			});
		} catch (Exception ex) {
			sipLogger.logStackTrace(sipSession, ex);
		}
	}
}
