package org.vorpal.blade.framework.v3.irouter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Parameterable;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.Callflow;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.connectors.Connector;
import org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.routing.Routing;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;

/// Callflow for the initial INVITE through the v3 iRouter pipeline.
///
/// 1. Builds a [Context] around the request (hides the
///    `SipServletRequest` from the pipeline).
/// 2. Chains every [Connector] in `config.pipeline` via
///    [CompletableFuture#thenCompose]. Sync connectors (SIP, Table)
///    complete instantly; REST fires its HTTP call and returns a
///    real future; JDBC/LDAP run on a bounded thread pool. The SIP
///    container thread is released as soon as the chain starts.
/// 3. When the chain completes, re-dispatches onto a SIP container
///    thread (0-ms ServletTimer), consults `config.routing` for the
///    routing decision — a concrete [Route] — and forwards the call
///    there through [Callflow#sendRequest]. The application's
///    `session.passthru` setting decides how: on, the caller's INVITE is
///    proxied without Record-Route and iRouter leaves the call; off, iRouter
///    stays in it as a B2BUA and the servlet's b2bua callflows relay the
///    re-INVITE, ACK and BYE.
public class IRouterInvite extends Callflow {
	private static final long serialVersionUID = 1L;

	/// Snapshot of the active routing config, captured by the leaf
	/// servlet (IRouterServlet or a subclass) at instantiation
	/// time and passed in here. Per-request snapshot avoids mid-request
	/// reload races and decouples the framework-resident IRouterInvite
	/// from any one servlet's static `settings` field.
	protected final IRouterConfig config;

	/// Analytics event for a forwarded call, published after the route's
	/// headers are stamped on the outbound INVITE.
	///
	/// Every routing decision publishes one of three events whenever the
	/// application publishes to the event bus: [BladeEventTypes#CALL_ROUTED]
	/// with the `destination`, and [BladeEventTypes#CALL_DECLINED] or
	/// [BladeEventTypes#CALL_RESPONDED] with the `status`. A screening config
	/// adds the headers its route stamps (for example `X-Call-Screen`) by naming
	/// the event under `analytics.events` with attribute selectors.
	public static final String EVENT_ROUTED = "callRouted";

	/// Analytics event for a direct response of 400 or above. Shares the
	/// framework's `callDeclined` type, so a subscriber selecting declined calls
	/// sees the router's too.
	public static final String EVENT_DECLINED = "callDeclined";

	/// Analytics event for a direct response below 400, such as a redirect
	/// server's 302. Published as [BladeEventTypes#CALL_RESPONDED].
	public static final String EVENT_RESPONDED = "callResponded";

	public IRouterInvite(IRouterConfig config) {
		this.config = config;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// Defensive: a null `config` means the SettingsManager couldn't
		// produce an IRouterConfig from the live JSON. Without a config
		// we have no pipeline to run; respond 503 with a log line that
		// points at the actual files an operator needs to inspect, not
		// an opaque NPE stack trace in the SIP body.
		if (config == null) {
			sipLogger.severe(request, buildNullConfigMessage());
			safeSend(request, 503);
			return;
		}
		if (config.getPipeline() == null || config.getPipeline().isEmpty()) {
			sipLogger.severe(request, "iRouter config has no pipeline — pipeline JSON is "
					+ "missing or empty in the live config file");
			safeSend(request, 503);
			return;
		}

		Context ctx = new Context(request);

		// Subclass extension point: write any pre-pipeline values into ctx
		// (e.g. a screening subclass's `${sipJson}` formatter). Default is empty.
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
				// malformed header — leave vorpalOrigin null
			}
			// Only emit this BLADE-chain diagnostic when the upstream is
			// actually a prior BLADE service (i.e. X-Vorpal-ID is present).
			// For external upstreams the line is just `vorpalOrigin=null …`
			// noise on every call.
			if (vorpalOrigin != null) {
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

		// An async connector (REST) completes the chain on its HttpClient
		// executor thread, where the container has no call context, and
		// sending the INVITE from there fails (a passthru proxy's
		// request.getProxy() throws NPE). The 0-ms
		// ServletTimer fires applyRouting on a container thread with
		// appSession context, whichever thread completed the chain.
		chain.thenRun(() -> startTimer(request.getApplicationSession(), 0, false,
				timer -> applyRouting(request, ctx, config)))
				.exceptionally(t -> {
					sipLogger.severe(request, "iRouter pipeline failed: " + t.getMessage());
					safeSend(request, 500);
					return null;
				});
	}

	/// Subclass hook: write extra values into the Context before the
	/// pipeline runs. The default implementation does nothing — base
	/// iRouter is fully driven by configured connectors. Subclasses
	/// (e.g. a screening service) override this to inject values that don't fit
	/// the connector/selector model — typically a customer-specific
	/// formatting of the SIP request itself.
	protected void enrichContext(SipServletRequest request, Context ctx) {
		// no-op by default
	}

	/// Compose the diagnostic for the null-config 503. Points the operator
	/// at the three live-config tiers that `Settings.reload()` merges, with
	/// the actual app / cluster / server names from `SettingsManager`
	/// filled in so the suggested paths are copy-pasteable. The real
	/// root-cause line is the Jackson SEVERE that `Settings.reload()`'s
	/// catch block logged just above this one — typically an
	/// `Already had POJO for id …` from a duplicate entry across tiers,
	/// or a `UnrecognizedPropertyException` from a stale field name.
	private static String buildNullConfigMessage() {
		String app = SettingsManager.getApplicationName();
		String cluster = SettingsManager.getClusterName();
		String server = SettingsManager.getServerName();
		StringBuilder sb = new StringBuilder(
				"iRouter config is null — config did not deserialize. Read the SEVERE Jackson "
				+ "exception logged above this line for the root cause, then check the live-config "
				+ "tiers (all three are merged at load time; a stale or duplicate entry in ANY "
				+ "tier breaks the whole load):\n");
		sb.append("  ./config/custom/vorpal/").append(app).append(".json  (domain)\n");
		if (cluster != null) {
			sb.append("  ./config/custom/vorpal/_clusters/").append(cluster)
					.append("/").append(app).append(".json  (cluster)\n");
		}
		if (server != null) {
			sb.append("  ./config/custom/vorpal/_servers/").append(server)
					.append("/").append(app).append(".json  (server)\n");
		}
		sb.append("The cluster and server tiers are usually empty — operators only need them when ")
				.append("intentionally overriding the domain config. Stale leftovers there (e.g. ")
				.append("from earlier Configurator publishes) cause silent merge corruption.");
		return sb.toString();
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

	/// Context variables that set the call's scope: a translation row whose
	/// extras name them (for example a table keyed on the dialed number) files
	/// every later event of the call under that tenant and department. See
	/// [Callflow#setTenant].
	public static final String TENANT_VAR = "blade.tenant";
	public static final String DEPARTMENT_VAR = "blade.department";

	/// Promote [#TENANT_VAR] and [#DEPARTMENT_VAR] from the pipeline's results
	/// to the call, before the INVITE goes on, so the next hop receives them
	/// on X-Vorpal-ID. A value that is not a name is refused with a warning
	/// rather than half-applied.
	///
	/// A proxy forwards the INVITE it received, whose X-Vorpal-ID the hop
	/// before stamped without this scope. Setting the scope on this
	/// application's session alone would file iRouter's own events under it
	/// and leave every hop after it unscoped, so the header is rewritten here.
	static void applyScope(SipServletRequest request, Context ctx) {
		String tenant = ctx.get(TENANT_VAR);
		String department = ctx.get(DEPARTMENT_VAR);
		boolean hasTenant = tenant != null && !tenant.isEmpty();
		boolean hasDepartment = department != null && !department.isEmpty();
		if (!hasTenant && !hasDepartment) {
			return;
		}
		try {
			if (hasTenant) {
				Callflow.setTenant(request.getApplicationSession(), tenant);
			}
			if (hasDepartment) {
				Callflow.setDepartment(request.getApplicationSession(), department);
			}
			Parameterable xVorpalId = request.getParameterableHeader(X_VORPAL_ID);
			if (xVorpalId == null) {
				stampVorpalIdHeaders(request, request.getApplicationSession(), request.getSession());
			} else {
				if (hasTenant) {
					xVorpalId.setParameter(TENANT_PARAM, tenant);
				}
				if (hasDepartment) {
					xVorpalId.setParameter(DEPARTMENT_PARAM, department);
				}
				request.setHeader(X_VORPAL_ID, xVorpalId.toString());
			}
		} catch (IllegalArgumentException e) {
			sipLogger.warning(request, "iRouter: call scope not applied: " + e.getMessage());
		} catch (javax.servlet.sip.ServletParseException e) {
			sipLogger.warning(request, "iRouter: call scope not forwarded: " + e.getMessage());
		}
	}

	private void applyRouting(SipServletRequest request, Context ctx, IRouterConfig config) {
		applyScope(request, ctx);
		Routing routing = config.getRouting();
		Route route = (routing != null) ? routing.decide(ctx) : null;

		if (route == null) {
			sipLogger.warning(request, "iRouter no routing decision; rejecting with 503");
			safeSend(request, 503);
			return;
		}

		// Direct-response routes (statusCode set) short-circuit before
		// any forwarding logic — same path for proxy iRouter and for
		// redirect-server subclasses.
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
		// The decision event reads the response after its headers are stamped,
		// and must go before sendResponse for the same reason the log line does.
		AnalyticsEvent decided = SettingsManager.createEvent(code >= 400 ? EVENT_DECLINED : EVENT_RESPONDED,
				response);
		if (decided != null) {
			decided.addAttribute("status", String.valueOf(code));
		}
		SettingsManager.sendEvent(response);
		// Log before send — a final non-2xx response invalidates the session,
		// after which the logger's hexHash → getGlareState → session.getAttribute
		// path throws "Invalid attribute store!". Same reasoning as
		// Callflow.sendResponse.
		sipLogger.fine(request, "iRouter responded " + code + (reason != null ? " " + reason : ""));
		// Route through the framework's sendResponse rather than response.send()
		// so we pick up X-Vorpal-* header injection and the Logger.superArrow
		// diagram-arrow + FINEST raw-response dump.
		sendResponse(response);
	}

	/// Hook for the *forwarding* role: act on the chosen [Route] + resolved
	/// destination URI. The default implementation builds the INVITE to
	/// `destination` the way a B2BUA does and sends it with [#sendRequest], so
	/// the application's `session.passthru` setting picks the role:
	///
	/// - **passthru on**: the caller's own INVITE is proxied to `destination`
	///   without Record-Route, and iRouter drops out of the dialog (re-INVITE,
	///   ACK and BYE go end-to-end). The container relays the responses.
	/// - **passthru off**: iRouter is a B2BUA. [#relay] answers the caller
	///   with what the callee sends, and the servlet's b2bua callflows carry
	///   the rest of the call.
	///
	/// The route's headers go on the caller's INVITE, before the outbound one
	/// is built from it. A passthru proxy forwards that INVITE as it is, and a
	/// B2BUA copies its headers, so they reach the callee either way.
	///
	/// Reached only for routes WITHOUT a `statusCode` — a route that sets a
	/// status code is a direct response and short-circuits to [#sendStatus] in
	/// [#applyRouting] before this runs. A screening service can mix both with no Java
	/// override: allow is a 302 with a `Contact` (sendStatus), block is a 603
	/// Decline (sendStatus), and redirect is a forward route (`requestUri`,
	/// no status code) that lands here. Override this only to take a
	/// different forwarding role.
	///
	/// Throws are caught by [#applyRouting] which sends 500 upstream.
	protected void executeRoute(SipServletRequest request, URI destination, Route route, Context ctx)
			throws Exception {
		applyHeaders(request, route, ctx);
		AnalyticsEvent routed = SettingsManager.createEvent(EVENT_ROUTED, request);
		if (routed != null && destination != null) {
			routed.addAttribute("destination", destination.toString());
		}
		SettingsManager.sendEvent(request);

		SipServletRequest outbound = sipFactory.createRequest(request.getApplicationSession(), INVITE,
				request.getFrom(), request.getTo());
		outbound.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, request);
		copyContentAndHeaders(request, outbound); // links the callee's dialog to the caller's
		outbound.setRequestURI(destination);
		sendRequest(outbound, calleeResponse -> relay(request, calleeResponse));
	}

	/// Answers the caller with the callee's response, when iRouter is a B2BUA.
	/// Under passthru the container relays every response itself, so this does
	/// nothing. A caller who cancelled has already been answered; the callee's
	/// leg is then cancelled or ended instead (see [#endIfAbandoned]).
	///
	/// @param caller         the caller's INVITE
	/// @param calleeResponse a response to the outbound INVITE
	protected void relay(SipServletRequest caller, SipServletResponse calleeResponse)
			throws ServletException, IOException {
		if (isPassthru() || endIfAbandoned(caller, calleeResponse)) {
			return;
		}
		SipServletResponse callerResponse = caller.createResponse(calleeResponse.getStatus());
		copyContentAndHeaders(calleeResponse, callerResponse); // links the caller to the answering dialog
		if (successful(calleeResponse)) {
			sendResponse(callerResponse, ack -> sendAcknowledgement(ack, calleeResponse));
		} else {
			sendResponse(callerResponse);
		}
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
	/// overrides can use the same fallback (e.g. a redirect-server subclass sending
	/// a 500 on redirect-build failure). Sent through the framework, so the
	/// flight recorder sees the refusal instead of an unanswered INVITE.
	protected void safeSend(SipServletRequest request, int status) {
		try {
			sendResponse(request.createResponse(status));
		} catch (Exception ignore) {
		}
	}
}
