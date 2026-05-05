package org.vorpal.blade.services.irouter;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;
import org.vorpal.blade.framework.v3.irouter.IRouterConfigSample;
import org.vorpal.blade.framework.v3.irouter.IRouterInvite;

/// Intelligent Router — orchestrates the v3 iRouter pipeline
/// asynchronously.
///
/// Initial INVITEs are dispatched to [IRouterInvite], which runs
/// the configured connector pipeline and applies the resulting
/// routing decision via the SIP proxy API. Re-INVITE / ACK / BYE
/// pass through automatically: [IRouterInvite] uses
/// [Callflow#proxyRequest] which marks the appSession as a proxy,
/// so [AsyncSipServlet] short-circuits subsequent in-dialog
/// requests through the container's proxy machinery.
///
/// **Annotations live on leaf classes, not on this base.** Java
/// class-level annotations are not @Inherited; if `@SipApplication` /
/// `@SipServlet` / `@SipListener` were on this class, every subclass
/// WAR (e.g. connect/securelogix) would see both this class and its
/// own subclass as annotated SIP servlets in one app — invalid. The
/// iRouter WAR ships [IRouterApp] as its annotated leaf; commercial
/// extensions ship their own annotated subclass.
public class IRouterServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<IRouterConfig> settings;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settings = new SettingsManager<>(event, IRouterConfig.class, new IRouterConfigSample());
		} catch (Exception e) {
			sipLogger.severe("IRouterServlet init failed: " + e.getMessage());
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		try {
			if (settings != null) settings.unregister();
		} catch (Exception ignore) {
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		if ("INVITE".equals(request.getMethod()) && request.isInitial()) {
			return new IRouterInvite(settings.getCurrent());
		}
		return null;
	}
}
