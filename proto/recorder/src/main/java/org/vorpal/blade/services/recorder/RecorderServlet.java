package org.vorpal.blade.services.recorder;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.annotation.SipApplication;
import javax.servlet.sip.annotation.SipListener;
import javax.servlet.sip.annotation.SipServlet;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.B2buaServlet;
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
/// ## Status
///
/// **The media path is not wired yet.** Recording requires the call's media to
/// be anchored on the media server, and anchoring a call that passes through a
/// B2BUA means answering the caller's offer from the media server and offering
/// the media server toward the callee, on both legs. That SDP interception is
/// the remaining work and it cannot be proven without a live call, so it is
/// absent rather than written blind. What is complete and tested is the part
/// that decides *when* to start, pause, resume and stop.
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

	public static SettingsManager<RecorderSettings> settings;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settings = new SettingsManager<>(event, RecorderSettings.class, new RecorderSettingsSample());
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

	/// An initial INVITE: a conversation begins.
	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		RecordingAction action = ConversationBoundary.decide(true, directionsOf(outboundRequest),
				isPaused(outboundRequest.getApplicationSession()));
		apply(action, outboundRequest.getApplicationSession(), outboundRequest);
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

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
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
			finish(app);
			app.setAttribute(PAUSED, Boolean.FALSE);
			sipLogger.fine(request, "RecorderServlet: conversation begins");
			break;
		case PAUSE:
			app.setAttribute(PAUSED, Boolean.TRUE);
			sipLogger.fine(request, "RecorderServlet: on hold, recording paused");
			break;
		case RESUME:
			app.setAttribute(PAUSED, Boolean.FALSE);
			sipLogger.fine(request, "RecorderServlet: off hold, recording resumed");
			break;
		case NONE:
		default:
			break;
		}
	}

	/// Stop and release the conversation currently recording, if any.
	private void finish(SipApplicationSession app) {
		if (app == null || app.getAttribute(RECORDING) == null) {
			return;
		}
		app.removeAttribute(RECORDING);
		app.setAttribute(PAUSED, Boolean.FALSE);
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
