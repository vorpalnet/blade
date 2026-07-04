package org.vorpal.blade.framework.v3.irouter;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.snmp.Snmp;

/// Base SIP servlet for the v3 iRouter pipeline — orchestrates the connector
/// chain + routing decision asynchronously by dispatching each initial INVITE
/// to an [IRouterInvite] callflow.
///
/// **Unannotated on purpose** (like [org.vorpal.blade.framework.v2.b2bua.B2buaServlet]).
/// Java class-level `@SipApplication` / `@SipServlet` / `@SipListener` are not
/// `@Inherited`; if they lived here, every WAR that subclasses this base would
/// register *two* SIP servlets (base + leaf) in one app — invalid. So each
/// deployable WAR ships a tiny annotated leaf that extends this class:
/// `IRouterApp` for the standalone iRouter WAR, `SecureLogixServlet` for the
/// SecureLogix WAR, and so on. Because this base lives in the framework JAR
/// (bundled per-WAR), a commercial extension subclasses it with only the
/// framework dependency — no cross-WAR class sharing needed.
///
/// ## Two subclass seams
///
/// Everything an iRouter servlet does — settings lifecycle, the static
/// `settings` snapshot, and the INVITE dispatch — is shared here. A subclass
/// customizes exactly two things:
///  - [#newSampleConfig] — the sample/default config (pipeline + routing).
///  - [#newInvite] — the callflow for an initial INVITE (override its
///    `enrichContext` to inject bespoke pre-pipeline values).
///
/// Both have working defaults (plain iRouter), so the standalone WAR's leaf is
/// pure annotations with an empty body.
///
/// Re-INVITE / ACK / BYE need no handling here: [IRouterInvite] proxies via
/// [Callflow#proxyRequest], which marks the appSession as a proxy so
/// [AsyncSipServlet] short-circuits subsequent in-dialog requests through the
/// container's proxy machinery.
public class IRouterServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 1L;

	/// Per-WAR snapshot of the active routing config. Static so the leaf and
	/// any helper can reach it; the framework JAR is bundled per-WAR, so each
	/// deployed iRouter app has its own independent value (no cross-WAR
	/// sharing). The chosen value is passed into each [IRouterInvite] at
	/// dispatch time, so the callflow never reads this field directly.
	public static SettingsManager<IRouterConfig> settings;

	/// Subclass seam: the sample config written to `_samples/` on first deploy
	/// and used as the default until an operator publishes a live one. Override
	/// to supply a customer-specific config. Default: plain iRouter.
	protected IRouterConfig newSampleConfig() {
		return new IRouterConfigSample();
	}

	/// Subclass seam: the callflow run for an initial INVITE. Override to supply
	/// a customer-specific callflow — typically one whose `enrichContext`
	/// injects bespoke pre-pipeline values (e.g. SecureLogix's `${sipJson}`).
	/// Default: plain [IRouterInvite].
	protected IRouterInvite newInvite(IRouterConfig config) {
		return new IRouterInvite(config);
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settings = new SettingsManager<>(event, IRouterConfig.class, newSampleConfig());
		} catch (Exception e) {
			// A failed startup is exactly what an NMS wants to hear about, so
			// trap it (fail-closed off-OCCAS). getSimpleName() names the actual
			// leaf (IRouterApp / SecureLogixServlet / …).
			String msg = getClass().getSimpleName() + " init failed: " + e.getMessage();
			sipLogger.severe(msg);
			Snmp.trap(Snmp.Severity.ERROR, msg);
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
		if (Callflow.INVITE.equals(request.getMethod()) && request.isInitial()) {
			return newInvite(settings.getCurrent());
		}
		return null;
	}
}
