package org.vorpal.blade.framework.v3.fsmar;

import java.io.Serializable;
import java.util.HashMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Flow editor layout carried inside the FSMAR 3 config — geometry and the
/// ingress role only, never routing semantics. The AppRouter ignores it
/// entirely; deleting it costs nothing but the saved layout (the editor
/// auto-lays-out whatever has no stored placement).
///
/// - [#getStates]: position per vertex, keyed by state name (includes
///   `"null"`, the default ingress, and every ingress state).
/// - [#getIngresses]: which states are ingress entry points and each one's
///   source-match. An ingress is a real FSMAR state (its own selectors and
///   transitions); the `"null"` state is the implicit default ingress
///   ("any source") and is not listed here. The editor renders listed states
///   as ingress clouds and recognizes the generated source-dispatch
///   transitions on `"null"` (those whose `next` is a listed ingress) as
///   classification plumbing rather than drawn arrows.
/// - [#getEgresses]: the exit points — where the call leaves OCCAS. The mirror
///   of ingresses: each carries the routes and route-modifier the editor bakes
///   onto the terminal transition that targets it (whose `next` becomes
///   `null`). The editor renders these as egress clouds.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "states", "ingresses", "egresses" })
public class Diagram implements Serializable {
	private static final long serialVersionUID = 1L;

	private HashMap<String, Placement> states;
	private HashMap<String, Ingress> ingresses;
	private HashMap<String, Egress> egresses;

	@JsonPropertyDescription("Position per vertex, keyed by state name (includes 'null' and ingress states)")
	public HashMap<String, Placement> getStates() {
		return states;
	}

	public void setStates(HashMap<String, Placement> states) {
		this.states = states;
	}

	@JsonPropertyDescription("Ingress entry states (state name -> source-match); 'null' is the implicit default ingress and is not listed")
	public HashMap<String, Ingress> getIngresses() {
		return ingresses;
	}

	public void setIngresses(HashMap<String, Ingress> ingresses) {
		this.ingresses = ingresses;
	}

	@JsonPropertyDescription("Egress exit nodes (name -> routes + route-modifier); where the call leaves OCCAS")
	public HashMap<String, Egress> getEgresses() {
		return egresses;
	}

	public void setEgresses(HashMap<String, Egress> egresses) {
		this.egresses = egresses;
	}

}
