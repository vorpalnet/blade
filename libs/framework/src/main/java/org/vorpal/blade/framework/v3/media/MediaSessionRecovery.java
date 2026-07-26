package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.MediaSession;
import javax.media.mscontrol.MsControlException;
import javax.servlet.sip.SipApplicationSession;

/// Optional **failover-recovery SPI** a JSR-309 [javax.media.mscontrol.MsControlFactory] may
/// implement so BLADE can rebuild a call's live media objects on the node that takes over after a
/// cluster failover.
///
/// ## Why a side interface instead of `MsControlFactory.getMediaObject(uri)`
///
/// Standard 309 recovery is nominally "re-resolve a live object by URI"
/// ([javax.media.mscontrol.MsControlFactory#getMediaObject]). But a bare URI gives a driver no way to
/// find *which* replicated [SipApplicationSession] holds the recovery state — there is no app-session
/// handle on that call. Recovery is inherently app-scoped, so this interface passes the
/// [SipApplicationSession] explicitly: the driver persists whatever it needs to reclaim the
/// still-running media (media-server coordinates, element ids) into that replicated SAS, keyed by the
/// [MediaSession] URI, and rebuilds live objects from it on the surviving node.
///
/// The app never sees this — it speaks only `javax.media.mscontrol.*`; [MediaCallflow#reattach] drives
/// it. Drivers that do not implement it simply get no failover recovery (today's behavior): the
/// continuations still ride the replicated SAS, but the live media objects are not rebuilt.
public interface MediaSessionRecovery {

	/// Persist the recovery state of `live` into `app` (the replicated SAS), keyed by the session's
	/// URI. Must be **idempotent and cheap** — [MediaCallflow] calls it as media-server coordinates
	/// become available (each verb, and again right after the SDP anchor), so a later call simply
	/// refreshes an earlier one.
	void captureInto(SipApplicationSession app, MediaSession live);

	/// Rebuild the live [MediaSession] previously [#captureInto]-ed under `mediaSessionUri` for `app`,
	/// reconnecting to the media server and reclaiming the still-running server-side objects. The
	/// rebuilt session **carries the same URI** so BLADE's stored continuations still key to it.
	/// Returns null if no recovery state is present (nothing was anchored, or it was already released).
	MediaSession rebuild(SipApplicationSession app, String mediaSessionUri) throws MsControlException;
}
