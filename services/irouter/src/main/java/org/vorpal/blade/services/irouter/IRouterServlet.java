package org.vorpal.blade.services.irouter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.adapters.Adapter;
import org.vorpal.blade.framework.v3.configuration.tables.RoutingTable;

/// Intelligent Router — orchestrates the v3 iRouter pipeline
/// asynchronously.
///
/// On the initial INVITE, [#doInvite]:
///
/// 1. Builds a [Context] around the request (hides the
///    `SipServletRequest` from the pipeline).
/// 2. Chains every [Adapter] in `config.adapters` via
///    [CompletableFuture#thenCompose]. Sync adapters (SIP, Map)
///    complete instantly; REST fires its HTTP call and returns a
///    real future; JDBC/LDAP run on a bounded thread pool. The SIP
///    container thread is released as soon as the chain starts.
/// 3. When the chain completes, walks `config.tables` to find a
///    Treatment, resolves `${requestUri}`, and calls
///    `proxy.proxyTo(destination)`.
///
/// Re-INVITEs on an established dialog pass through unchanged —
/// routing already happened for the initial transaction.
///
/// Other request types (ACK, BYE, UPDATE, INFO, …) are handled
/// automatically by the SIP container's proxy machinery once
/// `proxy.proxyTo` has been called for the initial INVITE.
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class IRouterServlet extends SipServlet implements SipServletListener {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<IRouterConfig> settings;

	@Resource
	private SipFactory sipFactory;

	@Override
	public void servletInitialized(SipServletContextEvent event) {
		this.sipFactory = (SipFactory) event.getServletContext().getAttribute("javax.servlet.sip.SipFactory");
		try {
			settings = new SettingsManager<>(event, IRouterConfig.class, new IRouterConfigSample());
		} catch (Exception e) {
			Callflow.getSipLogger().severe("IRouterServlet init failed: " + e.getMessage());
		}
	}

	@Override
	public void destroy() {
		try {
			if (settings != null) settings.unregister();
		} catch (Exception ignore) {
		}
		super.destroy();
	}

	@Override
	protected void doInvite(SipServletRequest request) throws ServletException, IOException {
		Logger sipLogger = Callflow.getSipLogger();

		// Re-INVITEs in an established dialog pass through unchanged.
		if (!request.isInitial()) {
			try {
				request.getProxy().proxyTo(request.getRequestURI());
			} catch (Exception e) {
				sipLogger.severe(request, "iRouter re-INVITE proxy failed: " + e.getMessage());
			}
			return;
		}

		IRouterConfig config = settings.getCurrent();
		Context ctx = new Context(request);

		// Chain every adapter; each runs after the previous completes.
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (Adapter adapter : config.adapters) {
			final Adapter a = adapter;
			chain = chain.thenCompose(v -> safeInvoke(a, ctx, sipLogger));
		}

		chain.thenRun(() -> applyTreatment(request, ctx, config, sipLogger))
				.exceptionally(t -> {
					sipLogger.severe(request, "iRouter pipeline failed: " + t.getMessage());
					safeSend(request, 500);
					return null;
				});
	}

	private static CompletableFuture<Void> safeInvoke(Adapter adapter, Context ctx, Logger sipLogger) {
		try {
			CompletableFuture<Void> f = adapter.invoke(ctx);
			return (f != null) ? f : CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			sipLogger.warning("iRouter adapter " + adapter.getId() + " threw: " + e.getMessage());
			return CompletableFuture.completedFuture(null);
		}
	}

	private void applyTreatment(SipServletRequest request, Context ctx,
			IRouterConfig config, Logger sipLogger) {
		Map<String, String> treatment = null;
		for (RoutingTable table : config.tables) {
			treatment = table.match(ctx);
			if (treatment != null) {
				sipLogger.fine(request, "iRouter table " + table.getId() + " matched");
				break;
			}
		}
		if (treatment == null) treatment = config.defaultTreatment;

		String ruri = (treatment != null) ? ctx.resolve(treatment.get("requestUri")) : null;

		try {
			Proxy proxy = request.getProxy();
			URI destination;
			if (ruri == null) {
				destination = request.getRequestURI();
				sipLogger.fine(request, "iRouter passthrough → " + destination);
			} else {
				destination = sipFactory.createURI(ruri);
				Callflow.copyParameters(request.getRequestURI(), destination);
				sipLogger.fine(request, "iRouter routing to " + destination);
			}
			proxy.proxyTo(destination);
		} catch (Exception e) {
			sipLogger.severe(request, "iRouter proxyTo failed: " + e.getMessage());
			safeSend(request, 500);
		}
	}

	private static void safeSend(SipServletRequest request, int status) {
		try {
			request.createResponse(status).send();
		} catch (Exception ignore) {
		}
	}
}
