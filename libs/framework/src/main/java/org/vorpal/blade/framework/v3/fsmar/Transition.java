package org.vorpal.blade.framework.v3.fsmar;

import java.io.Serializable;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;
import javax.servlet.sip.ar.SipRouteModifier;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v2.config.FormSection;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A routing transition in the FSMAR state machine.
///
/// A transition fires when its [#when] condition evaluates true against the
/// routing [Context] — the values its state's selectors extracted from the
/// request. Transitions are evaluated in order; the first to fire wins. When it
/// fires it routes to [#next], optionally identifying a [#subscriber] (by SIP
/// header name, per the JSR-289 App Router contract) and pushing [#routes],
/// which may contain `${}` placeholders resolved against the context.
@JsonPropertyOrder({ "id", "when", "next", "subscriber", "region", "routes", "routeModifier" })
@FormSection(title = "Condition", fields = { "when" })
@FormSection(title = "Route to", fields = { "next", "subscriber", "region", "routes", "routeModifier" })
@FormLayoutGroup({ "next", "subscriber", "region" })
@FormLayoutGroup({ "routes", "routeModifier" })
public class Transition implements Serializable {
	private static final long serialVersionUID = 1L;

	/// JSR-289 routing region for the routed application. Serialized by name;
	/// maps to the [SipApplicationRoutingRegion] constants in createRouterInfo.
	public enum Region {
		ORIGINATING, TERMINATING, NEUTRAL
	}

	private String id;
	private String when;
	private String next;
	private String subscriber;
	private Region region;
	private String[] routes;
	private SipRouteModifier routeModifier;

	/// Lazily-compiled form of [#when]. Transient: recompiled on first use after
	/// deserialization (the App Router round-trips transitions via `stateInfo`).
	@JsonIgnore
	private transient Expression compiled;

	/// Optional identifier for logging and diagnostics.
	@JsonPropertyDescription("Optional identifier for logging and diagnostics")
	public String getId() {
		return id;
	}

	public Transition setId(String id) {
		this.id = id;
		return this;
	}

	/// Boolean condition over the routing context. Fires the transition when it
	/// evaluates true. Empty/absent matches unconditionally. See the Expression
	/// grammar — e.g. `${To.user} == 'bob' && ${From.user} == 'alice'`.
	@JsonPropertyDescription("Boolean condition over extracted values; empty matches unconditionally. e.g. ${To.user} == 'bob'")
	@FormLayout(wide = true)
	public String getWhen() {
		return when;
	}

	public Transition setWhen(String when) {
		this.when = when;
		this.compiled = null;
		return this;
	}

	/// Target application name to route to.
	@JsonPropertyDescription("Target application name to route to")
	public String getNext() {
		return next;
	}

	public Transition setNext(String next) {
		this.next = next;
		return this;
	}

	/// SIP header name whose URI identifies the subscriber (e.g. "From" or
	/// "To"). The container resolves it for the application's subscriber-URI API.
	@JsonPropertyDescription("SIP header name whose URI identifies the subscriber, e.g. From or To")
	public String getSubscriber() {
		return subscriber;
	}

	public Transition setSubscriber(String subscriber) {
		this.subscriber = subscriber;
		return this;
	}

	/// JSR-289 routing region the application is invoked in: ORIGINATING
	/// (serving the caller), TERMINATING (serving the callee), or NEUTRAL
	/// (the default when absent). Only matters to applications that call
	/// `request.getRegion()` — BLADE's own services don't, but third-party
	/// JSR-289 apps written IMS-style may.
	@JsonPropertyDescription("JSR-289 routing region: ORIGINATING, TERMINATING, or NEUTRAL (default)")
	public Region getRegion() {
		return region;
	}

	public Transition setRegion(Region region) {
		this.region = region;
		return this;
	}

	/// Optional SIP route URIs pushed as Route headers. May contain `${}`
	/// placeholders resolved against the context, e.g. `sip:${To.user}@proxy`.
	/// Interpreted according to [#getRouteModifier] (ROUTE by default).
	@JsonPropertyDescription("Optional SIP route URIs pushed as Route headers; may contain ${} placeholders, e.g. sip:${To.user}@proxy")
	public String[] getRoutes() {
		return routes;
	}

	public Transition setRoutes(String[] routes) {
		this.routes = routes;
		return this;
	}

	/// How the routes array is applied: ROUTE (default), ROUTE_BACK, or
	/// ROUTE_FINAL. Only meaningful when [#routes] is non-empty.
	@JsonPropertyDescription("How the routes array is applied: ROUTE (default), ROUTE_BACK, or ROUTE_FINAL. Ignored when routes is empty.")
	public SipRouteModifier getRouteModifier() {
		return routeModifier;
	}

	public Transition setRouteModifier(SipRouteModifier routeModifier) {
		this.routeModifier = routeModifier;
		return this;
	}

	/// Convenience: set routes and mark them ROUTE_BACK in one call.
	public Transition setRouteBack(String[] routes) {
		this.routes = routes;
		this.routeModifier = SipRouteModifier.ROUTE_BACK;
		return this;
	}

	/// Convenience: set routes and mark them ROUTE_FINAL in one call.
	public Transition setRouteFinal(String[] routes) {
		this.routes = routes;
		this.routeModifier = SipRouteModifier.ROUTE_FINAL;
		return this;
	}

	/// Evaluates [#when] against the context. Compiles lazily on first use;
	/// parse errors resolve to false so a malformed condition can't abort the
	/// routing decision (matches iRouter's `Clause` behavior). An empty/absent
	/// condition matches unconditionally.
	public boolean matches(Context ctx) {
		if (when == null || when.isEmpty()) {
			return true;
		}
		try {
			if (compiled == null) {
				compiled = new Expression(when);
			}
			return compiled.evaluate(ctx);
		} catch (Exception e) {
			return false;
		}
	}

	/// Builds the SipApplicationRouterInfo for this transition.
	///
	/// The routing region comes from [#getRegion] (NEUTRAL when absent). If
	/// [#subscriber] is set, extracts the URI from that named header (the
	/// container hands it to the app's subscriber-URI API). Each route is
	/// `${}`-resolved against `ctx` before being pushed. `stateInfo` is the
	/// wrapper carried across App Router invocations.
	public SipApplicationRouterInfo createRouterInfo(String deployedAppName, Serializable stateInfo,
			Context ctx, SipServletRequest request) {

		String subscriberURI = null;
		SipRouteModifier modifier = SipRouteModifier.NO_ROUTE;
		String[] routeArray = null;

		SipApplicationRoutingRegion routingRegion = SipApplicationRoutingRegion.NEUTRAL_REGION;
		if (region == Region.ORIGINATING) {
			routingRegion = SipApplicationRoutingRegion.ORIGINATING_REGION;
		} else if (region == Region.TERMINATING) {
			routingRegion = SipApplicationRoutingRegion.TERMINATING_REGION;
		}

		// Extract subscriber URI from the named header.
		if (subscriber != null) {
			try {
				subscriberURI = request.getAddressHeader(subscriber).getURI().toString();
			} catch (Exception e) {
				// Header not present or not an address header; leave subscriber null.
			}
		}

		// Resolve ${} placeholders in each route against the context.
		if (routes != null && routes.length > 0) {
			routeArray = new String[routes.length];
			for (int i = 0; i < routes.length; i++) {
				routeArray[i] = (ctx != null) ? ctx.resolve(routes[i]) : routes[i];
			}
			modifier = (routeModifier != null) ? routeModifier : SipRouteModifier.ROUTE;
		}

		return new SipApplicationRouterInfo(deployedAppName, routingRegion,
				subscriberURI, routeArray, modifier, stateInfo);
	}

}
