package org.vorpal.blade.services.irouter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.sip.Parameterable;
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
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.routing.Routing;

/// Intelligent Router — orchestrates the v3 iRouter pipeline
/// asynchronously.
///
/// On the initial INVITE, [#doInvite]:
///
/// 1. Builds a [Context] around the request (hides the
///    `SipServletRequest` from the pipeline).
/// 2. Chains every [Connector] in `config.pipeline` via
///    [CompletableFuture#thenCompose]. Sync connectors (SIP, Map)
///    complete instantly; REST fires its HTTP call and returns a
///    real future; JDBC/LDAP run on a bounded thread pool. The SIP
///    container thread is released as soon as the chain starts.
/// 3. When the chain completes, consults `config.routing` for the
///    routing decision — a concrete [Route] — and calls
///    `proxy.proxyTo(destination)`.
///
/// Re-INVITEs on an established dialog pass through unchanged —
/// routing already happened for the initial transaction.
///
/// Other request types (ACK, BYE, UPDATE, INFO, …) are handled
/// automatically by the SIP container's proxy machinery once
/// `proxy.proxyTo` has been called for the initial INVITE.
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

		// FINER: dump everything useful for diagnosing a table miss.
		//  - vorpalOrigin: X-Vorpal-ID;origin — stamped by Callflow on the
		//    first BLADE service to see the request and propagated downstream.
		//    When non-null, the selector resolves to this and the table
		//    lookup should just work.
		//  - initialRemoteAddr: container-derived original sender; often null
		//    for internally-dispatched requests.
		//  - remoteAddr: immediate transport peer — another OCCAS service
		//    when iRouter isn't first in the chain.
		//  - via: the full Via stack, numbered from the top.
		if (sipLogger.isLoggable(Level.FINER)) {
			String vorpalOrigin = null;
			try {
				Parameterable xVorpalId = request.getParameterableHeader(Callflow.X_VORPAL_ID);
				if (xVorpalId != null) vorpalOrigin = xVorpalId.getParameter(Callflow.ORIGIN_PARAM);
			} catch (Exception ignore) {
				// malformed header — leave vorpalOrigin null for the log line
			}
			StringBuilder vias = new StringBuilder();
			@SuppressWarnings("unchecked")
			java.util.ListIterator<String> it = request.getHeaders("Via");
			int i = 0;
			while (it.hasNext()) {
				if (vias.length() > 0) vias.append(" | ");
				vias.append("[").append(i++).append("] ").append(it.next());
			}
			sipLogger.finer(request, "iRouter vorpalOrigin=" + vorpalOrigin
					+ " initialRemoteAddr=" + request.getInitialRemoteAddr()
					+ " remoteAddr=" + request.getRemoteAddr()
					+ " via=" + vias);
		}

		// Chain every pipeline step; each runs after the previous completes.
		// The pipeline is pure enrichment — every connector writes values
		// into the Context. The routing decision is a separate top-level
		// phase that runs once the chain completes.
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		for (Connector connector : config.getPipeline()) {
			final Connector a = connector;
			chain = chain.thenCompose(v -> safeInvoke(a, ctx, sipLogger));
		}

		chain.thenRun(() -> applyRouting(request, ctx, config, sipLogger))
				.exceptionally(t -> {
					sipLogger.severe(request, "iRouter pipeline failed: " + t.getMessage());
					safeSend(request, 500);
					return null;
				});
	}

	private static CompletableFuture<Void> safeInvoke(Connector connector, Context ctx, Logger sipLogger) {
		try {
			CompletableFuture<Void> f = connector.invoke(ctx);
			return (f != null) ? f : CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			sipLogger.warning("iRouter connector " + connector.getId() + " threw: " + e.getMessage());
			return CompletableFuture.completedFuture(null);
		}
	}

	private void applyRouting(SipServletRequest request, Context ctx,
			IRouterConfig config, Logger sipLogger) {
		Routing routing = config.getRouting();
		Route route = (routing != null) ? routing.decide(ctx) : null;

		if (route == null) {
			sipLogger.warning(request, "iRouter no routing decision; rejecting with 503");
			safeSend(request, 503);
			return;
		}

		String ruri = (route.getRequestUri() != null) ? ctx.resolve(route.getRequestUri()) : null;

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
			applyHeaders(request, route, ctx, sipLogger);
			proxy.proxyTo(destination);
		} catch (Exception e) {
			sipLogger.severe(request, "iRouter proxyTo failed: " + e.getMessage());
			safeSend(request, 500);
		}
	}

	private static void applyHeaders(SipServletRequest request, Route route,
			Context ctx, Logger sipLogger) {
		if (route == null || route.getHeaders() == null) return;
		for (Map.Entry<String, String> h : route.getHeaders().entrySet()) {
			try {
				request.setHeader(h.getKey(), ctx.resolve(h.getValue()));
			} catch (Exception e) {
				sipLogger.warning(request, "iRouter header " + h.getKey()
						+ " failed to apply: " + e.getMessage());
			}
		}
	}

	private static void safeSend(SipServletRequest request, int status) {
		try {
			request.createResponse(status).send();
		} catch (Exception ignore) {
		}
	}
}
