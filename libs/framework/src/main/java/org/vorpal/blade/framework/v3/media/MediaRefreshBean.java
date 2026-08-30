package org.vorpal.blade.framework.v3.media;

import javax.media.mscontrol.MediaSession;
import javax.servlet.sip.SipApplicationSession;

import com.bea.wcp.sip.WlssAction;
import com.bea.wcp.sip.WlssSipApplicationSession;

import org.vorpal.blade.framework.AsyncSipServlet;

/// The [MediaRefresh] implementation. Lives in the framework so every media application gets the
/// same one; it is *exposed* by a one-line `@Stateless @Remote` subclass in each media WAR (the
/// container discovers EJBs among the WAR's own classes, and this class deliberately carries no
/// EJB annotations so the framework jar never declares a bean of its own). An EJB packaged in a
/// WAR shares the web application's classloader, which is what lets it reach the servlet's
/// statics ([AsyncSipServlet#getSipUtil], the installed [javax.media.mscontrol.MsControlFactory]).
///
/// Runs on whichever engine the cluster-aware stub picked. It takes the app session's lock the
/// same way a media completion does ([MediaCallflow.MediaDispatcher]) and reattaches under it,
/// so the rebuilt live objects are cached on the node that will handle the call's next message.
public class MediaRefreshBean implements MediaRefresh {

	@Override
	public String refresh(String app, String sasId, String kmsSessionId, String msUri) {
		final SipApplicationSession sas = AsyncSipServlet.getSipUtil().getApplicationSessionById(sasId);
		if (sas == null) {
			return "no-session";
		}
		try {
			return (String) ((WlssSipApplicationSession) sas).doAction(new WlssAction() {
				@Override
				public Object run() throws Exception {
					if (MediaCallflow.liveSession(sasId) != null) {
						return "already-live";
					}
					MediaSession ms = MediaCallflow.reattach(sas);
					return (ms == null) ? "no-session" : "reattached";
				}
			});
		} catch (Exception e) {
			return "failed: " + e.getMessage();
		}
	}
}
