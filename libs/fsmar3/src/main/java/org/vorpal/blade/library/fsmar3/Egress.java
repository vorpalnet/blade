package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Flow editor marker that a node is an egress exit point — where the call
/// leaves OCCAS. The mirror image of [Ingress]: pure presentation metadata in
/// [Diagram#getEgresses]. An egress is not a deployed application; it is a named
/// exit whose [#getRoutes] the editor bakes onto each transition that targets
/// it. The AppRouter never reads this; it sees only the resulting transition.
///
/// The exit KIND is topology, not a stored modifier: [#getReturnState] absent →
/// ROUTE_FINAL (the call leaves OCCAS for good); present → ROUTE_BACK (the call
/// goes out to the routes, then the container routes it back and the flow
/// resumes at that state — see `AppRouter` and the route-back line in the Flow
/// editor).
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "routes", "returnState" })
// `description` retired (folded into Configuration.notes); tolerate in old configs.
@JsonIgnoreProperties("description")
public class Egress implements Serializable {
	private static final long serialVersionUID = 2L;

	private String[] routes;
	private String returnState;

	public Egress() {
	}

	public Egress(String[] routes, String returnState) {
		this.routes = routes;
		this.returnState = returnState;
	}

	/// SIP route URIs the editor bakes onto each transition that targets this
	/// egress. May contain `${}` placeholders resolved against the context,
	/// e.g. `sip:${To.user}@carrier-trunk`.
	@JsonPropertyDescription("SIP route URIs pushed when the call exits here; may contain ${} placeholders, e.g. sip:${To.user}@carrier-trunk")
	public String[] getRoutes() {
		return routes;
	}

	public Egress setRoutes(String[] routes) {
		this.routes = routes;
		return this;
	}

	/// The state the flow resumes at when a route-back call returns. Present =
	/// ROUTE_BACK (the editor's route-back line points here); absent =
	/// ROUTE_FINAL (the call leaves OCCAS for good).
	@JsonPropertyDescription("Resume state for a route-back exit (the call returns and the flow continues here); absent = ROUTE_FINAL")
	public String getReturnState() {
		return returnState;
	}

	public Egress setReturnState(String returnState) {
		this.returnState = returnState;
		return this;
	}

}
