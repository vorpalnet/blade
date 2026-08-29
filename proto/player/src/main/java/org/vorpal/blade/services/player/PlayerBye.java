package org.vorpal.blade.services.player;

import java.io.IOException;

import javax.media.mscontrol.MediaSession;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v3.media.MediaCallflow;

/// Handle a caller-initiated BYE/CANCEL: release the call's media anchor on the media server, then
/// `200 OK` the request. Teardown is idempotent, so it is safe whether the caller hangs up or the app
/// hung up first (after playback).
public class PlayerBye extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		teardown(request.getApplicationSession().getId());
		request.createResponse(200).send();
	}

	/// Release the live media anchor for `appId` (stop the group so any recording is flushed, then
	/// release the session/pipeline). No-op if already torn down.
	static void teardown(String appId) {
		PlayerServlet.Anchor anchor = PlayerServlet.LIVE.remove(appId);
		if (anchor != null && anchor.room != null) {
			// Conference leg: leave the mix and release the leg; the room's session outlives the
			// caller and is released by the last one out.
			Room room = Room.get(anchor.room);
			try {
				if (room != null) {
					anchor.nc.unjoin(room.mixer);
				}
				anchor.nc.release();
			} catch (Exception ignore) {
				// best effort
			}
			if (room != null) {
				room.leave(appId);
			}
			return;
		}
		if (anchor != null) {
			// Local node: the live media objects are in hand — release them directly.
			try {
				if (anchor.mg != null) {
					anchor.mg.stop(); // stopAndWait the recorder → the file is finalized before release
				}
			} catch (Exception ignore) {
				// best effort
			}
			try {
				anchor.ms.release();
			} catch (Exception ignore) {
				// best effort
			}
			return;
		}
		// Failed-over node: the live media objects died with the old engine and LIVE is empty here.
		// Rebuild the session from the replicated SAS and release the still-running pipeline, so the
		// failover doesn't leak it on the media server (KMS finalizes any recording on pipeline
		// release). Nothing here knows about the media server — the driver does the reclaim behind reattach().
		try {
			MediaSession ms = MediaCallflow.reattach(appId);
			if (ms != null) {
				ms.release();
			}
		} catch (Exception e) {
			sipLogger.warning("PlayerBye.teardown: failover reclaim for " + appId + " failed: " + e.getMessage());
		}
	}
}
