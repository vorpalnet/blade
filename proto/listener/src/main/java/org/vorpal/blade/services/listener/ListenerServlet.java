package org.vorpal.blade.services.listener;

import java.io.IOException;
import java.util.List;

import javax.media.mscontrol.MsControlFactory;

import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.InitialInvite;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.B2buaServlet;
import org.vorpal.blade.framework.v3.media.CallAnalyzer;
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.media.MediaDirection;
import org.vorpal.blade.framework.v3.media.SdpMedia;
import org.vorpal.blade.framework.v2.sdp.Sdp;

/// Hears the calls the App Router routes through it: records them, transcribes
/// them, publishes what each party says as it is said, scores the voices it is
/// told to, and hands all of it to any installed
/// [org.vorpal.blade.framework.v3.media.CallAnalyzer]. One anchor and one set
/// of ears per call, whatever reads it.
///
/// ## What it watches, and why that is enough
///
/// It sits in the application chain and calls pass through it. The App Router
/// routes **initial** requests, so a call that moves to a new party arrives here
/// as a new initial INVITE, which is exactly the moment a new conversation
/// begins. A re-INVITE is the same dialog with the same parties, so it is never
/// a boundary, though it is where hold shows up.
///
/// That is the entire detection mechanism, and it is deliberately ignorant. This
/// application does not know how a transfer was performed, which method carried
/// it, or which application performed it. Transfer is a separate application and
/// this one never touches it. The decision itself is in
/// [ConversationBoundary], off the servlet so it can be tested.
///
/// ## One call, several conversations
///
/// Each conversation is its own recording, named by
/// [org.vorpal.blade.framework.v3.media.MediaCallflow#conversationUri], carrying
/// its own classification. A reviewer authorized for one department hears the
/// conversation that belongs to it and is refused the rest of the same call,
/// with an audit record either way. The conversations are found together through
/// the `call` attribute stamped on every recording, not through the identifiers,
/// which are flat.
///
/// ## Hold
///
/// A re-INVITE that puts the call on hold pauses the recorder rather than ending
/// the recording, so the held passage never reaches the muxer and the
/// conversation stays one recording with a gap in it. The same primitive serves
/// a PCI pause. See [MediaCallflow#pauseRecording], and note that a driver which
/// does not confirm the pause records the passage anyway, which this logs rather
/// than hides.
///
/// ## Failing toward the call
///
/// Every failure here lets the call proceed. A media server that cannot be
/// reached means the call is not recorded, not that the call is refused, because
/// the alternative to an unrecorded call is a dropped one. That trade is
/// deliberate and it is the opposite of the trade the *review* side makes, where
/// an uncertain access decision refuses.
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class ListenerServlet extends B2buaServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	/// Session attribute: whether this call's recording is currently paused.
	static final String PAUSED = "org.vorpal.blade.listener.paused";

	/// Session attribute: the logical destination the current conversation is
	/// recording to, so a boundary and teardown can release it.
	static final String RECORDING = "org.vorpal.blade.listener.recording";

	/// The content type of an SDP body.
	static final String SDP = "application/sdp";

	/// The halted callflow for each call in setup, so [#callAnswered] can lift the
	/// halt. Node-local and short-lived: it exists only between the outbound
	/// INVITE and the callee answering, and a callflow is not something to put on
	/// a replicated session.
	static final java.util.Map<String, InitialInvite> HALTED = new java.util.concurrent.ConcurrentHashMap<>();

	public static SettingsManager<ListenerSettings> settings;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settings = new SettingsManager<>(event, ListenerSettings.class, new ListenerSettingsSample());
			MediaCallflow.setMsControlFactory(obtainFactory(settings.getCurrent()));
			sipLogger.info("ListenerServlet: JSR-309 MsControlFactory installed");
			sipLogger.fine("ListenerServlet.servletCreated");
		} catch (Exception e) {
			sipLogger.severe(e);
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		try {
			if (settings != null) {
				settings.unregister();
			}
		} catch (Exception e) {
			sipLogger.severe(e);
		}
	}

	/// An initial INVITE: a conversation begins, and the media server goes into
	/// the middle of it.
	///
	/// The outbound INVITE is held back, because its SDP has to be the media
	/// server's offer rather than the caller's. `doNotProcess` stops the B2BUA
	/// sending it, and `processContinue` sends it once the media server has
	/// answered the caller and produced that offer. This is the deferral the
	/// framework already provides for exactly this purpose.
	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		SipApplicationSession app = outboundRequest.getApplicationSession();
		RecordingAction action = ConversationBoundary.decide(true, directionsOf(outboundRequest), isPaused(app));
		apply(action, app, outboundRequest);
		// Who called whom, for every recording of this call: the first thing a
		// supervisor searches by. The user parts only; a URI's host is routing.
		stamp(app, "listener.from", outboundRequest.getFrom());
		stamp(app, "listener.to", outboundRequest.getTo());

		analyzersStarted(app, outboundRequest);

		ListenerSettings cfg = (settings == null) ? null : settings.getCurrent();
		if (cfg == null || (!cfg.needsAudio() && CallAnalyzer.installed().isEmpty())) {
			return;
		}

		final InitialInvite callflow = (InitialInvite) outboundRequest.getAttribute("callflow");
		if (callflow == null) {
			sipLogger.warning(outboundRequest, "ListenerServlet: no callflow to continue; passing the call through");
			return;
		}

		try {
			byte[] callerOffer = bodyOf(outboundRequest);
			doNotProcess(outboundRequest);
			HALTED.put(app.getId(), callflow);
			new ListenerAnchor().begin(app, callerOffer, calleeOffer -> {
				outboundRequest.setContent(calleeOffer, SDP);
				resume(callflow);
			});
		} catch (Exception e) {
			// The media server could not be reached. Let the call through
			// unrecorded rather than failing it: the alternative to an unrecorded
			// call is a dropped one, and that is worse for everybody.
			sipLogger.severe(outboundRequest, "ListenerServlet: could not anchor, call proceeds unheard: " + e);
			resume(callflow);
		}
	}

	/// Send the outbound INVITE, and leave the halt in place.
	///
	/// **The halt is cleared later, in [#callAnswered], and the order is the
	/// whole trick.** `doNotProcess` is read at two different moments for two
	/// different purposes:
	///
	/// 1. Just after `callStarted` returns, to decide whether to send the
	///    outbound INVITE.
	/// 2. Inside the response handler, to decide whether to pass the callee's
	///    answer back to the caller.
	///
	/// `processContinue` itself checks neither: it sends unconditionally, and the
	/// second check happens after this application's [#callAnswered] has run. So
	/// the flag stays set here, which stops the framework sending the INVITE a
	/// second time when the media callback happens to complete on this thread,
	/// and is cleared in `callAnswered`, in time for the answer to go back.
	///
	/// Both halves cost a live call to find. Clearing it here let the framework
	/// send the INVITE again and the second send threw
	/// `IllegalStateException: message is committed`. Leaving it set for the
	/// whole call meant the callee answered and the caller never heard, so the
	/// session hung until it timed out.
	private static void resume(InitialInvite callflow) throws ServletException, IOException {
		callflow.processContinue();
	}

	/// A request on a party's dialog goes to [PartyDialog]; the B2BUA's own
	/// callflows know only the two legs they linked.
	@Override
	protected org.vorpal.blade.framework.Callflow chooseCallflow(SipServletRequest request)
			throws ServletException, IOException {
		if (!request.isInitial() && request.getSession() != null
				&& request.getSession().getAttribute(ListenerAnchor.PARTY) != null) {
			return new PartyDialog();
		}
		return super.chooseCallflow(request);
	}

	/// Resolve the 309 factory from the configured driver, or the sole registered
	/// one. See [MediaCallflow#obtainFactory].
	private static MsControlFactory obtainFactory(ListenerSettings cfg) throws ServletException {
		try {
			return MediaCallflow.obtainFactory(cfg == null ? null : cfg.getDriverName(),
					cfg == null ? null : cfg.getDriverProperties(), ListenerServlet.class.getClassLoader());
		} catch (Exception e) {
			throw new ServletException("getFactory failed", e);
		}
	}

	/// A mid-dialog request. A re-INVITE carrying an offer is where hold appears;
	/// everything else here is none of this application's business.
	///
	/// ## The offer is replaced, not relayed
	///
	/// `request` is the INVITE about to go to the far party, and the framework
	/// filled it with the near party's SDP. Sent like that, the far party would
	/// learn the near party's address and the media would leave the media server
	/// for the rest of the call, which is exactly what happened before this: a
	/// held call was recorded as silence from the hold onward. So the body is
	/// replaced with the media server's own SDP for the far leg, carrying the
	/// near party's new direction, and the direction is kept so the answer can
	/// carry its mirror in [#responseEvent]. See [AnchoredSdp].
	@Override
	public void requestEvent(SipServletRequest request) throws ServletException, IOException {
		if (!"INVITE".equals(request.getMethod())) {
			return;
		}
		SipApplicationSession app = request.getApplicationSession();
		List<MediaDirection> offered = directionsOf(request);
		RecordingAction action = ConversationBoundary.decide(false, offered, isPaused(app));
		apply(action, app, request);

		ListenerAnchor.Anchor anchor = ListenerAnchor.LIVE.get(app.getId());
		if (anchor == null || offered == null || offered.isEmpty()) {
			return; // no anchor to keep, or an offerless refresh that moves nothing
		}
		MediaDirection direction = offered.get(0);
		// Which party sent this: the request is about to go to the far party,
		// so a request bound for the caller's session came from the callee.
		boolean fromCaller = anchor.callerSessionId == null
				|| !anchor.callerSessionId.equals(request.getSession().getId());
		NetworkConnection near = fromCaller ? anchor.caller : anchor.callee;
		NetworkConnection far = fromCaller ? anchor.callee : anchor.caller;

		// A party that moved its media gets a fresh leg; see ListenerAnchor.moveLeg.
		byte[] nearOffer = bodyOf(request);
		byte[] lastRemote = null;
		try {
			lastRemote = (near == null) ? null : near.getSdpPortManager().getUserAgentSessionDescription();
		} catch (Exception e) {
			sipLogger.fine(request, "ListenerServlet: the near leg's last SDP is unavailable: " + e);
		}
		if (near != null && AnchoredSdp.moved(lastRemote, nearOffer)) {
			java.util.concurrent.CompletableFuture<byte[]> answer = new java.util.concurrent.CompletableFuture<>();
			anchor.moveAnswer = answer;
			if (AnchoredSdp.newParty(lastRemote, nearOffer)) {
				// A different party on this leg: a transfer completed by
				// re-INVITE. That is a conversation boundary, the same one a new
				// initial INVITE marks, on a fresh leg. The conversation so far
				// closes now, before the swap, so its record holds only the party
				// it was with; the next begins once the new party is on the mix.
				sipLogger.info(request, "ListenerServlet: a new party on the " + (fromCaller ? "caller" : "callee")
						+ " leg (" + AnchoredSdp.origin(nearOffer) + "); conversation boundary");
				ListenerAnchor.stopRecording(app.getId());
				app.setAttribute(PAUSED, Boolean.FALSE);
				final ListenerSettings cfg = (settings == null) ? null : settings.getCurrent();
				new ListenerAnchor().moveLeg(anchor, fromCaller, nearOffer, answer,
						() -> new ListenerAnchor().startListening(app, anchor, cfg));
			} else {
				new ListenerAnchor().moveLeg(anchor, fromCaller, nearOffer, answer);
			}
		}

		anchor.reissued.incrementAndGet();
		byte[] sdp = ListenerAnchor.anchoredSdp(anchor, far, direction);
		if (sdp == null) {
			sipLogger.warning(request, "ListenerServlet: the far leg has no media server SDP; "
					+ "the re-INVITE is relayed as is and the media may leave the media server");
			return;
		}
		request.setContent(sdp, SDP);
		anchor.reinviteDirection = direction;
		anchor.reinviteFromCaller = fromCaller;
	}

	/// The callee answered. Give the caller the media server's answer, not the
	/// callee's, then bridge the legs and start recording.
	///
	/// The response to the caller is not held back. Its SDP was produced before
	/// the callee was ever called, so it is ready now, and making the caller wait
	/// on a second media round-trip would add setup delay for nothing. Applying
	/// the callee's answer and joining happens a moment later, on the media
	/// thread.
	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		SipApplicationSession app = outboundResponse.getApplicationSession();

		// Lift the halt, here and not earlier. This runs inside the callflow's
		// response handler, immediately before it decides whether to pass the
		// answer back to the caller, so clearing it now is what lets the caller
		// hear the callee. See resume().
		//
		// The halt is on the OUTBOUND request, and it has to be cleared there.
		// `outboundResponse` is a misleading name for what arrives here: it is
		// the response being built toward the CALLER, so its getRequest() is the
		// inbound INVITE, and clearing the attribute on that one changes nothing
		// the callflow ever reads. Cost a live call to see: the flag looked
		// cleared, and the caller still never heard the answer.
		InitialInvite callflow = HALTED.remove(app.getId());
		if (callflow != null) {
			callflow.setDoNotProcess(false);
			SipServletRequest outboundRequest = callflow.getOutboundRequest();
			if (outboundRequest != null) {
				outboundRequest.removeAttribute("doNotProcess");
			}
		}

		ListenerAnchor.Anchor anchor = ListenerAnchor.LIVE.get(app.getId());
		if (anchor == null) {
			return;
		}
		// This response is the one toward the caller, so its session is the
		// caller's; a later mid-dialog request bound for it came from the callee.
		anchor.callerSessionId = outboundResponse.getSession().getId();
		try {
			// The callee's answer, read BEFORE the body is swapped for the media
			// server's. Reading it afterwards fed the caller leg's own SDP to the
			// callee leg, so the callee leg sent its RTP to the caller leg's port:
			// the caller leg then saw two streams, its jitter buffer refused the
			// second SSRC, and its receive chain died with not-linked within a
			// second of the ACK. Every recording since had only the callee in it,
			// and the mix hid that. Found by the transcriber, which hears each leg
			// on its own and heard nothing from the caller.
			byte[] calleeAnswer = bodyOf(outboundResponse);
			if (anchor.answerForCaller != null) {
				outboundResponse.setContent(anchor.answerForCaller, SDP);
			}
			ListenerSettings cfg = (settings == null) ? null : settings.getCurrent();
			new ListenerAnchor().connect(app, calleeAnswer, cfg);
		} catch (Exception e) {
			sipLogger.severe(outboundResponse, "ListenerServlet: could not bridge the anchored call: " + e);
		}
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		finish(outboundRequest.getApplicationSession());
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		HALTED.remove(outboundResponse.getApplicationSession().getId());
		finish(outboundResponse.getApplicationSession());
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		finish(outboundRequest.getApplicationSession());
	}

	/// The far party answered a re-INVITE. `response` is the one about to go to
	/// the near party, filled with the far party's SDP; it is replaced with the
	/// media server's SDP for the near leg, in the direction that mirrors what
	/// the near party offered, so the near party keeps sending to the media
	/// server. A failure leaves the offer's direction pending for nothing, so it
	/// is cleared either way.
	@Override
	public void responseEvent(SipServletResponse response) throws ServletException, IOException {
		if (!"INVITE".equals(response.getMethod())) {
			return;
		}
		ListenerAnchor.Anchor anchor = ListenerAnchor.LIVE.get(response.getApplicationSession().getId());
		if (anchor == null) {
			return;
		}
		MediaDirection offered = anchor.reinviteDirection;
		if (offered == null) {
			return;
		}
		if (response.getStatus() < 200) {
			return; // provisional; the answer is still coming
		}
		anchor.reinviteDirection = null;
		java.util.concurrent.CompletableFuture<byte[]> move = anchor.moveAnswer;
		anchor.moveAnswer = null;
		if (response.getStatus() >= 300) {
			return;
		}
		NetworkConnection near = anchor.reinviteFromCaller ? anchor.caller : anchor.callee;
		byte[] sdp = null;
		if (move != null) {
			// The fresh leg's own SDP, in the mirrored direction. The media
			// server answers in milliseconds; the wait is bounded so a media
			// server that does not answer costs the party its move, not the
			// call.
			try {
				sdp = AnchoredSdp.rewrite(move.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS), offered.reverse(),
						anchor.reissued.get());
			} catch (Exception e) {
				sipLogger.warning(response, "ListenerServlet: the moved party's fresh leg did not negotiate in time ("
						+ e + "); answering with the old leg, which the party may no longer reach");
			}
		}
		if (sdp == null) {
			sdp = ListenerAnchor.anchoredSdp(anchor, near, offered.reverse());
		}
		if (sdp == null) {
			sipLogger.warning(response, "ListenerServlet: the near leg has no media server SDP; "
					+ "the answer is relayed as is and the media may leave the media server");
			return;
		}
		response.setContent(sdp, SDP);
	}

	/// Carry out what [ConversationBoundary] decided.
	///
	/// The recorder calls themselves land here once the media path is wired. The
	/// state transitions are kept whole now so that the part which needs a live
	/// call is the media plumbing and nothing else.
	private void apply(RecordingAction action, SipApplicationSession app, SipServletRequest request) {
		switch (action) {
		case START_CONVERSATION:
			// A boundary always closes the previous conversation first. Its
			// capability has to be released whether or not the next one starts,
			// or it lives until its backstop expiry.
			ListenerAnchor.stopRecording(app.getId());
			app.setAttribute(PAUSED, Boolean.FALSE);
			sipLogger.fine(request, "ListenerServlet: conversation begins");
			break;
		case PAUSE:
			app.setAttribute(PAUSED, Boolean.TRUE);
			pauseRecorder(app, true, request);
			break;
		case RESUME:
			app.setAttribute(PAUSED, Boolean.FALSE);
			pauseRecorder(app, false, request);
			break;
		case NONE:
		default:
			break;
		}
	}

	/// Pause or resume the live recorder.
	///
	/// A paused span never reaches the muxer, so the held audio is not in the
	/// file to be found later. Failing to pause is logged rather than thrown: the
	/// call is not worth dropping over it, though it does mean hold music is in
	/// the recording, which is why it is logged at warning.
	private void pauseRecorder(SipApplicationSession app, boolean pause, SipServletRequest request) {
		ListenerAnchor.Anchor anchor = ListenerAnchor.LIVE.get(app.getId());
		if (anchor == null || anchor.mg == null || anchor.record == null) {
			return;
		}
		try {
			boolean honoured = pause ? MediaCallflow.pauseRecording(anchor.mg)
					: MediaCallflow.resumeRecording(anchor.mg);
			sipLogger.fine(request, "ListenerServlet: " + (pause ? "on hold" : "off hold") + ", recording "
					+ (honoured ? (pause ? "paused" : "resumed") : "UNCHANGED (driver cannot pause)"));
		} catch (Exception e) {
			sipLogger.warning(request,
					"ListenerServlet: recorder would not " + (pause ? "pause" : "resume") + ": " + e);
		}
	}

	/// End of call: stop the recorder, then release the anchor and the recording's
	/// destination.
	private void finish(SipApplicationSession app) {
		if (app == null) {
			return;
		}
		HALTED.remove(app.getId());
		app.setAttribute(PAUSED, Boolean.FALSE);
		ListenerAnchor.release(app.getId());
		analyzersEnded(app);
	}

	/// Session attribute: the installed analyzers were told this call started,
	/// so they are told once that it ended.
	static final String ANALYZED = "org.vorpal.blade.listener.analyzed";

	/// Tell every installed analyzer the call started, with the INVITE as it
	/// arrived here rather than the copy about to leave. Once per call: a later
	/// initial INVITE on the same call is a new conversation, not a new call.
	private void analyzersStarted(SipApplicationSession app, SipServletRequest outboundRequest) {
		if (CallAnalyzer.installed().isEmpty() || Boolean.TRUE.equals(app.getAttribute(ANALYZED))) {
			return;
		}
		app.setAttribute(ANALYZED, Boolean.TRUE);
		SipServletRequest inbound = getIncomingRequest(outboundRequest);
		SipServletRequest invite = (inbound != null) ? inbound : outboundRequest;
		for (CallAnalyzer analyzer : CallAnalyzer.installed()) {
			try {
				analyzer.callStarted(app, invite);
			} catch (Throwable t) {
				sipLogger.warning(outboundRequest, "ListenerServlet: an analyzer failed on the call's start: " + t);
			}
		}
	}

	private void analyzersEnded(SipApplicationSession app) {
		if (!Boolean.TRUE.equals(app.getAttribute(ANALYZED))) {
			return;
		}
		app.removeAttribute(ANALYZED);
		for (CallAnalyzer analyzer : CallAnalyzer.installed()) {
			try {
				analyzer.callEnded(app);
			} catch (Throwable t) {
				sipLogger.warning("ListenerServlet: an analyzer failed on the call's end: " + t);
			}
		}
	}

	/// The message body as bytes, or null when it carries none.
	private static void stamp(SipApplicationSession app, String name, javax.servlet.sip.Address address) {
		if (address == null || address.getURI() == null) {
			return;
		}
		javax.servlet.sip.URI uri = address.getURI();
		String user = null;
		if (uri instanceof javax.servlet.sip.SipURI) {
			user = ((javax.servlet.sip.SipURI) uri).getUser();
		} else if (uri instanceof javax.servlet.sip.TelURL) {
			user = ((javax.servlet.sip.TelURL) uri).getPhoneNumber();
		}
		if (user != null && !user.isEmpty()) {
			app.setAttribute(name, user);
		}
	}

	static byte[] bodyOf(javax.servlet.sip.SipServletMessage message) {
		try {
			Object content = (message == null) ? null : message.getContent();
			if (content == null) {
				return null;
			}
			return (content instanceof byte[]) ? (byte[]) content
					: String.valueOf(content).getBytes("UTF-8");
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isPaused(SipApplicationSession app) {
		return app != null && Boolean.TRUE.equals(app.getAttribute(PAUSED));
	}

	/// The effective direction of each m-line in the request's offer, or null
	/// when it carries none.
	///
	/// Unparseable SDP yields null rather than an exception. A malformed offer is
	/// not a reason to drop a call, and null means "says nothing", which leaves
	/// the recording exactly as it was.
	static List<MediaDirection> directionsOf(SipServletRequest request) {
		try {
			Object content = (request == null) ? null : request.getContent();
			if (content == null) {
				return null;
			}
			String body = (content instanceof byte[]) ? new String((byte[]) content, "UTF-8")
					: String.valueOf(content);
			if (body.trim().isEmpty()) {
				return null;
			}
			return SdpMedia.captureDirections(Sdp.parse(body));
		} catch (Exception unparseable) {
			return null;
		}
	}
}
