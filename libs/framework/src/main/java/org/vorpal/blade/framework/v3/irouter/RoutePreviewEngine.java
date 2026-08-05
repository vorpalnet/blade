package org.vorpal.blade.framework.v3.irouter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.Pipelines;
import org.vorpal.blade.framework.v3.configuration.RouterConfiguration;
import org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader;
import org.vorpal.blade.framework.v3.configuration.routing.Route;
import org.vorpal.blade.framework.v3.configuration.routing.Routing;
import org.vorpal.blade.framework.v3.crud.SipMessageParser;

/// Offline dry-run of the iRouter decision: parse a pasted SIP request, run
/// the enrichment pipeline inline, ask the routing phase to decide, and
/// report what WOULD happen — without proxying anything. The
/// irouter-editor's "which Route does this INVITE get?" sandbox.
///
/// [Routing#decide] is a pure function of the [Context], so this reuses the
/// production decision code exactly; the only difference from a live call is
/// that the pipeline runs inline ([Pipelines#enrich]) rather than through the
/// [IRouterInvite] async chain — which also means an I/O connector (`rest`,
/// `jdbc`, `ldap`) in the pipeline really contacts its backend during a
/// dry-run.
public final class RoutePreviewEngine {

	private RoutePreviewEngine() {
	}

	/// One dry-run pass.
	///
	/// @param config             the routing configuration (live or draft-overlaid)
	/// @param messageText        raw SIP wire text; must be a request
	/// @param initialVariables   name → value pairs pre-loaded onto the session
	///                           before the pipeline runs. May be null.
	public static RouteResult routePreview(RouterConfiguration config, String messageText,
			Map<String, String> initialVariables) {
		RouteResult result = new RouteResult();

		if (config == null) {
			result.error = "no configuration loaded";
			return result;
		}

		SipServletMessage msg;
		try {
			msg = SipMessageParser.parse(messageText);
		} catch (Exception e) {
			result.error = "failed to parse SIP message: " + e.getMessage();
			return result;
		}
		if (!(msg instanceof SipServletRequest)) {
			result.error = "routing runs on initial requests — paste a SIP request, not a response";
			return result;
		}
		SipServletRequest request = (SipServletRequest) msg;

		if (initialVariables != null && !initialVariables.isEmpty()) {
			SipApplicationSession appSession = request.getApplicationSession();
			if (appSession != null) {
				for (Map.Entry<String, String> e : initialVariables.entrySet()) {
					if (e.getKey() != null && e.getValue() != null) {
						appSession.setAttribute(e.getKey(), e.getValue());
					}
				}
			}
		}

		Context ctx = Pipelines.enrich(config.getPipeline(), request);
		result.variables = ctx.snapshot();

		Routing routing = config.getRouting();
		Route route = (routing != null) ? routing.decide(ctx) : null;

		if (route == null) {
			// Same terminal state as production: applyRouting answers 503.
			result.outcome = "none";
			return result;
		}

		if (route.getStatusCode() != null) {
			result.outcome = "respond";
			result.statusCode = route.getStatusCode();
			result.reasonPhrase = (route.getReasonPhrase() != null)
					? ctx.resolve(route.getReasonPhrase()) : null;
		} else {
			result.outcome = "forward";
			// Null requestUri = passthrough: production forwards to the
			// inbound Request-URI unchanged.
			result.requestUri = (route.getRequestUri() != null)
					? ctx.resolve(route.getRequestUri()) : null;
		}

		if (route.getHeaders() != null) {
			for (Map.Entry<String, String> h : route.getHeaders().entrySet()) {
				result.headers.put(h.getKey(), ctx.resolve(h.getValue()));
			}
		}
		if (route.getConditionalHeaders() != null) {
			for (ConditionalHeader ch : route.getConditionalHeaders()) {
				if (ch.shouldApply(ctx)) {
					result.headers.put(ch.getName(), ctx.resolve(ch.getValue()));
				} else {
					result.skippedConditionalHeaders.add(ch.getName() + " (when: " + ch.getWhen() + ")");
				}
			}
		}

		return result;
	}

	/// Result of a routing dry-run.
	///
	/// `outcome` is `forward` (proxy downstream; `requestUri` null means
	/// passthrough to the inbound Request-URI), `respond` (direct final
	/// response with `statusCode`/`reasonPhrase`), or `none` (no decision —
	/// production answers 503). `headers` are the stamped headers with
	/// `${var}` resolved, conditional ones included only when their `when`
	/// passed; the ones that didn't pass are listed by name in
	/// `skippedConditionalHeaders`. `variables` is the post-enrichment
	/// snapshot; `warnings` is populated by callers that bracket the run
	/// with a capturing logger.
	public static class RouteResult implements Serializable {
		private static final long serialVersionUID = 1L;
		public String outcome;
		public String requestUri;
		public Integer statusCode;
		public String reasonPhrase;
		public Map<String, String> headers = new LinkedHashMap<>();
		public List<String> skippedConditionalHeaders = new ArrayList<>();
		public Map<String, String> variables = new LinkedHashMap<>();
		public List<String> warnings = new ArrayList<>();
		public String error;
	}
}
