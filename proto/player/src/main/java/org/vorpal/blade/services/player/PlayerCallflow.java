package org.vorpal.blade.services.player;

import java.io.IOException;
import java.net.URI;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.media.MediaCallflow;

/// Answer an inbound INVITE, anchor its media on the 309 media server, and play the configured prompt
/// (optionally recording the caller). The whole conversation reads top-to-bottom via the media verbs:
///
/// `offer` (SDP anchor → 200 OK) → on ACK: `join` group↔caller → optional `record` → `play` → on
/// completion, loop or hang up.
public class PlayerCallflow extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	private final PlayerSettings cfg;

	public PlayerCallflow(PlayerSettings cfg) {
		this.cfg = cfg;
	}

	@Override
	public void process(SipServletRequest invite) throws ServletException, IOException {
		final SipApplicationSession app = invite.getApplicationSession();
		try {
			final MediaSession ms = createMediaSession(app);
			PlayerServlet.LIVE.put(app.getId(), new PlayerServlet.Anchor(ms));

			final NetworkConnection nc = ms.createNetworkConnection(NetworkConnection.BASIC);
			byte[] callerSdp = invite.getRawContent();

			// Feed the caller's offer to the media server; its answer goes in our 200 OK.
			offer(nc, callerSdp, answerEvent -> {
				SipServletResponse ok = invite.createResponse(200);
				ok.setContent(answerEvent.getMediaServerSdp(), "application/sdp");

				// Start media once the dialog is confirmed (ACK).
				sendResponse(ok, ack -> startMedia(app, ms, nc, invite));
			});
		} catch (MsControlException e) {
			sipLogger.severe(invite, "PlayerCallflow: media anchor failed: " + e.getMessage());
			PlayerServlet.LIVE.remove(app.getId());
			invite.createResponse(500, "Media server unavailable").send();
		}
	}

	/// Wire the player/recorder to the caller and begin playback.
	private void startMedia(SipApplicationSession app, MediaSession ms, NetworkConnection nc,
			SipServletRequest invite) throws ServletException, IOException {
		try {
			MediaGroup mg = ms.createMediaGroup(MediaGroup.PLAYER_RECORDER_SIGNALDETECTOR);
			PlayerServlet.Anchor anchor = PlayerServlet.LIVE.get(app.getId());
			if (anchor != null) {
				anchor.mg = mg;
			}
			join(mg, Joinable.Direction.DUPLEX, nc); // player -> caller, caller -> recorder

			if (cfg.isRecord() && cfg.getRecordUri() != null && !cfg.getRecordUri().isEmpty()) {
				record(mg, URI.create(cfg.getRecordUri()), rec -> {
					// recording continues until teardown flushes the file; nothing to do here
				});
			}
			playOnce(mg, invite);
		} catch (MsControlException e) {
			sipLogger.severe(invite, "PlayerCallflow: startMedia failed: " + e.getMessage());
			hangup(invite);
		}
	}

	/// Play the configured media once; on completion, loop (music) or hang up.
	private void playOnce(MediaGroup mg, SipServletRequest invite) throws MsControlException {
		play(mg, new URI[] { URI.create(cfg.getMediaUri()) }, done -> {
			if (cfg.isLoop()) {
				playOnce(mg, invite);
			} else {
				hangup(invite);
			}
		});
	}

	/// Release the media anchor and BYE the caller.
	private void hangup(SipServletRequest invite) {
		SipApplicationSession app = invite.getApplicationSession();
		PlayerBye.teardown(app.getId());
		try {
			SipServletRequest bye = invite.getSession().createRequest("BYE");
			sendRequest(bye, resp -> {
				// caller acknowledged the hangup; nothing further
			});
		} catch (Exception e) {
			sipLogger.warning(invite, "PlayerCallflow: BYE after playback failed: " + e.getMessage());
		}
	}
}
