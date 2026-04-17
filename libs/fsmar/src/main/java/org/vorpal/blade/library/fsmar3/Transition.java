package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.servlet.sip.ar.SipApplicationRoutingRegion;
import javax.servlet.sip.ar.SipRouteModifier;

import org.vorpal.blade.framework.v3.configuration.RequestSelector;
import org.vorpal.blade.framework.v3.configuration.SelectorGroup;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A routing transition in the FSMAR state machine.
///
/// Combines pattern matching (via selector groups), subscriber identification,
/// optional route pushing, and the target application into a single flat
/// structure. Selector groups are evaluated with OR logic — the first matching
/// group wins. Within a group, selectors are ANDed together.
@JsonPropertyOrder({ "id", "selectorGroups", "subscriber", "routes", "next" })
public class Transition implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private List<SelectorGroup> selectorGroups;
	private String subscriber;
	private String[] routes;
	private String next;

	/// Optional identifier for logging and diagnostics.
	@JsonPropertyDescription("Optional identifier for logging and diagnostics")
	public String getId() {
		return id;
	}

	public Transition setId(String id) {
		this.id = id;
		return this;
	}

	/// Selector groups evaluated with OR logic; first matching group wins.
	/// Within each group, all selectors must match (AND logic).
	/// If null or empty, the transition matches unconditionally.
	@JsonPropertyDescription("Selector groups (OR logic); each group contains selectors (AND logic). Null or empty matches unconditionally.")
	public List<SelectorGroup> getSelectorGroups() {
		return selectorGroups;
	}

	public void setSelectorGroups(List<SelectorGroup> selectorGroups) {
		this.selectorGroups = selectorGroups;
	}

	public SelectorGroup addSelectorGroup() {
		if (this.selectorGroups == null) {
			this.selectorGroups = new ArrayList<>();
		}
		SelectorGroup group = new SelectorGroup();
		this.selectorGroups.add(group);
		return group;
	}

	/// Convenience: creates a single-selector group and adds it.
	public Transition addSelector(RequestSelector selector) {
		addSelectorGroup().addSelector(selector);
		return this;
	}

	/// SIP header name whose URI identifies the subscriber (e.g. "From" or "To").
	@JsonPropertyDescription("SIP header name whose URI identifies the subscriber, e.g. From or To")
	public String getSubscriber() {
		return subscriber;
	}

	public Transition setSubscriber(String subscriber) {
		this.subscriber = subscriber;
		return this;
	}

	/// Optional SIP route URIs pushed as Route headers (SipRouteModifier.ROUTE).
	@JsonPropertyDescription("Optional SIP route URIs pushed as Route headers")
	public String[] getRoutes() {
		return routes;
	}

	public Transition setRoutes(String[] routes) {
		this.routes = routes;
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

	/// Evaluates selector groups with OR logic. Returns true if any group matches,
	/// or if no groups are defined (unconditional match).
	public boolean matches(SipServletRequest request) {
		if (selectorGroups == null || selectorGroups.isEmpty()) {
			return true;
		}
		for (SelectorGroup group : selectorGroups) {
			if (group.matches(request)) {
				return true;
			}
		}
		return false;
	}

	/// Builds the SipApplicationRouterInfo for this transition.
	///
	/// Uses NEUTRAL_REGION. If [subscriber] is set, extracts the URI from that
	/// header. If [routes] is set, uses SipRouteModifier.ROUTE.
	public SipApplicationRouterInfo createRouterInfo(String deployedAppName, AppRouterConfiguration config,
			SipServletRequest request) {

		String subscriberURI = null;
		SipRouteModifier modifier = SipRouteModifier.NO_ROUTE;
		String[] routeArray = null;

		// Extract subscriber URI from the named header
		if (subscriber != null) {
			try {
				subscriberURI = request.getAddressHeader(subscriber).getURI().toString();
			} catch (Exception e) {
				// Header not present or not an address header; leave subscriber null
			}
		}

		// Set up routes
		if (routes != null && routes.length > 0) {
			routeArray = routes;
			modifier = SipRouteModifier.ROUTE;
		}

		return new SipApplicationRouterInfo(deployedAppName, SipApplicationRoutingRegion.NEUTRAL_REGION, subscriberURI,
				routeArray, modifier, config);
	}

}
