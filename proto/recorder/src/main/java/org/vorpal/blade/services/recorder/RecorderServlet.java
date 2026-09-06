package org.vorpal.blade.services.recorder;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

import javax.media.mscontrol.MsControlFactory;
import javax.media.mscontrol.spi.Driver;

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
import org.vorpal.blade.framework.v3.media.MediaCallflow;
import org.vorpal.blade.framework.v3.media.MediaDirection;
import org.vorpal.blade.framework.v3.media.SdpMedia;
import org.vorpal.blade.framework.v2.sdp.Sdp;

/// Records the calls the App Router routes through it.
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
/// a PCI pause. See [org.vorpal.blade.framework.v3.media.PausableRecorder], and
/// note that a driver which cannot pause records the passage anyway, which this
/// logs rather than hides.
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
public class RecorderServlet extends B2buaServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	/// Session attribute: whether this call's recording is currently paused.
	static final String PAUSED = "org.vorpal.blade.recorder.paused";

	/// Session attribute: the logical destination the current conversation is
	/// recording to, so a boundary and teardown can release it.
	static final String RECORDING = "org.vorpal.blade.recorder.recording";

	/// The content type of an SDP body.
	static final String SDP = "application/sdp";

	/// The halted callflow for each call in setup, so [#callAnswered] can lift the
	/// halt. Node-local and short-lived: it exists only between the outbound
	/// INVITE and the callee answering, and a callflow is not something to put on
	/// a replicated session.
	static final java.util.Map<String, InitialInvite> HALTED = new java.util.concurrent.ConcurrentHashMap<>();

	public static SettingsManager<RecorderSettings> settings;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settings = new SettingsManager<>(event, RecorderSettings.class, new RecorderSettingsSample());
			MediaCallflow.setMsControlFactory(obtainFactory(settings.getCurrent()));
			sipLogger.info("RecorderServlet: JSR-309 MsControlFactory installed");
			sipLogger.fine("RecorderServlet.servletCreated");
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

		RecorderSettings cfg = (settings == null) ? null : settings.getCurrent();
		if (cfg == null || !cfg.isRecord()) {
			return;
		}

		final InitialInvite callflow = (InitialInvite) outboundRequest.getAttribute("callflow");
		if (callflow == null) {
			sipLogger.warning(outboundRequest, "RecorderServlet: no callflow to continue; passing the call through");
			return;
		}

		try {
			byte[] callerOffer = bodyOf(outboundRequest);
			doNotProcess(outboundRequest);
			HALTED.put(app.getId(), callflow);
			new RecorderAnchor().begin(app, callerOffer, calleeOffer -> {
				outboundRequest.setContent(calleeOffer, SDP);
				resume(callflow);
			});
		} catch (Exception e) {
			// The media server could not be reached. Let the call through
			// unrecorded rather than failing it: the alternative to an unrecorded
			// call is a dropped one, and that is worse for everybody.
			sipLogger.severe(outboundRequest, "RecorderServlet: could not anchor, call proceeds unrecorded: " + e);
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

	/// Resolve the 309 factory from the configured driver, or the sole registered
	/// one. Discovery goes through ServiceLoader rather than
	/// `javax.media.mscontrol.spi.DriverManager`, which finds drivers through a
	/// mechanism Java 9 removed: touching it throws, and from a loadOnStartup
	/// servlet that fails the whole deployment.
	private static MsControlFactory obtainFactory(RecorderSettings cfg) throws ServletException {
		Properties props = new Properties();
		if (cfg != null && cfg.getDriverProperties() != null) {
			props.putAll(cfg.getDriverProperties());
		}
		try {
			String name = (cfg == null) ? null : cfg.getDriverName();
			Driver fallback = null;
			for (Driver driver : ServiceLoader.load(Driver.class, RecorderServlet.class.getClassLoader())) {
				if (name != null && !name.isEmpty()) {
					if (name.equals(driver.getName())) {
						return driver.getFactory(props);
					}
				} else if (fallback == null) {
					fallback = driver;
				}
			}
			if (name != null && !name.isEmpty()) {
				throw new ServletException("no JSR-309 driver named '" + name + "' is registered");
			}
			if (fallback == null) {
				throw new ServletException("no JSR-309 driver is registered");
			}
			return fallback.getFactory(props);
		} catch (ServletException e) {
			throw e;
		} catch (Exception e) {
			throw new ServletException("getFactory failed", e);
		}
	}

	/// A mid-dialog request. A re-INVITE carrying an offer is where hold appears;
	/// everything else here is none of this application's business.
	@Override
	public void requestEvent(SipServletRequest request) throws ServletException, IOException {
		if (!"INVITE".equals(request.getMethod())) {
			return;
		}
		SipApplicationSession app = request.getApplicationSession();
		RecordingAction action = ConversationBoundary.decide(false, directionsOf(request), isPaused(app));
		apply(action, app, request);
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

		RecorderAnchor.Anchor anchor = RecorderAnchor.LIVE.get(app.getId());
		if (anchor == null) {
			return;
		}
		try {
			if (anchor.answerForCaller != null) {
				outboundResponse.setContent(anchor.answerForCaller, SDP);
			}
			RecorderSettings cfg = (settings == null) ? null : settings.getCurrent();
			new RecorderAnchor().connect(app, bodyOf(outboundResponse), cfg);
		} catch (Exception e) {
			sipLogger.severe(outboundResponse, "RecorderServlet: could not bridge the anchored call: " + e);
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

	@Override
	public void responseEvent(SipServletResponse response) throws ServletException, IOException {
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
			RecorderAnchor.stopRecording(app.getId());
			app.setAttribute(PAUSED, Boolean.FALSE);
			sipLogger.fine(request, "RecorderServlet: conversation begins");
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
		RecorderAnchor.Anchor anchor = RecorderAnchor.LIVE.get(app.getId());
		if (anchor == null || anchor.mg == null || anchor.recording == null) {
			return;
		}
		try {
			boolean honoured = pause ? MediaCallflow.pauseRecording(anchor.mg)
					: MediaCallflow.resumeRecording(anchor.mg);
			sipLogger.fine(request, "RecorderServlet: " + (pause ? "on hold" : "off hold") + ", recording "
					+ (honoured ? (pause ? "paused" : "resumed") : "UNCHANGED (driver cannot pause)"));
		} catch (Exception e) {
			sipLogger.warning(request,
					"RecorderServlet: recorder would not " + (pause ? "pause" : "resume") + ": " + e);
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
		RecorderAnchor.release(app.getId());
	}

	/// The message body as bytes, or null when it carries none.
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
