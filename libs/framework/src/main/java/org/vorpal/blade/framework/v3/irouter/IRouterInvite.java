package org.vorpal.blade.framework.v3.irouter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.routing.Routing;

/// Callflow for the initial INVITE through the v3 iRouter pipeline.
///
/// 1. Builds a [Context] around the request (hides the
///    `SipServletRequest` from the pipeline).
/// 2. Chains every [Connector] in `config.pipeline` via
///    [CompletableFuture#thenCompose]. Sync connectors (SIP, Map)
///    complete instantly; REST fires its HTTP call and returns a
///    real future; JDBC/LDAP run on a bounded thread pool. The SIP
///    container thread is released as soon as the chain starts.
/// 3. When the chain completes, consults `config.routing` for the
///    routing decision — a concrete [Route] — and proxies via
///    [Callflow#proxyRequest] (which marks the appSession as a
///    proxy so re-INVITE / BYE / ACK pass through automatically).
public class IRouterInvite extends Callflow {
	private static final long serialVersionUID = 1L;

	/// Snapshot of the active routing config, captured by the leaf
	/// servlet (IRouterServlet, SecureLogixServlet, …) at instantiation
	/// time and passed in here. Per-request snapshot avoids mid-request
	/// reload races and decouples the framework-resident IRouterInvite
	/// from any one servlet's static `settings` field.
	protected final IRouterConfig config;

	public IRouterInvite(IRouterConfig config) {
		this.config = config;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		Context ctx = new Context(request);

		// Subclass extension point: write any pre-pipeline values into ctx
		// (e.g. SecureLogix's `${sipJson}` formatter). Default is empty.
		enrichContext(request, ctx);

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
			chain = chain.thenCompose(v -> safeInvoke(a, ctx));
		}

		chain.thenRun(() -> applyRouting(request, ctx, config))
				.exceptionally(t -> {
					sipLogger.severe(request, "iRouter pipeline failed: " + t.getMessage());
					safeSend(request, 500);
					return null;
				});
	}

	/// Subclass hook: write extra values into the Context before the
	/// pipeline runs. The default implementation does nothing — base
	/// iRouter is fully driven by configured connectors. Subclasses
	/// (e.g. SecureLogix) override this to inject values that don't fit
	/// the connector/selector model — typically a customer-specific
	/// formatting of the SIP request itself.
	protected void enrichContext(SipServletRequest request, Context ctx) {
		// no-op by default
	}

	private static CompletableFuture<Void> safeInvoke(Connector connector, Context ctx) {
		try {
			CompletableFuture<Void> f = connector.invoke(ctx);
			return (f != null) ? f : CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			sipLogger.warning("iRouter connector " + connector.getId() + " threw: " + e.getMessage());
			return CompletableFuture.completedFuture(null);
		}
	}

	private void applyRouting(SipServletRequest request, Context ctx, IRouterConfig config) {
		Routing routing = config.getRouting();
		Route route = (routing != null) ? routing.decide(ctx) : null;

		if (route == null) {
			sipLogger.warning(request, "iRouter no routing decision; rejecting with 503");
			safeSend(request, 503);
			return;
		}

		// Direct-response routes (statusCode set) short-circuit before
		// any forwarding logic — same path for proxy iRouter and for
		// SecureLogix-style redirect subclasses.
		if (route.getStatusCode() != null) {
			try {
				sendStatus(request, route, ctx);
			} catch (Exception e) {
				sipLogger.severe(request, "iRouter status response failed: " + e.getMessage());
				safeSend(request, 500);
			}
			return;
		}

		String ruri = (route.getRequestUri() != null) ? ctx.resolve(route.getRequestUri()) : null;
		URI destination;
		if (ruri == null) {
			destination = request.getRequestURI();
			sipLogger.fine(request, "iRouter passthrough → " + destination);
		} else {
			try {
				destination = sipFactory.createURI(ruri);
				Callflow.copyParameters(request.getRequestURI(), destination);
			} catch (Exception e) {
				sipLogger.severe(request, "iRouter URI build failed for " + ruri + ": " + e.getMessage());
				safeSend(request, 500);
				return;
			}
			sipLogger.fine(request, "iRouter routing to " + destination);
		}

		try {
			executeRoute(request, destination, route, ctx);
		} catch (Exception e) {
			sipLogger.severe(request, "iRouter executeRoute failed: " + e.getMessage());
			safeSend(request, 500);
		}
	}

	/// Build and send a direct response for a route whose
	/// `statusCode` is set. Headers and conditional headers are
	/// stamped on the response. `${var}` is resolved on the
	/// reason-phrase. `protected` so subclasses overriding the
	/// dispatch path can reuse the same response shape if they
	/// need to.
	protected void sendStatus(SipServletRequest request, Route route, Context ctx) throws Exception {
		int code = route.getStatusCode();
		String reasonRaw = route.getReasonPhrase();
		String reason = (reasonRaw != null) ? ctx.resolve(reasonRaw) : null;
		SipServletResponse response = (reason != null && !reason.isEmpty())
				? request.createResponse(code, reason)
				: request.createResponse(code);
		applyHeaders(response, route, ctx);
		// Log before send — a final non-2xx response invalidates the session,
		// after which the logger's hexHash → getGlareState → session.getAttribute
		// path throws "Invalid attribute store!". Same reasoning as
		// SecureLogixInvite.executeRoute and Callflow.sendResponse.
		sipLogger.fine(request, "iRouter responded " + code + (reason != null ? " " + reason : ""));
		// Route through the framework's sendResponse rather than response.send()
		// so we pick up X-Vorpal-* header injection and the Logger.superArrow
		// diagram-arrow + FINEST raw-response dump.
		sendResponse(response);
	}

	/// Subclass hook: act on the chosen [Route] + resolved destination
	/// URI. The default implementation **proxies** the INVITE
	/// downstream with `Record-Route: false` so iRouter drops out of
	/// the dialog (re-INVITE / ACK / BYE go end-to-end). Route headers
	/// and conditional headers are stamped on the outbound request
	/// before `proxyTo` fires.
	///
	/// Subclasses override this to take a different SIP role for the
	/// same routing-table machinery — for example
	/// [org.vorpal.blade.connect.securelogix.SecureLogixInvite]
	/// returns a `302 Moved Temporarily` with the destination in
	/// `Contact`, so SecureLogix never participates in the call leg
	/// at all (an even stronger drop-out than `recordRoute=false`).
	///
	/// Throws are caught by [#applyRouting] which sends 500 upstream.
	protected void executeRoute(SipServletRequest request, URI destination, Route route, Context ctx)
			throws Exception {
		Proxy proxy = request.getProxy();
		proxy.setRecordRoute(false);
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(request, "iRouter proxy.recordRoute=" + proxy.getRecordRoute()
					+ " supervised=" + proxy.getSupervised());
		}
		applyHeaders(request, route, ctx);
		proxyRequest(proxy, destination);
	}

	/// Apply [Route#getHeaders] and [Route#getConditionalHeaders] to
	/// `msg`. `protected` so subclasses overriding [#executeRoute] can
	/// stamp the configured headers on whichever message they create
	/// (request for proxy, response for redirect). `${var}` resolved
	/// before the value lands on the wire.
	protected static void applyHeaders(SipServletMessage msg, Route route, Context ctx) {
		if (route == null) return;
		if (route.getHeaders() != null) {
			for (Map.Entry<String, String> h : route.getHeaders().entrySet()) {
				try {
					msg.setHeader(h.getKey(), ctx.resolve(h.getValue()));
				} catch (Exception e) {
					sipLogger.warning("iRouter header " + h.getKey()
							+ " failed to apply: " + e.getMessage());
				}
			}
		}
		if (route.getConditionalHeaders() != null) {
			for (ConditionalHeader ch : route.getConditionalHeaders()) {
				if (!ch.shouldApply(ctx)) continue;
				try {
					msg.setHeader(ch.getName(), ctx.resolve(ch.getValue()));
				} catch (Exception e) {
					sipLogger.warning("iRouter conditional header " + ch.getName()
							+ " failed to apply: " + e.getMessage());
				}
			}
		}
	}

	/// Last-resort response sender for terminal error paths in
	/// [#applyRouting]. `protected` so subclasses' [#executeRoute]
	/// overrides can use the same fallback (e.g. SecureLogix sending
	/// a 500 on redirect-build failure).
	protected static void safeSend(SipServletRequest request, int status) {
		try {
			request.createResponse(status).send();
		} catch (Exception ignore) {
		}
	}
}
