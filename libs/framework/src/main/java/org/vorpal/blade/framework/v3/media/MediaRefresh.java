package org.vorpal.blade.framework.v3.media;

import javax.ejb.Remote;

/// The cluster's "your media server lost you" door. A media server that sees a control socket
/// die reads the `app` / `sas` tags the driver stamped on the orphaned pipelines and calls this
/// on the application — through a cluster-aware stub, so whichever engine answers is the one
/// that takes the [javax.servlet.sip.SipApplicationSession] and reopens control
/// ([MediaCallflow#reattach]). A media application exposes it by packaging
/// [MediaRefreshBean] in its WAR.
///
/// Engine loss is the whole point: the pipeline is still running, the phones are still talking,
/// and the media server keeps the objects for its collector period. The refresh lands well inside
/// that window, so the takeover engine finds everything where the dead one left it.
@Remote
public interface MediaRefresh {

	/// Bean name, and the last segment of the portable JNDI name
	/// `java:global/<app>/MediaRefresh!org.vorpal.blade.framework.v3.media.MediaRefresh`.
	String BEAN_NAME = "MediaRefresh";

	/// Reclaim the media session `msUri` (owned by app session `sasId`, running under media-server
	/// session `kmsSessionId`) on this cluster. Returns a short status for the caller's log:
	/// `reattached`, `already-live`, `no-session` (the SAS is gone — the call ended), or
	/// `failed: <reason>`.
	String refresh(String app, String sasId, String kmsSessionId, String msUri);
}
