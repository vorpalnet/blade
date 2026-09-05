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
///
/// In conference mode the leg is created on the room's shared session and, on ACK, `join`ed to the
/// room's mixer instead: `offer` → 200 OK → on ACK: `join` caller↔mixer. The caller stays until they
/// hang up ([PlayerBye]).
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
			final MediaSession ms;
			final Room room;
			if (cfg.isConference()) {
				// The room's session is bound to whoever opened it; each leg is bound to its own
				// caller below, so this caller's SDP answer continues under this caller's app.
				room = Room.open(roomId(invite), () -> createMediaSession(app));
				ms = room.ms;
			} else {
				room = null;
				ms = createMediaSession(app);
			}
			PlayerServlet.Anchor anchor = new PlayerServlet.Anchor(ms);
			PlayerServlet.LIVE.put(app.getId(), anchor);

			final NetworkConnection nc = ms.createNetworkConnection(NetworkConnection.BASIC);
			anchor.nc = nc;
			if (room != null) {
				bindMediaObject(nc, app);
				anchor.room = room.id;
				room.join(app.getId());
			}
			byte[] callerSdp = invite.getRawContent();

			// The ACK continuation below is stored on the replicated call state, so it may capture
			// only serializable things — the app-session id, never the live 309 objects (those are
			// re-resolved from the node-local anchor when the ACK arrives).
			final String appId = app.getId();
			final boolean conference = (room != null);

			// Feed the caller's offer to the media server; its answer goes in our 200 OK.
			offer(nc, callerSdp, answerEvent -> {
				SipServletResponse ok = invite.createResponse(200);
				ok.setContent(answerEvent.getMediaServerSdp(), "application/sdp");

				// Start media once the dialog is confirmed (ACK).
				sendResponse(ok, ack -> {
					if (conference) {
						joinRoom(appId, invite);
					} else {
						startMedia(appId, invite);
					}
				});
			});
		} catch (MsControlException e) {
			sipLogger.severe(invite, "PlayerCallflow: media anchor failed: " + e.getMessage());
			PlayerBye.teardown(app.getId());
			invite.createResponse(500, "Media server unavailable").send();
		}
	}

	/// The room a call lands in: the dialed user. `sip:daily@example.net` and `sip:daily@other.net`
	/// are the same room; a routing tier that wants tenant-scoped rooms prefixes the user.
	static String roomId(SipServletRequest invite) {
		String user = null;
		if (invite.getTo().getURI() instanceof javax.servlet.sip.SipURI) {
			user = ((javax.servlet.sip.SipURI) invite.getTo().getURI()).getUser();
		}
		return (user == null || user.isEmpty()) ? "default" : user;
	}

	/// Put the caller in the mix. Nothing else to do: the room keeps running until the last BYE.
	private void joinRoom(String appId, SipServletRequest invite) {
		PlayerServlet.Anchor anchor = PlayerServlet.LIVE.get(appId);
		Room room = (anchor == null) ? null : Room.get(anchor.room);
		if (room == null) {
			sipLogger.warning(invite, "PlayerCallflow: no live anchor/room at ACK (hung up already?)");
			return;
		}
		try {
			join(anchor.nc, Joinable.Direction.DUPLEX, room.mixer);
			sipLogger.info(invite, "PlayerCallflow: joined room " + room.id + " (" + room.size() + " in)");
		} catch (MsControlException e) {
			sipLogger.severe(invite, "PlayerCallflow: join room " + room.id + " failed: " + e.getMessage());
			hangup(invite);
		}
	}

	/// Wire the player/recorder to the caller and begin playback.
	private void startMedia(String appId, SipServletRequest invite) throws ServletException, IOException {
		PlayerServlet.Anchor anchor = PlayerServlet.LIVE.get(appId);
		if (anchor == null) {
			sipLogger.warning(invite, "PlayerCallflow: no live anchor at ACK (hung up already?)");
			return;
		}
		MediaSession ms = anchor.ms;
		NetworkConnection nc = anchor.nc;
		try {
			MediaGroup mg = ms.createMediaGroup(MediaGroup.PLAYER_RECORDER_SIGNALDETECTOR);
			anchor.mg = mg;
			join(mg, Joinable.Direction.DUPLEX, nc); // player -> caller, caller -> recorder

			if (cfg.isRecord() && cfg.getRecordUri() != null && !cfg.getRecordUri().isEmpty()) {
				// Two tokens, and they answer different questions. (Braces without a
				// dollar sign on purpose: ${...} is the config layer's own variable
				// syntax and is expanded — to nothing, for unknown names — before the
				// application ever sees the value.)
				//
				// {id}        this call's app-session id, so concurrent calls never
				//             record into one file. Right for a file: destination.
				// {recording} the call's logical recording name. The whole configured
				//             value becomes "rec:<name>", which the deployment's
				//             RecordingDestinations turns into a real destination —
				//             for object storage, a capability minted for this
				//             recording alone. The application never names a path.
				//
				// Recording is set up inside its own try. Whatever goes wrong here —
				// no destination resolver installed, a call with no Vorpal-ID to name
				// a recording after, object storage refusing to mint — must be said
				// out loud and must not take the call down with it. Silence was the
				// actual bug: an unchecked exception escaped this method, the caller
				// caught only MsControlException, and the call carried on with no
				// recording and no complaint.
				try {
					String configured = cfg.getRecordUri();
					URI dest = configured.contains("{recording}")
							? MediaCallflow.recordingUri(invite.getApplicationSession())
							: URI.create(configured.replace("{id}", appId.replaceAll("[^A-Za-z0-9._-]", "_")));
					anchor.recording = dest;
					sipLogger.info(invite, "PlayerCallflow: recording to " + dest.getScheme() + ":...");
					record(mg, dest, rec -> {
						// recording continues until teardown flushes it
					});
				} catch (Exception e) {
					sipLogger.severe(invite, "PlayerCallflow: recording could not be started: " + e);
				}
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
