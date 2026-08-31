package org.vorpal.blade.services.player;

import java.util.concurrent.ConcurrentHashMap;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable;
import javax.media.mscontrol.mixer.MediaMixer;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;

import org.vorpal.blade.framework.v3.media.MediaCallflow;

/// Media-server loss recovery: the media server under a room died (the framework's
/// [MediaCallflow.MediaLostListener] fired), its pipeline with it, and every phone in the room is
/// now streaming at a dead address. The only way back is forward: a fresh media session — the
/// driver's placement picks a surviving node — a fresh mixer, and for **each member** a fresh leg
/// whose offer rides a **re-INVITE**; the 200 OK's answer completes it and the leg joins the new
/// mix. Callers hear a gap of one re-INVITE round trip.
///
/// One rehome runs per room however many legs report the loss (they all shared one media session,
/// so they all fire at once) — the first reporter wins the guard and carries the whole room.
/// Play-mode calls (no room) are not rehomed yet: logged, and the caller's next action ends the
/// call cleanly. This class extends [MediaCallflow] for its verbs; it is never dispatched a SIP
/// request of its own.
public class PlayerRehome extends MediaCallflow {
	private static final long serialVersionUID = 1L;

	/// Rooms with a rehome in flight (room id): the many-legs-one-loss guard.
	private static final ConcurrentHashMap.KeySetView<String, Boolean> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	@Override
	public void process(SipServletRequest request) {
		// never dispatched
	}

	/// Entry point from the servlet's registered listener.
	static void mediaLost(String appId, String msUri) {
		PlayerServlet.Anchor anchor = PlayerServlet.LIVE.get(appId);
		if (anchor == null) {
			return; // call already gone
		}
		if (anchor.room == null) {
			sipLogger.warning("PlayerRehome: media server lost under play-mode call " + appId
					+ " (" + msUri + ") — not rehomed (room calls only today)");
			return;
		}
		Room room = Room.get(anchor.room);
		if (room == null || !IN_FLIGHT.add(room.id)) {
			return; // no room, or another leg's report is already carrying it
		}
		try {
			sipLogger.warning("PlayerRehome: media server lost under room " + room.id + " ("
					+ room.size() + " in) — rehoming");
			// A dying node can still ACCEPT a connect for a moment after it stopped doing work,
			// so the first attempt may land the fresh session right back on the corpse. By the
			// next attempt that node has refused a connect and left the placement candidates.
			PlayerRehome rehome = new PlayerRehome();
			for (int attempt = 1; attempt <= 3; attempt++) {
				Room current = Room.get(room.id);
				if (current == null || rehome.rehomeRoom(current) > 0) {
					return;
				}
				sipLogger.warning("PlayerRehome: attempt " + attempt + " for room " + room.id
						+ " rehomed nobody — retrying");
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			sipLogger.severe("PlayerRehome: room " + room.id + " NOT rehomed after 3 attempts");
		} finally {
			IN_FLIGHT.remove(room.id);
		}
	}

	/// Re-anchor the room on a surviving node and re-INVITE every member. Returns how many
	/// members were carried over (their re-INVITE is in flight); 0 = the attempt found no
	/// usable node and should be retried.
	private int rehomeRoom(Room dead) {
		Room fresh;
		try {
			// The fresh session is bound to the first member whose SAS still resolves.
			SipApplicationSession owner = firstLiveApp(dead);
			if (owner == null) {
				return -1; // everyone hung up; nothing to carry
			}
			MediaSession ms = createMediaSession(owner);
			fresh = Room.replace(dead, ms, ms.createMediaMixer(MediaMixer.AUDIO));
		} catch (MsControlException e) {
			sipLogger.severe("PlayerRehome: no usable media node for room " + dead.id + ": "
					+ causeChain(e));
			return 0;
		}
		try {
			dead.ms.release(); // best effort; its server is gone
		} catch (Exception ignore) {
			// expected
		}
		MediaCallflow.forget(fresh.ownerAppId);

		int carried = 0;
		for (String memberId : fresh.members()) {
			try {
				rehomeMember(fresh, memberId);
				carried++;
			} catch (Exception e) {
				sipLogger.severe("PlayerRehome: member " + memberId + " of room " + fresh.id
						+ " not rehomed: " + causeChain(e));
			}
		}
		if (carried == 0) {
			// The fresh session itself sits on a bad node (create worked, first use failed).
			try {
				fresh.ms.release();
			} catch (Exception ignore) {
				// expected
			}
		}
		return carried;
	}

	/// The exception and its causes, messages only — "generateSdpOffer failed" alone says
	/// nothing; the cause underneath names the node that refused.
	private static String causeChain(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = t; c != null && sb.length() < 400; c = c.getCause()) {
			if (sb.length() > 0) {
				sb.append(" <- ");
			}
			sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
		}
		return sb.toString();
	}

	/// One member: fresh leg on the fresh session, media-server-first offer, re-INVITE, answer,
	/// join. Runs under the member's SAS lock (we arrive on the loss-report thread).
	///
	/// SERIALIZATION RULE (this bit us three times now): every continuation below is stored on
	/// the replicated call state, so it may capture only Strings and `this` (a [MediaCallflow],
	/// serializable) — never a live 309 object, a Room, or the enclosing [WlssAction]. Live
	/// objects are re-resolved from the node-local registries when the continuation fires. That
	/// is also why the continuations are built HERE, in methods of this class, and not inside
	/// the anonymous action: a lambda born there drags the non-serializable `$1` into the store.
	private void rehomeMember(final Room room, final String appId) throws Exception {
		final SipApplicationSession app = getSipUtil().getApplicationSessionById(appId);
		if (app == null) {
			room.leave(appId);
			return;
		}
		final PlayerRehome self = this;
		((WlssSipApplicationSession) app).doAction(new WlssAction() {
			@Override
			public Object run() throws Exception {
				return self.offerMember(room, appId, app);
			}
		});
	}

	/// Under the SAS lock: fresh leg + media-server-first offer, continuing into [#reinviteMember].
	Object offerMember(Room room, final String appId, SipApplicationSession app) throws Exception {
		PlayerServlet.Anchor anchor = PlayerServlet.LIVE.get(appId);
		if (anchor == null || dialogOf(app) == null) {
			room.leave(appId);
			return null;
		}
		NetworkConnection nc = room.ms.createNetworkConnection(NetworkConnection.BASIC);
		bindMediaObject(nc, app);
		anchor.nc = nc;
		final String roomId = room.id;

		generateOffer(nc, offerEvent -> reinviteMember(appId, roomId,
				offerEvent.getMediaServerSdp()));
		return null;
	}

	/// The offer is ready: send the re-INVITE. Captures nothing live — the response continuation
	/// re-resolves everything by id when the 200 arrives.
	private void reinviteMember(final String appId, final String roomId, byte[] offerSdp)
			throws Exception {
		SipApplicationSession app = getSipUtil().getApplicationSessionById(appId);
		SipSession dialog = (app == null) ? null : dialogOf(app);
		if (dialog == null) {
			return;
		}
		SipServletRequest reinvite = dialog.createRequest("INVITE");
		reinvite.setContent(offerSdp, "application/sdp");
		sendRequest(reinvite, resp -> {
			if (resp.getStatus() == 200) {
				resp.createAck().send();
				answerMember(appId, roomId, resp.getRawContent());
			} else if (resp.getStatus() >= 300) {
				sipLogger.warning(resp, "PlayerRehome: re-INVITE rejected (" + resp.getStatus()
						+ ") — member stays lost");
			}
		});
	}

	/// The 200's answer: complete the leg and put it in the mix. Everything is re-resolved.
	private void answerMember(final String appId, final String roomId, byte[] answerSdp)
			throws Exception {
		Room room = Room.get(roomId);
		PlayerServlet.Anchor anchor = PlayerServlet.LIVE.get(appId);
		if (room == null || anchor == null || anchor.nc == null) {
			return;
		}
		processAnswer(anchor.nc, answerSdp, done -> {
			Room r = Room.get(roomId);
			PlayerServlet.Anchor a = PlayerServlet.LIVE.get(appId);
			if (r != null && a != null && a.nc != null) {
				join(a.nc, Joinable.Direction.DUPLEX, r.mixer);
				sipLogger.info("PlayerRehome: member " + appId + " rejoined room " + roomId);
			}
		});
	}

	/// The confirmed dialog of this call's app session, or null.
	private static SipSession dialogOf(SipApplicationSession app) {
		java.util.Iterator<?> it = app.getSessions("SIP");
		while (it.hasNext()) {
			SipSession s = (SipSession) it.next();
			if (s.getState() == SipSession.State.CONFIRMED) {
				return s;
			}
		}
		return null;
	}

	private static SipApplicationSession firstLiveApp(Room room) {
		for (String id : room.members()) {
			SipApplicationSession app = getSipUtil().getApplicationSessionById(id);
			if (app != null) {
				return app;
			}
		}
		return null;
	}
}
